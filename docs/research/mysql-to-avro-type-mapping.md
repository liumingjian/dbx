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

### 2.3 日期与时间

`timestamp.granularity` 默认 `connect_logical`；其余取值见
`JdbcSourceConnectorConfig.TimestampGranularity` 枚举，直接改变 `Types.TIMESTAMP`
的 Connect schema 类型。

| MySQL 类型 | Connector/J JDBC 类型 | Connect Schema | Avro | 推荐 PG 15 落点 | 有损风险 | 影响参数 | 来源 |
|---|---|---|---|---|---|---|---|
| `DATE` | `Types.DATE`（`java.sql.Date`） | `org.apache.kafka.connect.data.Date`（INT32，天数） | `int`, `logicalType=date` | `date` | `0000-00-00` 触发 `zeroDateTimeBehavior` | `zeroDateTimeBehavior`, `db.timezone` | `MysqlType.DATE`；`case Types.DATE` |
| `DATETIME` / `DATETIME(0)` | `Types.TIMESTAMP`（`LocalDateTime`） | `Timestamp`（INT64，epoch millis） | `long`, `logicalType=timestamp-millis` | `timestamp(0) without time zone` | **无时区语义被强行赋予时区**：按 `db.timezone` 解释成瞬时 | `db.timezone`, `connectionTimeZone`, `timestamp.granularity` | `MysqlType.DATETIME`；`case Types.TIMESTAMP` |
| `DATETIME(1..3)` | 同上 | 同上 | 同上 | `timestamp(3)` | 无 | 同上 | 同上 |
| `DATETIME(4..6)` | 同上 | 同上（**毫秒**） | `timestamp-millis` | `timestamp(6)` | **微秒静默截断**（`AvroData` 只有 `timestampMillis()`） | `timestamp.granularity=micros_long` 可保 | `AvroData.fromConnectSchema()` |
| `TIMESTAMP` / `TIMESTAMP(0)` | `Types.TIMESTAMP`（`java.sql.Timestamp`） | `Timestamp`（INT64，epoch millis） | `long`, `logicalType=timestamp-millis` | **`timestamptz(0)`** | 与 `DATETIME` **在 Connect 层无法区分**；错配时区会整体平移 | `connectionTimeZone`, `preserveInstants`, `forceConnectionTimeZoneToSession`, `db.timezone` | `MysqlType.TIMESTAMP` |
| `TIMESTAMP(4..6)` | 同上 | 同上 | 同上 | `timestamptz(6)` | 微秒静默截断 | `timestamp.granularity` | 同上 |
| `TIME` / `TIME(0..6)` | `Types.TIME`（`java.sql.Time`） | `org.apache.kafka.connect.data.Time`（INT32，当日毫秒） | `int`, `logicalType=time-millis` | `time(0..6) without time zone` | ① 微秒截断；② **MySQL `TIME` 值域是 `-838:59:59`～`838:59:59`，PG `time` 只有 `00:00:00`～`24:00:00`**，超范围值必然出错；③ 负值无法用 `time-millis` 表示 | `db.timezone`, `sendFractionalSecondsForTime` | `MysqlType.TIME`；`case Types.TIME`；MySQL 8.0 手册 11.2.3；PG 15 手册 8.5 |
| `YEAR`（默认） | `Types.DATE`（`yearIsDateType=true` → `java.sql.Date`） | `Date`（INT32 天数） | `int`, `logicalType=date` | `date`（值为 `YYYY-01-01`）；或改配置后落 `smallint` | 语义膨胀成整年首日 | `yearIsDateType` | `MysqlType.YEAR`；Connector/J 类型转换表 |
| `YEAR` + `yearIsDateType=false` | `Types.DATE`（JDBC 类型不变，只是取值类为 `Short`） | 仍是 `Date` | 同上 | 同上 | 同上 | 同上 | 同上（**注意：JDBC 类型由 `MysqlType.YEAR` 固定为 `Types.DATE`，故 Connect schema 不受此参数影响**） |

