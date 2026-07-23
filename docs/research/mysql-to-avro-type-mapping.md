# MySQL 8.0 列类型 → Connect Schema → Avro → PostgreSQL 15 映射规则

> 研究票：[#5](https://github.com/liumingjian/dbx/issues/5)。链路：
> MySQL 8.0 --(Connector/J 8.x)--> Confluent JDBC Source --> Connect Schema
> --> AvroConverter + Schema Registry --> Kafka --> JDBC Sink --> PostgreSQL 15。
>
> 一手来源：MySQL 8.0 参考手册、Connector/J 官方手册、`confluentinc/kafka-connect-jdbc`
> 与 `confluentinc/schema-registry`（`AvroData`）源码、Avro 1.11 规范、PostgreSQL 15 手册。

## 结论摘要

1. **`numeric.mapping` 对 MySQL 完全无效。** `GenericDatabaseDialect.addFieldToSchema()`
   的四种 `numeric.mapping` 分支全部写在 `case Types.NUMERIC:` 里，靠 fallthrough
   落到 `case Types.DECIMAL:`。而 Connector/J 的 `MysqlType.DECIMAL` / `DECIMAL_UNSIGNED`
   声明的 JDBC 类型是 `Types.DECIMAL`，**不是** `Types.NUMERIC`。
   → MySQL 的 `DECIMAL`/`NUMERIC` 列**永远**变成 Connect `Decimal` 逻辑类型，
   `numeric.mapping` 设成什么都一样。**这对 DBX 是好事**：不会静默变 double。
   但也意味着「用 `numeric.mapping=best_fit` 把小数值降成 double 提速」这条路在 MySQL 上不存在。
2. **无符号整型会自动升宽**，因为 `addFieldToSchema()` 对 TINYINT/SMALLINT/INTEGER
   检查 `columnDefn.isSignedNumber()`。`TINYINT UNSIGNED`→INT16、`SMALLINT UNSIGNED`→INT32、
   `INT UNSIGNED`→INT64。**唯独 `BIGINT UNSIGNED` 没有升宽分支**（`case Types.BIGINT`
   无条件 INT64）。值 > 2^63-1 时：默认 `jdbcCompliantTruncation=true` 下
   `LongValueFactory.createFromBigInteger()` 抛 `NumberOutOfRange`（任务红）；
   若有人把它关掉，就变成 `i.longValue()` **静默回绕成负数**。
   **v1 必须显式保持 `jdbcCompliantTruncation=true`，并在前端对 `BIGINT UNSIGNED` 告警。**
3. **`TINYINT(1)` 默认变成 Connect INT8，不是 BOOLEAN。** Connector/J 默认
   `tinyInt1isBit=true` 把 `TINYINT(1)` 报成 `BIT`（`Types.BIT`），
   而 `addFieldToSchema()` 的 `case Types.BIT` 建的是 **INT8**（注释：`// ints <= 8 bits`），
   不是 BOOLEAN。想拿到真 BOOLEAN 必须显式加 `transformedBitIsBoolean=true`
   （此时 JDBC 类型是 `Types.BOOLEAN`）。**两种配置产出的 Avro schema 不同，必须由平台显式固定。**
4. **时区**：Source 的 `db.timezone`（默认 `UTC`）被用作
   `rs.getTimestamp(col, Calendar)` 的日历。MySQL `DATETIME` 无时区、`TIMESTAMP` 是
   UTC 存储按 session `time_zone` 呈现 —— 两者在 Connect 里**都变成同一个
   `org.apache.kafka.connect.data.Timestamp`（epoch millis）**，时区语义丢失。
   Connector/J 8.0 用 `connectionTimeZone`（取代已废弃的 `serverTimezone`）+
   `forceConnectionTimeZoneToSession` + `preserveInstants` 控制转换。
   **v1 基线：全链路 UTC**，PG 侧 `DATETIME`→`timestamp(6)`（无 TZ）、`TIMESTAMP`→`timestamptz`。
5. **毫秒截断**：Connect `Timestamp`/`Time` 逻辑类型 → Avro `timestamp-millis`/`time-millis`
   （`AvroData` 硬编码 millis，无 micros 分支）。MySQL `DATETIME(6)`/`TIME(6)`/`TIMESTAMP(6)`
   的微秒部分**静默丢失**。要保微秒必须设 `timestamp.granularity=micros_long`
   （Connect INT64）或 `micros_iso_datetime_string`，且 Sink 侧要相应处理。
6. **`JSON` 降级为字符串**：`MysqlType.JSON` → `Types.LONGVARCHAR` → Connect STRING → Avro `string`。
   PG 侧我们可以自己建 `JSONB`，Sink 的 `PostgreSqlDatabaseDialect.valueTypeCast()`
   会自动渲染 `?::jsonb`（见 #4 结论 8）。
7. **`GEOMETRY` 和 `BIT(n>1)` 会被静默丢列**或落成 bytes：`GEOMETRY` 报 `Types.BINARY`
   → Connect BYTES（内部 WKB，PG 侧不是 PostGIS 可用格式）；`BIT(n)` 报 `Types.BIT`
   → Connect **INT8**，n>8 时溢出。二者 v1 都应列为**不支持**并在前端拦截。
8. **`ENUM`/`SET` → `Types.CHAR` → STRING**，枚举约束丢失；PG 侧建议落 `TEXT` + `CHECK`，
   不要用 PG `enum`（Sink 不会做 cast，`DbStructure` 也不校验类型）。
9. **长度语义**：MySQL `VARCHAR(n)` 的 n 是**字符**数，PG 15 `varchar(n)` 的 n 也是**字符**数
   → `VARCHAR(n)` → `varchar(n)` 长度安全，utf8mb4 不会因字节膨胀而截断。
   但 Connect schema **不携带长度**（`addFieldToSchema()` 注释：
   "we drop this from the schema conversion"），长度必须由 DBX 从
   `information_schema.columns` 自己读取并写进 DDL。
10. 承接 #4：Sink 不校验类型，只按列名匹配 → **本文的「推荐 PG 落点」列就是 DDL 生成器的规格**。

---

## 1. 链路各段的判定代码（结论依据）

| 段 | 决定映射的代码/文档 | 说明 |
|---|---|---|
| MySQL → JDBC 类型 | `com.mysql.cj.MysqlType` 枚举常量（每个常量第 2 个参数即 `java.sql.Types`） | [Connector/J 类型转换表](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-type-conversions.html) |
| JDBC 类型 → Connect Schema | `GenericDatabaseDialect.addFieldToSchema(ColumnDefinition, SchemaBuilder, String, int, boolean)` | `MySqlDatabaseDialect` **未覆写**此方法，故 MySQL 走通用逻辑 |
| ResultSet 取值 | `GenericDatabaseDialect.columnConverterFor(...)` | 日期时间用 `DateTimeUtils.getZoneIdCalendar(zoneId)`，`zoneId` 来自 `db.timezone` |
| Connect Schema → Avro | `io.confluent.connect.avro.AvroData.fromConnectSchema()` | 逻辑类型：`decimal`/`date`/`time-millis`/`timestamp-millis` |
| Avro 规范 | [Avro 1.11 Logical Types](https://avro.apache.org/docs/1.11.1/specification/#logical-types) | `decimal` 附 `precision`/`scale` |
| Connect Schema → PG DDL（若用 auto.create） | `PostgreSqlDatabaseDialect.getSqlType()` | 能力弱于 DBX 自研映射，见 #4 §3 |

> 说明：下文「PG 落点」是 DBX **自己生成 DDL** 时的推荐值，不是 `auto.create` 的产物。
> Sink 不校验类型（#4 结论 1），列名必须与 Connect 字段名逐字符相等（#4 结论 2）。

`MySqlDatabaseDialect` 只覆写了 `buildAuthenticationProperties`、
`initializePreparedStatement`、`getSqlType(SinkRecordField)`、`buildUpsertQueryStatement`、
`sanitizedUrl`、`resolveSynonym` —— 全部是 **Sink 侧**能力，Source 侧行为 100% 由
`GenericDatabaseDialect` 决定。

---

## 2. 映射大表

图例：`optional` = 列可空时 Connect schema 为 optional，Avro 为 `["null", T]` 且 default null。
下表 Avro 列只写非 null 分支。**Connect schema 不携带任何长度/精度信息**（DECIMAL 除外）。

### 2.1 整数与浮点

| MySQL 类型 | Connector/J JDBC 类型 | Connect Schema | Avro | 推荐 PG 15 落点 | 有损风险 | 影响参数 | 来源 |
|---|---|---|---|---|---|---|---|
| `TINYINT` / `TINYINT(M≠1)` | `Types.TINYINT`（signed） | `INT8` | `int` | `smallint` | 无 | — | `MysqlType.TINYINT`；`addFieldToSchema` `case Types.TINYINT` |
| `TINYINT(1)`（默认） | `Types.BIT`（`tinyInt1isBit=true`, `transformedBitIsBoolean=false`） | **`INT8`**（非 boolean！） | `int` | `smallint` 或 `boolean`（需自行确保 0/1） | 语义丢失：布尔列变整数 | `tinyInt1isBit`, `transformedBitIsBoolean` | Connector/J 类型转换表；`case Types.BIT` |
| `TINYINT(1)` + `transformedBitIsBoolean=true` | `Types.BOOLEAN` | `BOOLEAN` | `boolean` | `boolean` | 无 | 同上 | 同上 |
| `TINYINT(M)` + `tinyInt1isBit=false` | `Types.TINYINT` | `INT8` | `int` | `smallint` | 无 | `tinyInt1isBit=false` | 同上 |
| `TINYINT UNSIGNED` | `Types.TINYINT`（unsigned） | `INT16` | `int` | `smallint` | 无 | — | `isSignedNumber()` 分支 |
| `SMALLINT` | `Types.SMALLINT` | `INT16` | `int` | `smallint` | 无 | — | `MysqlType.SMALLINT` |
| `SMALLINT UNSIGNED` | `Types.SMALLINT`（unsigned） | `INT32` | `int` | `integer` | 无 | — | 同上 |
| `MEDIUMINT` | `Types.INTEGER` | `INT32` | `int` | `integer` | 无 | — | `MysqlType.MEDIUMINT` |
| `MEDIUMINT UNSIGNED` | `Types.INTEGER`（unsigned） | `INT64` | `long` | `integer`（值域安全）或 `bigint` | 无 | — | 同上 |
| `INT` / `INTEGER` | `Types.INTEGER` | `INT32` | `int` | `integer` | 无 | — | `MysqlType.INT` |
| `INT UNSIGNED` | `Types.INTEGER`（unsigned） | **`INT64`** | `long` | `bigint` | 无（升宽安全） | — | `case Types.INTEGER` 的 `isSignedNumber()` |
| `BIGINT` | `Types.BIGINT` | `INT64` | `long` | `bigint` | 无 | — | `MysqlType.BIGINT` |
| `BIGINT UNSIGNED` | `Types.BIGINT`（unsigned，`getColumnClassName`=`BigInteger`） | **`INT64`（无升宽）** | `long` | `numeric(20,0)` | **值 > 2^63-1 时溢出**；默认抛 `NumberOutOfRange`，`jdbcCompliantTruncation=false` 时静默回绕 | `jdbcCompliantTruncation` | `case Types.BIGINT` 无 signed 分支；`LongValueFactory.createFromBigInteger()` |
| `FLOAT` / `FLOAT UNSIGNED` | `Types.REAL` | `FLOAT32` | `float` | `real` | 二进制浮点固有 | — | `MysqlType.FLOAT`；`case Types.REAL` |
| `FLOAT(M,D)` | `Types.REAL` | `FLOAT32` | `float` | `real` | D 位小数约束丢失 | — | 同上 |
| `DOUBLE` / `REAL` / `DOUBLE UNSIGNED` | `Types.DOUBLE` | `FLOAT64` | `double` | `double precision` | 二进制浮点固有 | — | `MysqlType.DOUBLE` |
| `DECIMAL(p,s)` / `NUMERIC(p,s)` | **`Types.DECIMAL`**（非 NUMERIC） | `Decimal`（`bytes`+`scale=s`+参数 `connect.decimal.precision=p`） | `bytes`，`logicalType=decimal`，`precision=p`,`scale=s` | `numeric(p,s)` | 无（前提：PG 侧 p,s 与源一致） | `numeric.mapping` **无效**（见 §3） | `MysqlType.DECIMAL`；`addFieldToSchema` `case Types.DECIMAL` |
| `DECIMAL` 无显式 (p,s) | `Types.DECIMAL` | `Decimal(scale=0)`，precision=10 | 同上 | `numeric(10,0)` | MySQL 默认即 `DECIMAL(10,0)` | — | MySQL 8.0 手册 11.1.3 |
| `DECIMAL(p,s) UNSIGNED` | `Types.DECIMAL` | 同 signed | 同上 | `numeric(p,s)`（+`CHECK (col >= 0)`） | 无符号约束丢失 | — | `MysqlType.DECIMAL_UNSIGNED` |
| `BOOL` / `BOOLEAN` | 同 `TINYINT(1)` | 同 `TINYINT(1)` | 同上 | 同上 | 同上 | 同上 | MySQL 8.0 手册：`BOOL` 是 `TINYINT(1)` 的同义词 |
| `BIT(1)` | `Types.BIT` | `INT8` | `int` | `boolean` 或 `smallint` | 语义丢失 | — | `MysqlType.BIT`；`case Types.BIT` |
| `BIT(n)` 2≤n≤8 | `Types.BIT` | `INT8` | `int` | `smallint` | **值 128–255 静默变负数**（`(byte)` 截断） | `jdbcCompliantTruncation` | `ByteValueFactory.createFromBit()` |
| `BIT(n)` n>8 | `Types.BIT` | `INT8` | `int` | — **v1 不支持** | 值 ≥256 抛 `NumberOutOfRange`（默认） | 同上 | 同上 |

### 2.2 字符与二进制

`addFieldToSchema()` 把 CHAR/VARCHAR/LONGVARCHAR/NCHAR/NVARCHAR/LONGNVARCHAR/CLOB/NCLOB/
DATALINK/SQLXML 合并到同一分支 → 一律 `STRING`；BINARY/BLOB/VARBINARY/LONGVARBINARY
一律 `BYTES`。**长度被显式丢弃**（源码注释："Some of these types will have fixed size,
but we drop this from the schema conversion since only fixed byte arrays can have a fixed size"）。

| MySQL 类型 | Connector/J JDBC 类型 | Connect Schema | Avro | 推荐 PG 15 落点 | 有损风险 | 影响参数 | 来源 |
|---|---|---|---|---|---|---|---|
| `CHAR(M)` | `Types.CHAR` | `STRING` | `string` | `char(M)`（保尾部空格语义）或 `varchar(M)` | MySQL 检索 `CHAR` 会去尾部空格，PG `char(n)` 比较时忽略尾空格 —— 语义近似但不等价 | `padCharsWithSpace` | `MysqlType.CHAR` |
| `VARCHAR(M)` | `Types.VARCHAR` | `STRING` | `string` | `varchar(M)` 或 `text` | 无（M 两边都按字符计） | `characterEncoding` | `MysqlType.VARCHAR`；MySQL 8.0 手册 11.3.2；PG 15 手册 8.3 |
| `TINYTEXT` | `Types.VARCHAR` | `STRING` | `string` | `text` | 无 | — | `MysqlType.TINYTEXT` |
| `TEXT` / `MEDIUMTEXT` / `LONGTEXT` | `Types.LONGVARCHAR` | `STRING` | `string` | `text` | `LONGTEXT` 最大 4 GiB，受 Kafka `max.request.size`/`message.max.bytes` 限制而非类型限制 | — | `MysqlType.TEXT/MEDIUMTEXT/LONGTEXT` |
| `ENUM('a','b',…)` | `Types.CHAR`（`getColumnTypeName`=`CHAR`） | `STRING` | `string` | `text` + `CHECK (col IN (…))` | 取值集合约束丢失；**Avro 不是 `enum` 类型** | — | `MysqlType.ENUM`；Connector/J 类型转换表 |
| `SET('a','b',…)` | `Types.CHAR` | `STRING` | `string` | `text`（值形如 `"a,b"`）+ `CHECK` | 集合语义丢失，退化成逗号分隔串 | — | `MysqlType.SET` |
| `JSON` | **`Types.LONGVARCHAR`** | `STRING` | `string` | **`jsonb`**（Sink 自动 `?::jsonb`）或 `text` | JSON 有效性不再校验；`jsonb` 会重排 key、去重复 key、丢失空白（若需字节级保真用 `json` 或 `text`） | — | `MysqlType.JSON`；`PostgreSqlDatabaseDialect.valueTypeCast()`（见 #4 §结论 8） |
| `BINARY(M)` | `Types.BINARY` | `BYTES` | `bytes` | `bytea` | MySQL `BINARY` 右侧补 `0x00` 到 M；PG `bytea` 不补 → 往返不等长 | — | `MysqlType.BINARY` |
| `VARBINARY(M)` / `TINYBLOB` | `Types.VARBINARY` | `BYTES` | `bytes` | `bytea` | 无 | — | `MysqlType.VARBINARY/TINYBLOB` |
| `BLOB` / `MEDIUMBLOB` / `LONGBLOB` | `Types.LONGVARBINARY` | `BYTES` | `bytes` | `bytea` | 大对象受 Kafka 消息大小限制；`columnConverterFor` 的 `case Types.BLOB` 对 >`Integer.MAX_VALUE` 抛 `IOException`（但 MySQL 走 LONGVARBINARY 分支 `rs.getBytes()`，整块入堆内存） | `useBlobToStoreUTF8OutsideBMP`, `blobsAreStrings`, `functionsNeverReturnBlobs` | `MysqlType.BLOB` 等 |
| `GEOMETRY` 及子类型 | `Types.BINARY` | `BYTES` | `bytes` | **v1 不支持** | 值是 MySQL 内部格式（4 字节 SRID + WKB），直接落 `bytea` 无法被 PostGIS 识别 | — | `MysqlType.GEOMETRY` |
| `VECTOR(M)`（8.4+） | `Types.LONGVARBINARY` | `BYTES` | `bytes` | **v1 不支持**（8.0 无此类型） | — | — | `MysqlType.VECTOR` |

**utf8mb4 下的长度语义（关键澄清）**

- MySQL：`VARCHAR(M)` 的 M 是**字符**数，最大 65,535 **字节**行内限制，
  utf8mb4 下 M 上限约 16,383（[MySQL 8.0 手册 11.3.2 / 11.7](https://dev.mysql.com/doc/refman/8.0/en/char.html)）。
- PostgreSQL 15：`varchar(n)` / `char(n)` 的 n 也是**字符**数，
  且「不管字符集」（[PG 15 手册 8.3](https://www.postgresql.org/docs/15/datatype-character.html)）。
- **结论：`VARCHAR(255)` → `varchar(255)` 是安全的，不会因 utf8mb4 字节膨胀而截断。**
  唯一注意点是 PG 单行超过约 2 KB 会走 TOAST（性能而非正确性）。
- 但 **`CHAR(M) BINARY` / `VARCHAR(M) BINARY`（即 binary collation）会被 Connector/J
  报成 `BINARY`/`VARBINARY`** → Connect `BYTES` 而非 `STRING`。DDL 生成器必须读
  `information_schema.columns.character_set_name`：为 `binary` 时落 `bytea`，不能落 `text`。
