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
   无条件 INT64）→ 值 > 2^63-1 时静默溢出成负数。**这是 v1 必须拦截的头号安静失败。**
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

`MySqlDatabaseDialect` 只覆写了 `buildAuthenticationProperties`、
`initializePreparedStatement`、`getSqlType(SinkRecordField)`、`buildUpsertQueryStatement`、
`sanitizedUrl`、`resolveSynonym` —— 全部是 **Sink 侧**能力，Source 侧行为 100% 由
`GenericDatabaseDialect` 决定。