**`TIME` 是 v1 最容易翻车的日期类型**：`case Types.TIME` 的转换器是
`rs.getTime(col, DateTimeUtils.getZoneIdCalendar(zoneId))`，把「一段时长」当「当日时刻」处理。
建议 DDL 生成器对 `TIME` 列在前端标黄，并提供「落 `interval`（走 STRING 中转）」的备选。

### 2.4 特殊类型与 schema 元数据

| 项 | 链路上的表现 | 对 DDL 生成器的含义 | 来源 |
|---|---|---|---|
| 可空性 | `describeColumn()` 读 `ResultSetMetaData.isNullable()`；`ColumnDefinition.isOptional()` 在 `NULL` **和 `UNKNOWN`** 两种情况都返回 true → schema 为 optional，Avro 为 `["null", T]`，default `null` | 不能反过来用 Avro 的 optional 推 NOT NULL；NOT NULL 必须从 `information_schema.columns.is_nullable` 读 | `GenericDatabaseDialect.describeColumn()`, `ColumnDefinition.isOptional()` |
| 列名 | `fieldNameFor()` = `columnDefinition.id().aliasOrName()`，即 `ResultSetMetaData.getColumnLabel()`（有别名取别名） | PG 列名必须与之逐字符相等（#4 结论 2）；MySQL 列名大小写敏感性依赖 `lower_case_table_names`，PG 侧被双引号引用 → **一律按源端原样建列** | 同上 |
| 列默认值 | **不进 Connect schema**。Source 的 `addFieldToSchema()` 从不调用 `SchemaBuilder.defaultValue()`（除 optional 隐含 null） | MySQL 的 `DEFAULT` 必须由 DBX 从 `information_schema` 单独读取并翻译（注意 `CURRENT_TIMESTAMP`、`ON UPDATE CURRENT_TIMESTAMP` 在 PG 无直接等价） | `addFieldToSchema()` 全文 |
| `AUTO_INCREMENT` | `ColumnDefinition` 有 `isAutoIncremented()`，但 `addFieldToSchema()` **完全不读它** → schema 里没有任何痕迹 | PG 侧**不要**用 `GENERATED ALWAYS AS IDENTITY`（会拒绝显式插入值）；用普通 `bigint` 或 `GENERATED BY DEFAULT AS IDENTITY`，并在迁移后 `setval()` 对齐序列 | `ColumnDefinition`；PG 15 手册 8.1.4 |
| 生成列 `GENERATED ALWAYS AS (...) VIRTUAL/STORED` | 在 `SELECT *` 中与普通列无异，正常读出、正常映射 | PG 侧**不要**建成生成列（会拒绝 INSERT 指定值，报 `428C9`）；建成普通列，或从迁移中排除该列 | MySQL 8.0 手册 13.1.20.8；PG 15 手册 5.3 |
| `BIT(n>8)` | `Types.BIT` → INT8，读取时 `ByteValueFactory.createFromBit()` 抛 `NumberOutOfRange` | **v1 标记不支持**，前端硬拦 | `ByteValueFactory.createFromBit()` |
| `GEOMETRY` 家族 | `Types.BINARY` → BYTES，内容是 SRID+WKB | **v1 标记不支持** | `MysqlType.GEOMETRY` |
| MySQL 特有且 JDBC 类型落在默认分支的类型（`Types.ARRAY/JAVA_OBJECT/OTHER/STRUCT/REF/ROWID`） | `addFieldToSchema()` 走 `default:` → **`glog.warn("JDBC type {} ({}) not currently supported")` 并 `return null`，该列被静默丢弃**（任务全绿、数据缺列！） | 前端必须在建 connector 前枚举全部列类型并拒绝未知类型；不能依赖运行期报错 | `addFieldToSchema()` `default:` 分支 |
| `Types.NULL` | 同样 `return null` 丢列 | 同上 | 同上 |
| Avro 命名 | `AvroData` 会把非法 Avro 名字的字段/记录名做处理，`connect.name` 属性保留原名 | 列名含非 `[A-Za-z0-9_]` 字符时需注意；v1 建议先限制在合法标识符 | `AvroData.CONNECT_NAME_PROP` |

