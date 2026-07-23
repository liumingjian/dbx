# JDBC Sink 在 `auto.create=false` 下的写入契约

> 研究票：[liumingjian/dbx#4](https://github.com/liumingjian/dbx/issues/4)（父票 #1）
> 一手来源：`confluentinc/kafka-connect-jdbc` **tag `v10.9.6`**（撰写时最新 release tag）+ Confluent 官方文档。
> 源码链接统一形如 `https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/...`

## 结论摘要

1. **Sink 只靠列名匹配，不做任何类型校验。** `DbStructure.amendIfNecessary()` 的注释白纸黑字写着"We also don't check if the data types for columns that do line-up are compatible"，且主键一致性检查是被注释掉的死代码（`// FIXME: SQLite JDBC driver...`）。因此**类型/主键的正确性 100% 由我们自己生成的 DDL 负责**，Sink 不会给我们兜底。
2. **列名匹配存在一个致命的"半大小写不敏感"陷阱**：缺列检测（`DbStructure.missingFields()`）是**大小写不敏感**的，但真正生成 SQL 时（`quote.sql.identifiers` 默认 `always`）列名被双引号原样引用，即**大小写敏感**。后果：表列叫 `ID`、记录字段叫 `id` 时，缺列检查放行，但 `PreparedStatement` 预编译时 PG 报 `column "id" of relation "t" does not exist`。**我们的建表必须让 PG 中的实际列名与 Connect Schema 字段名逐字节相同**（在 PG 上意味着：要么全小写不加引号，要么加引号并与源字段名完全一致）。
3. **表可以没有主键**：`insert.mode=insert` + `pk.mode=none` 是合法组合，`keyFieldNames` 为空，生成纯 `INSERT INTO t (cols) VALUES (?)`。**这正是 v1 全量迁移应当采用的配置**。`upsert` 才强制要求 key（否则 `ConnectException: ... requires key field names to be known`），且 PG 的 `ON CONFLICT (...)` 要求这些列上有唯一约束。
4. **失败时机几乎都在"首批写入"而非"connector 启动"**：`JdbcSinkTask.start()` 只构造 config/dialect/writer，不碰数据库结构。表不存在、缺列、列名不匹配全部在**该 topic 第一条记录进入 `BufferedRecords.add()` 时**抛出。类型不兼容更晚——在 `executeBatch()` 时由 PG 抛出。
5. **一条坏行会毒掉整个 batch**：`BufferedRecords.executeUpdates()` 检查 `executeBatch()` 返回值，任一 `EXECUTE_FAILED` 即抛 `BatchUpdateException`；`JdbcDbWriter.write()` 随后 `rollback()` 整个事务（含该 batch 内**所有表**）。要把失败定位到行，必须依赖 `errors.tolerance=all` + DLQ 触发的 `JdbcSinkTask.unrollAndRetry()` 逐条重放。
6. **`auto.create=true` 的建表能力比我们弱**：无 `VARCHAR(n)`（STRING 一律 `TEXT`）、无 `NUMERIC(p,s)`（Decimal 一律无精度的 `DECIMAL`）、无 `TIMESTAMPTZ`/`JSONB`/`UUID`、且**永远不会同时产出 `DEFAULT` 和 `NOT NULL`**。它是我们映射矩阵的保守下界，不是目标。
7. **PG 方言有一个我们可以主动利用的能力**：`PostgreSqlDatabaseDialect.valueTypeCast()` 会读取**目标表已有列的类型**，对 `json` / `jsonb` / `uuid` 三种类型自动把占位符写成 `?::jsonb`。也就是说**我们建 `JSONB` / `UUID` 列，Sink 用 STRING 字段能正常写入**——尽管 `auto.create` 自己建不出这些类型。

---

## 一、目标表结构必须满足的硬性条件清单

### 1.1 列名匹配规则

| 规则 | 依据 |
| --- | --- |
| Sink 用 **Connect Schema 的字段名**（`Field.name()`）当列名，无任何大小写/命名风格转换 | `FieldsMetadata.extract()`，`allFields.put(field.name(), ...)` |
| 缺列检测大小写**不敏感**（先精确匹配，失败后回退到 `toLowerCase()` 比对） | `DbStructure.missingFields()`：`columnNamesLowerCase.contains(missing.name().toLowerCase())` |
| 生成 DML 时列名**大小写敏感**：`quote.sql.identifiers` 默认 `ALWAYS`，PG `IdentifierRules(".", "\"", "\"")` → 列名被 `"` 包裹原样输出 | `JdbcSourceConnectorConfig.QUOTE_SQL_IDENTIFIERS_DEFAULT = QuoteMethod.ALWAYS`；`PostgreSqlDatabaseDialect` 构造函数 |
| **硬性条件**：PG 表中列的真实名字（`pg_attribute.attname`）必须与 Connect 字段名**逐字符相等** | 上两条的交集 |
| **多余列**（表里有、记录里没有）：允许，Sink 完全不管它——但该列必须 **NULLABLE 或有 DEFAULT**，否则 `INSERT` 触发 PG `23502 not_null_violation` | `DbStructure.amendIfNecessary()` 注释："The table might have extra columns defined (hopefully with default values), which is not a case we check for here" |
| **缺列**（记录里有、表里没有）：`auto.evolve=false` → `TableAlterOrCreateException` | `DbStructure.amendIfNecessary()` |

> **对 DBX 的直接含义**：MySQL 列名 `UserName` 若在 PG 建成不加引号的 `username`，而上游 Source 产出的字段名是 `UserName`，则缺列检查放行、写入必炸。DDL 生成器要么统一"源列名原样 + 双引号"，要么在 Source 侧统一改名（SMT `ReplaceField`），二者必须与 DDL 一致。

### 1.2 类型兼容判据

**Sink 不做类型校验**（`DbStructure` 注释明示）。真正的判据是：`PreparedStatementBinder` 用哪个 JDBC setter 绑定，pgjdbc 就发送哪个 PG 类型 OID，PG 必须能把它隐式赋值给目标列。绑定规则（`GenericDatabaseDialect.bindFieldInternal` → `maybeBindLogical` → `maybeBindPrimitive`，PG 方言额外覆写 `maybeBindPrimitive` 处理 ARRAY）：

| Connect Schema | JDBC 调用 | 安全的 PG 目标列类型 |
| --- | --- | --- |
| `null`（任意 schema） | `setObject(i, null)`（PG 方言未覆写 `getSqlTypeForSchema`，返回 null） | 列必须 NULLABLE |
| `INT8` | `setByte` | `smallint` / `int` / `bigint` / `numeric` |
| `INT16` | `setShort` | `smallint` 及更宽 |
| `INT32` | `setInt` | `int` 及更宽 |
| `INT64` | `setLong` | `bigint` / `numeric` |
| `FLOAT32` | `setFloat` | `real` / `double precision` |
| `FLOAT64` | `setDouble` | `double precision` |
| `BOOLEAN` | `setBoolean` | `boolean` |
| `STRING` | `setString` | `text` / `varchar(n)` / `char(n)`；若目标列是 `json`/`jsonb`/`uuid`，方言自动加 `::type` 转换（见 1.5） |
| `BYTES` | `setBytes` | `bytea` |
| `Decimal`（逻辑类型） | `setBigDecimal` | `numeric(p,s)` / `numeric` |
| `Date` | `setDate(..., Calendar(date.timezone))` | `date` |
| `Time` | `setTime(..., Calendar(db.timezone))` | `time` |
| `Timestamp` | `setTimestamp(..., Calendar(db.timezone))` | `timestamp`（推荐）；`timestamptz` 可写但语义受 session TimeZone 影响 |
| `ARRAY<primitive>` | `setObject(i, T[], Types.ARRAY)` | `T[]` |
| `STRUCT` / `MAP` | 无分支 → `ConnectException: Unsupported source data type: STRUCT` | **不支持**，必须在 SMT 里拍平 |

判据可以写成一句话：**列类型 T 兼容 ⇔ PG 允许把上表对应的 JDBC 类型隐式赋值给 T**。`varchar(n)` 长度不足属于**运行时**失败（`22001 string_data_right_truncation`），不属于结构失败——这条要单独进错误翻译层。

### 1.3 可空性

- Sink **不检查**目标列的 NULL 约束。
- `replace.null.with.default` 默认 **`true`**：`PreparedStatementBinder.bindNonKeyFields()` 用 `valueStruct.get(field)`（会返回 schema default）而非 `getWithoutDefault()`。所以"字段值为 null 但 schema 有 default"时写入的是 default，不是 NULL。这会掩盖一部分 NOT NULL 冲突，但**不能依赖**。
- **硬性条件**：目标列为 `NOT NULL` ⇔ 该字段在所有记录中都非 null（或有 schema default 且 `replace.null.with.default=true`）。保守做法：v1 建表时对源库 nullable 列**一律建成 NULLABLE**，NOT NULL 仅保留在主键列上。

### 1.4 主键要求

| `insert.mode` | 对目标表主键的要求 | 依据 |
| --- | --- | --- |
| `insert`（默认） | **无要求**。`pk.mode=none` 时 `keyFieldNames` 为空，SQL 为普通 `INSERT INTO t (...) VALUES (...)` | `BufferedRecords.getInsertSql()` / `PostgreSqlDatabaseDialect.buildInsertStatement()` |
| `upsert` | `keyFieldNames` 非空，否则 `ConnectException: Write to table '%s' in UPSERT mode requires key field names to be known, check the primary key configuration`。生成 `INSERT ... ON CONFLICT ("k") DO UPDATE SET ...` → **PG 要求这些列上存在唯一索引/主键约束**，否则 `42P10 invalid_column_reference` | `BufferedRecords.getInsertSql()`；`PostgreSqlDatabaseDialect.buildUpsertQueryStatement()` |
| `update` | 生成 `UPDATE t SET ... WHERE "k" = ?`。key 为空时 `WHERE` 子句被整体省略 → **会更新全表**，属于静默危险行为 | `PostgreSqlDatabaseDialect.buildUpdateStatement()`：`if (!keyColumns.isEmpty())` |
| `delete.enabled=true` | 强制 `pk.mode=record_key`，否则 `ConnectException: Deletes are only supported for pk.mode record_key` | `BufferedRecords.getDeleteSql()` |

**`pk.mode` × `pk.fields` 语义**（`FieldsMetadata.extract()`）：

| `pk.mode` | `pk.fields` 语义 | 失败条件与文案 |
| --- | --- | --- |
| `none` | 忽略 | 无 key 列 |
| `kafka` | 空 → 默认三列 `__connect_topic`(STRING)/`__connect_partition`(INT32)/`__connect_offset`(INT64)；否则必须**恰好 3 个**，按顺序重命名这三列 | `PK mode for table '%s' is KAFKA so there should either be no field names defined for defaults %s to be applicable, or exactly 3, defined fields are: %s` |
| `record_key` | key 为原始类型 → `pk.fields` 必须**恰好 1 个**；key 为 Struct → 空表示取全部 key 字段，否则取指定子集 | `Need exactly one PK column defined since the key schema for records is a primitive type, defined columns are: %s`；`PK mode for table '%s' is RECORD_KEY with configured PK fields %s, but record key schema does not contain field: %s`；`Key schema must be primitive type or Struct, but is of type: %s`；`PK mode for table '%s' is RECORD_KEY, but record key schema is missing` |
| `record_value` | 空 → **value 的全部字段都变成 PK**（几乎肯定不是你想要的）；否则取指定子集 | `PK mode for table '%s' is RECORD_VALUE with configured PK fields %s, but record value schema does not contain field: %s` |

> **DBX v1 建议**：全量迁移用 `insert.mode=insert` + `pk.mode=none`。目标表**可以**（也应该）有主键——那是我们自己 DDL 的事，与 Sink 契约无关；但要注意此时唯一键冲突会以 PG `23505` 形式炸掉整批。

### 1.5 PG 特有类型的写入行为

- **`BYTEA`**：`BYTES` → `setBytes`，直通。`ByteBuffer` 会被 `slice()` 后取 `remaining()` 字节——**position 之前的数据会丢**，这是 Converter 侧要注意的坑。
- **`TEXT` / `VARCHAR(n)`**：都接受 `setString`。`auto.create` 只会产出 `TEXT`。
- **`NUMERIC(p,s)`**：`Decimal` → `setBigDecimal`，标度不匹配由 PG 处理（超标度会被舍入，超精度报 `22003 numeric_value_out_of_range`）。
- **`TIMESTAMP` / `TIMESTAMPTZ`**：`Timestamp` 逻辑类型 → `setTimestamp(i, ts, Calendar(db.timezone))`，`db.timezone` 默认 `UTC`。写 `timestamp without time zone` 语义确定；写 `timestamptz` 时值会按 session 时区再解释一次，**建议 v1 统一映射到 `timestamp`（无时区）并显式设置 `db.timezone=UTC`**。
- **`BOOLEAN`**：`setBoolean`，直通。
- **`JSONB` / `JSON` / `UUID`**：`getSqlType()` **建不出**这些类型，但 `valueTypeCast(TableDefinition, ColumnId)` 会读取**已存在表**的列类型，命中 `CAST_TYPES = {json, jsonb, uuid}` 时把占位符渲染成 `?::jsonb`。所以只要我们自己建 `JSONB` 列，STRING 字段就能写进去。**这条依赖 `TableDefinition` 非 null**——`auto.create=false` 且表已存在时它总是非 null（`DbStructure.tableDefinition()`）。
- **`ARRAY`**：PG 方言覆写 `maybeBindPrimitive`，把 `Collection`/Java 数组转成 `Short[]/Integer[]/Long[]/Float[]/Double[]/Boolean[]/String[]` 后 `setObject(..., Types.ARRAY)`。非上述元素类型 → `DataException: Type '%s' is not supported for Array.`

---

## 二、失败形态目录（错误翻译层素材）

先记住调用链：`JdbcSinkTask.put()` → `JdbcDbWriter.write()`（按 `TableId` 分组，每表一个 `BufferedRecords`）→ `BufferedRecords.add()`（schema 变化时做结构检查 + 预编译 SQL）→ `flush()` → `bindRecord()` → `executeUpdates()`。

| # | 触发条件 | 时机 | 异常类 | 错误文本（格式串原文） |
| --- | --- | --- | --- | --- |
| F1 | 目标表不存在 | 该表**第一条记录**进入 `add()` 时（`DbStructure.create()`） | `TableAlterOrCreateException` | `Table %s is missing and auto-creation is disabled` |
| F2 | 表缺少记录中的字段，`auto.evolve=false` | 同上（`amendIfNecessary()`） | `TableAlterOrCreateException` | `Table %s is missing fields (%s) and auto-evolution is disabled` |
| F3 | 目标是 VIEW 且缺字段 | 同上 | `TableAlterOrCreateException` | `View %s is missing fields (%s) and ALTER VIEW is unsupported` |
| F4 | 列名仅大小写不同（如表 `ID` vs 字段 `id`） | **预编译 SQL 时**（`dbDialect.createPreparedStatement`），仍在首条记录 | `org.postgresql.util.PSQLException` → 包装为 `RetriableException`/`ConnectException` | PG 原文：`ERROR: column "id" of relation "t" does not exist`，SQLState `42703`。日志中会先出现 `Unable to find fields ... among column names ...`（INFO）或 `Table has column names that differ only by case`（WARN） |
| F5 | 类型不兼容（如 STRING → `integer` 列） | **`executeBatch()` 时**，即首次达到 `batch.size` 或 `flush()` | `PSQLException` 包在 `BatchUpdateException` 里 | `ERROR: column "x" is of type integer but expression is of type character varying`（`42804`）；若 pgjdbc 以 `stringtype=unspecified` 连接则表现为 `invalid input syntax for type integer: "..."`（`22P02`） |
| F6 | 目标列 NOT NULL 而值为 null | `executeBatch()` | 同 F5 | `ERROR: null value in column "x" of relation "t" violates not-null constraint`（`23502`） |
| F7 | `varchar(n)` 长度不足 | `executeBatch()` | 同 F5 | `ERROR: value too long for type character varying(n)`（`22001`） |
| F8 | 主键/唯一键冲突（重跑、重复消费） | `executeBatch()` | 同 F5 | `ERROR: duplicate key value violates unique constraint "..."`（`23505`） |
| F9 | `upsert` 但 key 未知 | 首条记录（`getInsertSql()`） | `ConnectException` | `Write to table '%s' in UPSERT mode requires key field names to be known, check the primary key configuration` |
| F10 | `upsert` 的 `ON CONFLICT` 列无唯一约束 | 预编译 SQL 时 | `PSQLException` | `ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification`（`42P10`） |
| F11 | 记录含 STRUCT/MAP 字段 | 绑定时（`flush()`） | `ConnectException` | `Unsupported source data type: STRUCT` |
| F12 | tombstone / 非 Struct value 且 `delete.enabled=false` | `add()` 入口（`RecordValidator`） | `ConnectException` | `Sink connector '%s' is configured with 'delete.enabled=%s' and 'pk.mode=%s' and therefore requires records with a non-null Struct or String value ... but found record at (topic=...,partition=...,offset=...,timestamp=...) with a %s value and %s value schema.` ← **唯一自带 topic/partition/offset 坐标的错误** |
| F13 | `table.name.format` 渲染为空 | `destinationTable()` | `ConnectException` | `Destination table name for topic '%s' is empty using the format string '%s'` |
| F14 | key/value schema 都没有字段 | 首条记录 | `ConnectException` | `No fields found using key and value schemas for table: %s` |

### 2.1 batch / retry / DLQ 的行为

- **`batch.size`（默认 3000）**：`BufferedRecords.add()` 中 `records.size() >= config.batchSize` 才 flush。**注意它同时是"结构错误被发现的延迟上限"** —— F5~F8 这类错误最晚要攒够 3000 条才暴露。**建议冒烟阶段把 `batch.size` 调到 1**，让类型不兼容在第一行就炸。
- **原子性**：`JdbcDbWriter.write()` 在 `onConnect` 里 `setAutoCommit(false)`，任何异常都 `connection.rollback()`。一次 `put()` 里**所有表**的写入共享一个事务——一张表失败，同 batch 内其它表的写入也会回滚。
- **`max.retries`（默认 10）/ `retry.backoff.ms`（默认 3000）**：`JdbcSinkTask.put()` 捕获 `SQLException` 后重建 writer 并抛 `RetriableException`，由 Connect 框架重投**整批**。结构性错误（F4~F8）重试必然再失败，**结果是 10 × 3s ≈ 30 秒的无谓延迟后任务才 FAILED**。DBX 应把 `max.retries` 调小（如 1~3），让结构错误快速失败。
- **`errors.tolerance` / DLQ**：`JdbcSinkTask.start()` 取 `context.errantRecordReporter()`。
  - `reporter == null`（`errors.tolerance=none`，或 Connect < 2.6）→ 重试耗尽后 `throw new ConnectException(sqlAllMessagesException)`，**任务 FAILED**。异常消息是 `getAllMessagesException()` 拼出的 `"Exception chain:\n" + 每个 SQLException 的 toString()`。
  - `reporter != null`（`errors.tolerance=all` + DLQ）→ 走 `unrollAndRetry()`：**逐条**重新 `writer.write(singletonList(record))`，失败的那条被 `reporter.report(record, e)` 送进 DLQ，其余继续。**这是唯一能把失败定位到"哪一行"的机制**，代价是错误批次退化为逐行写入。
- **对"失败要能定位到表"的影响**：`TableAlterOrCreateException`（F1~F3）文本里**自带 `TableId`**，可直接解析出表名。PG 的 `PSQLException`（F4~F8）**只有 PG 自己的 `relation "..."` / `column "..."` 措辞**，没有 connector 层的表名——错误翻译层需要（a）解析 PG 错误文本里的 `relation`/`column`/`constraint`，或（b）依赖"一个 connector 只对一张表"的部署形态（每表一个 connector）来消除歧义。**推荐 (b)：v1 一表一 connector，用 connector name 反查表，最省事也最可靠。**

---

## 三、`auto.create=true` 会建成什么（保守下界对照）

来源：`DbStructure.create()` → `GenericDatabaseDialect.buildCreateTableStatement()` → `writeColumnsSpec()` / `writeColumnSpec()`，类型由 `PostgreSqlDatabaseDialect.getSqlType(SinkRecordField)` 决定。

### 3.1 语句骨架

```sql
CREATE TABLE "schema"."table" (
"col1" <TYPE> [DEFAULT <literal> | NULL | NOT NULL],
"col2" <TYPE> ...,
PRIMARY KEY("pk1","pk2"))
```

`writeColumnSpec()` 的三分支是**互斥**的：
1. `f.defaultValue() != null` → 只写 `DEFAULT <literal>`；
2. 否则 `isColumnOptional(f)` → 写 ` NULL`；
3. 否则 → 写 ` NOT NULL`。

**推论：`auto.create` 永远不会同时输出 `DEFAULT` 和 `NOT NULL`。** 一个"非可选且有默认值"的字段会被建成**可空**列。这是它比我们弱的地方之一——我们的 DDL 可以（也应当）同时保留两者。

### 3.2 Connect Schema → PG DDL 类型完整对照表

先看**逻辑类型**（`field.schemaName()`，优先于物理类型）：

| Connect 逻辑类型（schema name） | `auto.create` 产出的 PG 类型 |
| --- | --- |
| `org.apache.kafka.connect.data.Decimal` | `DECIMAL` ← **无精度无标度**（PG 中等价于任意精度 `numeric`） |
| `org.apache.kafka.connect.data.Date` | `DATE` |
| `org.apache.kafka.connect.data.Time` | `TIME`（无时区） |
| `org.apache.kafka.connect.data.Timestamp` | `TIMESTAMP`（**无时区**） |
| 其它 schema name（如 `io.debezium.*`） | 落到下表物理类型分支 |

再看**物理类型**（`field.schemaType()`）：

| Connect `Schema.Type` | `auto.create` 产出的 PG 类型 |
| --- | --- |
| `INT8` | `SMALLINT` |
| `INT16` | `SMALLINT` |
| `INT32` | `INT` |
| `INT64` | `BIGINT`（若字段名在 `timestamp.fields.list` 中 → `TIMESTAMP`） |
| `FLOAT32` | `REAL` |
| `FLOAT64` | `DOUBLE PRECISION` |
| `BOOLEAN` | `BOOLEAN` |
| `STRING` | `TEXT`（若字段名在 `timestamp.fields.list` 中 → `TIMESTAMP`） |
| `BYTES`（非 Decimal） | `BYTEA` |
| `ARRAY` | `<元素类型>[]`（递归调用 `getSqlType`） |
| `STRUCT` / `MAP` | 落到 `GenericDatabaseDialect.getSqlType()` → `ConnectException: %s (%s) type doesn't have a mapping to the SQL database column type` |

### 3.3 与 DBX 自研映射矩阵的差距（我们必须做得更好的点）

| 维度 | `auto.create` | DBX 应做到 |
| --- | --- | --- |
| 字符串长度 | 一律 `TEXT` | `VARCHAR(n)` 保留 MySQL 长度语义（注意 MySQL 按字符、PG 按字符，`utf8mb4` 下无需 ×N） |
| 定点数 | 无精度 `DECIMAL` | `NUMERIC(p,s)` 精确还原 `DECIMAL(p,s)` |
| 时间 | 只有 `TIMESTAMP`，无 `TIMESTAMPTZ` | 依据 MySQL `TIMESTAMP`（有时区语义）vs `DATETIME`（无）分别映射 |
| 半结构化 | 建不出 `JSON`/`JSONB` | MySQL `JSON` → PG `JSONB`（Sink 侧靠 `valueTypeCast` 自动 `?::jsonb`，可写） |
| 无符号整数 | 无概念（`INT32`→`INT` 会溢出） | `INT UNSIGNED` → `BIGINT`，`BIGINT UNSIGNED` → `NUMERIC(20,0)` |
| 约束 | 只有 PK；`DEFAULT` 与 `NOT NULL` 互斥 | PK + NOT NULL + DEFAULT 可并存；唯一/外键/索引另行决策 |
| `TINYINT(1)` | 取决于 Source 产出 `INT8` → `SMALLINT` | 应映射 `BOOLEAN`（需与 Source 的 `TINYINT(1)` 处理一致） |

**兼容性红线**：DBX 生成的每一列类型，都必须落在 §1.2 "安全的 PG 目标列类型" 一栏内。凡是比 `auto.create` 更"窄"的类型（`VARCHAR(n)`、`NUMERIC(p,s)`、`SMALLINT`），都引入了 §2 中 F5/F7 类运行时失败的可能，必须由**源库元数据的精确性**来担保，并在错误翻译层里有对应条目。

---

## 四、一致性判据（可直接实现的校验清单）

平台在"用户审核 DDL → 执行建表 → 启动 Sink"之间，应当能用下列判据做一次自检（读 PG `information_schema` 即可，不必启动 connector）：

1. 对预期 Connect Schema 的每个字段名 `f`：存在列 `c` 使 `c == f`（**区分大小写的字符串相等**）。不满足 → 对应 F2/F4。
2. 不存在列 `c'` 满足 `lower(c') == lower(f)` 但 `c' != f`（大小写歧义列）。不满足 → F4（且缺列检查不会替你发现）。
3. 表中每个**不在**字段集合里的列，必须 `is_nullable = 'YES'` 或 `column_default IS NOT NULL`。不满足 → F6。
4. 每个字段的目标列类型 ∈ §1.2 对应行的允许集合。不满足 → F5。
5. 若字段可能为 null（源列 nullable），目标列必须 `is_nullable = 'YES'`。不满足 → F6。
6. 若 `insert.mode=upsert`：`pk.fields` 对应的列集合上必须存在 PRIMARY KEY 或 UNIQUE 约束。不满足 → F10。
7. `insert.mode=insert` + `pk.mode=none` 时，目标表**不需要**主键；但若表上有唯一约束，重放会以 F8 形式失败——需要在 checkpoint/幂等策略里单独决策。
8. 目标对象必须是 TABLE 而非 VIEW（`information_schema.tables.table_type = 'BASE TABLE'`）。不满足 → F3。

## 附：本票建议的 Sink 配置基线（v1 全量迁移）

```properties
auto.create=false
auto.evolve=false
insert.mode=insert
pk.mode=none
delete.enabled=false
quote.sql.identifiers=always
db.timezone=UTC
batch.size=1000          # 冒烟阶段设 1，让结构错误第一行就暴露
max.retries=2            # 结构错误重试无意义，快速失败
retry.backoff.ms=1000
errors.tolerance=all     # 配合 DLQ 才能把失败定位到行
errors.deadletterqueue.topic.name=<per-table-dlq>
errors.deadletterqueue.context.headers.enable=true
```

## 参考

- Confluent JDBC Sink 配置参考：<https://docs.confluent.io/kafka-connectors/jdbc/current/sink-connector/sink_config_options.html>
- Confluent JDBC Sink 概览：<https://docs.confluent.io/kafka-connectors/jdbc/current/sink-connector/overview.html>
- `DbStructure.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/DbStructure.java>
- `BufferedRecords.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/BufferedRecords.java>
- `PreparedStatementBinder.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/PreparedStatementBinder.java>
- `FieldsMetadata.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/metadata/FieldsMetadata.java>
- `RecordValidator.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/RecordValidator.java>
- `JdbcSinkTask.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/JdbcSinkTask.java>
- `JdbcDbWriter.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/JdbcDbWriter.java>
- `JdbcSinkConfig.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/sink/JdbcSinkConfig.java>
- `GenericDatabaseDialect.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/dialect/GenericDatabaseDialect.java>
- `PostgreSqlDatabaseDialect.java`：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/dialect/PostgreSqlDatabaseDialect.java>
- PostgreSQL 错误码表（SQLState）：<https://www.postgresql.org/docs/15/errcodes-appendix.html>
- pgjdbc 连接参数 `stringtype`：<https://jdbc.postgresql.org/documentation/use/#connection-parameters>