**「静默丢列」是本链路最危险的失败模式**：类型不支持时 connector **不报错**，只在 log
打一条 WARN，字段直接不出现在 Avro schema 里；Sink 侧因为只按列名匹配、缺字段就不写
（#4 结论 1），最终表现是「任务全绿，PG 表该列全是 NULL 或默认值」。

---

## 3. 高风险项详解

### 3.1 `DECIMAL(p,s)` 与 `numeric.mapping` 的四种取值

`numeric.mapping` 的四个分支全部位于 `GenericDatabaseDialect.addFieldToSchema()`
的 `case Types.NUMERIC:` 内，末尾无 `break`，靠 `// fallthrough` 落入 `case Types.DECIMAL:`。
常量：`MAX_INTEGER_TYPE_PRECISION = 18`，`NUMERIC_TYPE_SCALE_LOW = -84`；
`integerSchema(optional, precision)` 在 `precision > 9` 时给 INT64，否则 INT32。

| 取值 | 触发条件（仅 `Types.NUMERIC`） | 结果 Connect 类型 |
|---|---|---|
| `none`（默认） | 不进入任何分支 | 一律 `Decimal(scale)` |
| `precision_only` | `scale == 0 && precision <= 18` | `INT32`(p≤9) / `INT64`(p>9)；其余 → `Decimal` |
| `best_fit` | `precision <= 18` 且 `-84 <= scale < 1` → 整数；`precision <= 18` 且 `scale > 0` → **`FLOAT64`** | 其余 → `Decimal` |
| `best_fit_eager_double` | `-84 <= scale < 1 && precision <= 18` → 整数；**`scale > 0` 时无论 precision 多大一律 `FLOAT64`** | 其余 → `Decimal` |

**对 MySQL 的实际结论：以上四种取值全部无效。**
Connector/J 的 `MysqlType.DECIMAL` 和 `DECIMAL_UNSIGNED` 都声明 `Types.DECIMAL`；
`MysqlType` 枚举中**没有任何常量声明 `Types.NUMERIC`**（MySQL 的 `NUMERIC` 是 `DECIMAL`
的同义词，见 MySQL 8.0 手册 11.1.1）。因此 MySQL 的所有定点列都直接命中 `case Types.DECIMAL`，
无条件产出 `Decimal` 逻辑类型。
配置文档：<https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/source_config_options.html>

**scale 的携带方式**（`case Types.DECIMAL` 分支）：

```java
scale = decimalScale(columnDefn);                    // 见下
SchemaBuilder fieldBuilder = Decimal.builder(scale); // 参数 "scale"
fieldBuilder.parameter(PRECISION_FIELD, Integer.toString(precision)); // "connect.decimal.precision"
```

`decimalScale()`：`defn.scale() == NUMERIC_TYPE_SCALE_UNSET ? NUMERIC_TYPE_SCALE_HIGH : defn.scale()`
—— MySQL 总会报出真实 scale，所以走 `defn.scale()`。
取值时 `columnConverterFor()` 用 `rs.getBigDecimal(col, scale)`，**按列定义的 scale 做定标**。

**Avro 侧**（`AvroData.fromConnectSchema()`）：Connect `Decimal` → Avro `bytes`，
`org.apache.avro.LogicalTypes.decimal(precision, scale).addToSchema(baseSchema)`；
`precision` 取 schema 参数 `connect.decimal.precision`，缺失时用默认值
（`CONNECT_AVRO_DECIMAL_PRECISION_DEFAULT`，即 64）。
若 `scale < 0 || scale > precision` 则退回 legacy 编码（只写 `logicalType`/`scale`/`precision` 属性）。
Avro 规范：decimal 用 `bytes` 存二进制补码的 unscaled 值，
<https://avro.apache.org/docs/1.11.1/specification/#decimal>。

**DDL 生成规则**：`DECIMAL(p,s)` → `numeric(p,s)`，p、s 必须从
`information_schema.columns.numeric_precision/numeric_scale` 原样带过来。
MySQL DECIMAL 上限 `p<=65, s<=30`（手册 11.1.3），PG `numeric` 上限 `p<=1000`（手册 8.1.2）→ 无溢出风险。
**不要**为了性能把 `DECIMAL` 落成 `double precision`：金额类字段会静默失真。

### 3.2 `TINYINT(1)` 与 `tinyInt1isBit` / `transformedBitIsBoolean`

Connector/J 官方类型转换表对 `TINYINT(1) SIGNED, BOOLEAN` 一行的原文规则：

| `tinyInt1isBit` | `transformedBitIsBoolean` | `getColumnTypeName` | JDBC 类型 | Connect Schema |
|---|---|---|---|---|
| `true`（默认） | `false`（默认） | `BIT` | `Types.BIT` | **`INT8`** |
| `true` | `true` | `BOOLEAN` | `Types.BOOLEAN` | `BOOLEAN` |
| `false` | 任意 | `TINYINT` | `Types.TINYINT` | `INT8` |

注意 `case Types.BIT` 在 `addFieldToSchema()` 里落在 `// ints <= 8 bits` 注释下，
建的是 `Schema.INT8_SCHEMA`，**不是** boolean；取值用 `rs.getByte(col)`。
所以「默认配置下 MySQL 布尔列到 Kafka 是 0/1 的 int8」。

**DBX 的取舍**：
- 若目标是「PG 侧得到真 `boolean`」→ 必须显式加 `transformedBitIsBoolean=true`。
  代价：该库**所有** `TINYINT(1)` 列都变 boolean，包括那些其实存 0..9 的列（值 >1 会被读成 `true`，**静默失真**）。
- 若目标是「零风险」→ 显式设 `tinyInt1isBit=false`，全部按 `TINYINT` 处理，PG 落 `smallint`。
- **v1 建议：`tinyInt1isBit=false` 作为全局默认**（安全），在表级映射里允许用户把特定列
  手工指定为 PG `boolean`（DDL 写 `boolean`，Sink 侧 int8 → PG boolean 会因类型不兼容失败，
  故该列需在 DDL 用 `smallint` + 视图，或整库切 `transformedBitIsBoolean=true`）。
  这是一张**需要在类型映射矩阵决策票里拍板的取舍**。

### 3.3 `INT UNSIGNED` / `BIGINT UNSIGNED` 溢出

`addFieldToSchema()` 对 TINYINT/SMALLINT/INTEGER 三档都写了
`if (columnDefn.isSignedNumber()) {...} else {升一档}`；`isSignedNumber()` 来自
`ResultSetMetaData.isSigned()`。所以：

- `TINYINT UNSIGNED`(0..255) → INT16 ✅
- `SMALLINT UNSIGNED`(0..65535) → INT32 ✅
- `MEDIUMINT UNSIGNED`(0..16777215) → INT64 ✅（过度升宽但安全）
- `INT UNSIGNED`(0..4294967295) → INT64 ✅
- **`BIGINT UNSIGNED`(0..18446744073709551615) → INT64 ❌**，`case Types.BIGINT` 没有
  `isSignedNumber()` 分支，转换器是 `rs.getLong(col)`。

Connector/J 的 `LongValueFactory.createFromBigInteger()`：

```java
if (this.jdbcCompliantTruncationForReads
        && (i.compareTo(Constants.BIG_INTEGER_MIN_LONG_VALUE) < 0
         || i.compareTo(Constants.BIG_INTEGER_MAX_LONG_VALUE) > 0)) {
    throw new NumberOutOfRange(...);
}
return i.longValue();
```

`jdbcCompliantTruncationForReads` 由连接参数 `jdbcCompliantTruncation`（默认 `true`）决定。
→ **默认行为是抛异常（响亮失败）；一旦有人关掉就变成静默回绕成负数。**

**v1 规则**：
1. 显式设 `jdbcCompliantTruncation=true`，禁止用户覆盖。
2. 前端对 `BIGINT UNSIGNED` 列显式告警，PG 落点建议 `numeric(20,0)`。
3. 若想彻底规避，可在 Source 的 `query` 模式里对该列写 `CAST(col AS CHAR)`，
   走 STRING → PG `numeric`（Sink 会以 `setString` 绑定，PG 侧隐式转换）。

### 3.4 日期时间的时区语义

**三层时区，缺一层就整体平移：**

1. **MySQL 侧语义**（手册 13.2.2）：`TIMESTAMP` 写入时从 session `time_zone` 转成 UTC 存储、
   读取时转回 session `time_zone`；`DATETIME`/`DATE`/`TIME` **原样存取，无任何时区转换**。
2. **Connector/J 侧**（[datetime 属性文档](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)）：
   - `connectionTimeZone`（默认 `LOCAL`，8.0 起取代旧名 `serverTimezone`）——
     Connector/J 用于「JVM 默认时区 ↔ 连接时区」换算的目标时区。可填地理名、UTC 偏移、
     或逻辑值 `LOCAL`/`SERVER`。**它默认不会去改服务器 session 的 `time_zone`**。
   - `forceConnectionTimeZoneToSession`（默认 `false`，8.0.23+）—— 置 true 才会把
     `connectionTimeZone` 写进 session `time_zone`，从而消除中间换算。
   - `preserveInstants`（默认 `true`，8.0.23+）—— 对 `java.sql.Timestamp` 这类
     instant 对象保留时间线上的瞬时（做时区换算）而非保留「视觉形状」；
     `connectionTimeZone=LOCAL` 时无效。
3. **Connect 侧**：`db.timezone`（`JdbcSourceConnectorConfig.DB_TIMEZONE_CONFIG`，默认 `"UTC"`）
   被 `GenericDatabaseDialect` 转成 `zoneId`，用作
   `rs.getTimestamp(col, DateTimeUtils.getZoneIdCalendar(zoneId))` 和
   `rs.getTime(col, ...)` 的日历。`DATE` 另用 `dateTimeZoneId`，Source 端**硬编码为
   `ZoneOffset.UTC`**（源码注释："dateTimeZone is used for handling DATE conversion and
   should be equal to UTC for source"）。

**致命点：`DATETIME` 和 `TIMESTAMP` 在 Connect 层是同一个类型**
（都是 `Types.TIMESTAMP` → `org.apache.kafka.connect.data.Timestamp` → epoch millis）。
下游无法区分「本地墙钟时间」和「绝对瞬时」，必须靠 DBX 自己从 `information_schema` 记住原类型。

**v1 推荐基线（全链路 UTC，唯一自洽的选择）：**

| 环节 | 设定 |
|---|---|
| MySQL 连接串 | `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true` |
| JDBC Source | `db.timezone=UTC` |
| Connect worker JVM | `-Duser.timezone=UTC`（消除 JVM 默认时区带来的隐式换算） |
| PG 会话 | `TimeZone=UTC` |

**PG 落点选择：**

- MySQL `TIMESTAMP` → PG **`timestamptz`**。两者语义同构（内部 UTC，按会话时区呈现），
  链路传的 epoch millis 天然对齐；PG 15 手册 8.5.1.3。
- MySQL `DATETIME` → PG **`timestamp without time zone`**。语义同构（无时区墙钟）。
  但**必须保证 Source 用 `db.timezone=UTC` 且 Sink 写入时也按 UTC 解释**，
  否则墙钟值会平移。Sink 侧 `GenericDatabaseDialect.maybeBindLogical()` 对 Connect
  `Timestamp` 用 `statement.setTimestamp(index, sqlTimestamp, DateTimeUtils.getZoneIdCalendar(zoneId))`，
  `zoneId` 来自 Sink 的 `db.timezone`（默认 `UTC`）；`Date` 逻辑类型另用
  `dateTimeZoneId`（Sink 的 `db.timezone.date`）。
  → **Source 与 Sink 的 `db.timezone`（及 `db.timezone.date`）必须一致，全部设 UTC。**
- MySQL `DATE` → PG `date`；MySQL `TIME` → PG `time`（注意 §2.3 的值域告警）；
  MySQL `YEAR` → PG `date`（默认）或改 `yearIsDateType=false` 后仍是 `Date` 逻辑类型
  （因为 `MysqlType.YEAR` 固定报 `Types.DATE`）→ 想要 `smallint` 必须走 `query` 模式 `CAST`。

### 3.5 utf8mb4 下的 `VARCHAR(n)` 长度语义

- MySQL `VARCHAR(M)`：M 计**字符**，行内最大 65,535 字节 → utf8mb4 下 M 实际上限
  约 16,383（手册 11.3.2）。`information_schema.columns` 里
  `character_maximum_length` 是字符数、`character_octet_length` 是字节数。
- PG 15 `varchar(n)` / `char(n)`：n 计**字符**，与数据库编码无关（手册 8.3）；
  `text` 无长度限制，超长不截断。
- **结论：`VARCHAR(n) → varchar(n)` 一一对应，utf8mb4 不产生截断风险。**
  DDL 生成器请用 `character_maximum_length`，**不要**用 `character_octet_length`
  （那会把 `VARCHAR(255)` 建成 `varchar(1020)`，虽不出错但审核界面失真）。
- 例外：MySQL 的 utf8mb4 可存 4 字节的补充平面字符（emoji），PG 的 UTF8 编码同样支持
  → 无字符集损失。若源库用 `utf8`（即 utf8mb3），迁到 PG UTF8 是**放宽**，安全。
- 注意 PG `varchar(n)` 超长直接报错 `22001 value too long`（不像 MySQL 非严格模式会截断）
  → 若源库运行在非严格 SQL 模式且存在超长历史数据，建议目标端一律用 `text`。

### 3.6 `JSON` / `ENUM` / `SET` / `BIT(n)` / `GEOMETRY` / 生成列

| 类型 | 支持情况 | 不支持时的表现 / 处置 |
|---|---|---|
| `JSON` | ✅ 支持，降级为字符串。`MysqlType.JSON` 声明 `Types.LONGVARCHAR`、`String.class` → Connect `STRING` → Avro `string` | PG 落 `jsonb`：`PostgreSqlDatabaseDialect.valueTypeCast()` 读已存在表的列类型，对 `json`/`jsonb`/`uuid` 渲染 `?::jsonb`（#4 结论 8），所以 **STRING 能写进我们自建的 jsonb 列**。若源里有非法 JSON（理论上不可能，MySQL 写入时已校验）则 PG 报 `22P02` |
| `ENUM` | ⚠️ 部分支持。`Types.CHAR` → STRING，Avro 是 `string` **不是 Avro enum** | 取值集合丢失。落 `text` + `CHECK (col IN (...))`（集合从 `information_schema.columns.column_type` 解析）。**不要落 PG `enum` 类型**：Sink 只有 json/jsonb/uuid 的 cast 白名单，自定义 enum 会因 `?` 参数类型不匹配报 `42804` |
| `SET` | ⚠️ 部分支持。`Types.CHAR` → STRING，值是逗号分隔串 | 落 `text`。若需结构化，需在 PG 侧后置 `string_to_array()`，不在链路内做 |
| `BIT(1)` | ⚠️ 变成 INT8 而非 boolean | 落 `smallint`，或应用侧再转 |
| `BIT(2..8)` | ⚠️ 危险。INT8 + `(byte)` 截断 → 值 128–255 变负数 | 建议**前端标红**，或用 `query` 模式 `CAST(col AS UNSIGNED)` 绕开 |
| `BIT(n>8)` | ❌ 不支持。`ByteValueFactory.createFromBit()` 抛 `NumberOutOfRange`（默认配置） | 前端硬拦；绕行方案：`query` 模式 `HEX(col)` → STRING → PG `bit varying`/`text` |
| `GEOMETRY`/`POINT`/… | ❌ 不支持。`Types.BINARY` → BYTES，内容为 4 字节 SRID + WKB | 前端硬拦；绕行：`ST_AsText(col)` → STRING → PostGIS `ST_GeomFromText()` |
| 生成列（VIRTUAL/STORED） | ✅ 读取正常，与普通列无区别 | **PG 侧必须建成普通列**。若建成 `GENERATED ALWAYS AS (...) STORED`，Sink 的 INSERT 显式给值会报 `428C9 cannot insert a non-DEFAULT value into column`。或在映射里排除该列 |
| `AUTO_INCREMENT` | ✅ 值正常搬运，但属性不在 schema 里 | PG 用普通 `bigint` 或 `GENERATED BY DEFAULT AS IDENTITY`；迁移后 `SELECT setval(...)` 对齐 |
| `Types.ARRAY/JAVA_OBJECT/OTHER/STRUCT/REF/ROWID/NULL` | ❌ `addFieldToSchema()` 走 `default:` → WARN + **静默丢列** | MySQL 8.0 里正常建表不会产生这些类型，但**前端仍须白名单校验**，因为丢列不报错 |

---

## 4. 连接参数清单（平台必须显式管理）

以下参数**都会改变类型映射结果或数据正确性**，DBX 必须写死在生成的连接串/connector 配置里，
不能依赖驱动默认值（默认值会随 Connector/J 版本变化）。

### 4.1 Connector/J（写在 `connection.url` 或 `connection.*` 里）

| 参数 | 默认 | v1 建议 | 为什么必须管 |
|---|---|---|---|
| `tinyInt1isBit` | `true` | **`false`** | 决定 `TINYINT(1)` 走 `Types.BIT`(→INT8) 还是 `Types.TINYINT`(→INT8)；与下条组合决定是否变 boolean |
| `transformedBitIsBoolean` | `false` | `false`（除非整库要 boolean） | `true` 时 `TINYINT(1)` → `Types.BOOLEAN` → Connect `BOOLEAN`，Avro schema 完全不同 |
| `jdbcCompliantTruncation` | `true` | **`true`（禁止改）** | 决定 `BIGINT UNSIGNED`/`BIT(n)` 越界是抛 `NumberOutOfRange` 还是静默回绕 |
| `connectionTimeZone` | `LOCAL` | **`UTC`** | 8.0 起取代 `serverTimezone`。决定 `TIMESTAMP` 的换算基准 |
| `forceConnectionTimeZoneToSession` | `false` | **`true`** | 把 `connectionTimeZone` 写进 session `time_zone`，消除中间换算 |
| `preserveInstants` | `true` | `true` | 保证 `java.sql.Timestamp` 保留瞬时而非视觉形状 |
| `yearIsDateType` | `true` | `true` | 影响 `YEAR` 的取值类；注意 JDBC 类型恒为 `Types.DATE`，故 Connect schema 不变 |
| `zeroDateTimeBehavior` | `EXCEPTION` | **`CONVERT_TO_NULL` 或 `EXCEPTION`（显式选）** | MySQL 允许 `0000-00-00`，PG 不允许。`EXCEPTION`=响亮失败，`CONVERT_TO_NULL`=转 NULL（需目标列可空）。**绝不能不设** |
| `characterEncoding` | 由服务器协商 | `UTF-8` | 决定字符串解码；错了就是乱码（静默失败） |
| `useUnicode` | `true` | `true` | 同上 |
| `noDatetimeStringSync` | `false` | `false` | 保持 `getTimestamp().toString()` 与 `getString()` 一致 |
| `sendFractionalSeconds` / `sendFractionalSecondsForTime` | `true` | `true` | 仅影响写入方向（Sink 用 PG 驱动，故对 v1 无影响），列出以防未来反向迁移 |
| `treatMysqlDatetimeAsTimestamp` | `false`（8.2.0+） | 不设 | 只影响 `getObject()` 返回类，链路用 `getTimestamp()`，无影响 |
| `useCursorFetch` / `defaultFetchSize` | `false` / 0 | 按性能票决定 | 不改类型，但影响大表 OOM |
| `functionsNeverReturnBlobs` / `blobsAreStrings` | `false` | `false` | 会把 BLOB 变 String，破坏 BYTES 映射 |

### 4.2 JDBC Source connector 配置

| 配置 | 默认 | v1 建议 | 影响 |
|---|---|---|---|
| `numeric.mapping` | `none` | `none`（显式写出） | 对 MySQL 无效（§3.1），显式写 `none` 以防未来换驱动/换源库时行为漂移 |
| `db.timezone` | `UTC` | `UTC`（显式写出） | `Types.TIMESTAMP`/`TIME` 的读取日历 |
| `timestamp.granularity` | `connect_logical` | `connect_logical`（若不需微秒）/ `micros_long`（需微秒） | 直接改变 `Types.TIMESTAMP` 的 Connect schema：`Timestamp` 逻辑类型 vs `INT64` vs `STRING` |
| `dialect.name` | 自动 | `MySqlDatabaseDialect` | 避免方言探测漂移（虽然 Source 行为由 Generic 决定） |
| `quote.sql.identifiers` | `always` | `always` | 列名原样引用 |

### 4.3 AvroConverter / Schema Registry

| 配置 | 默认 | 说明 |
|---|---|---|
| `value.converter.schemas.enable` | — | AvroConverter 不用此项 |
| `enhanced.avro.schema.support` | `false` | 影响 Avro 名字合法化与 `connect.name` 的往返保真 |
| `connect.meta.data` | `true` | 是否把 `connect.name`/`connect.parameters` 写进 Avro schema；**保持 `true`**，否则 `connect.decimal.precision` 丢失 |
| Subject 兼容性策略 | `BACKWARD` | 源端 DDL 变更会导致 schema 演进被拒 —— 属于另一张票的范围 |

---

## 5. 待决策清单（交给「类型映射矩阵定稿」票）

1. `TINYINT(1)`：全局 `tinyInt1isBit=false`（安全，PG 落 `smallint`）
   vs 全局 `transformedBitIsBoolean=true`（好看，但值 >1 会静默变 `true`）。**必须二选一，不能混。**
2. `DATETIME(6)`/`TIMESTAMP(6)`：接受毫秒截断（`connect_logical`）
   vs 切 `timestamp.granularity=micros_long`（Connect INT64，PG 侧需 DDL 落 `bigint` 或加转换 SMT）。
3. `BIGINT UNSIGNED` 的处置：告警放行（越界时任务红）vs 强制 `query` 模式 `CAST(... AS CHAR)`。
4. `TIME`：直落 PG `time`（超 24 小时的值会失败）vs 落 `interval`。
5. `ENUM`/`SET`：`text` + `CHECK` vs 裸 `text`。
6. `VARCHAR(n)`：保留 `varchar(n)`（审核界面直观、但超长数据会报 `22001`）vs 一律 `text`（更宽松）。
7. `JSON` → `jsonb`（key 重排、去重、丢空白）vs `json`/`text`（字节保真）。
8. `BIT(n>1)`、`GEOMETRY`、`VECTOR`：v1 直接标记不支持，前端硬拦。
