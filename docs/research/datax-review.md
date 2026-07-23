# DataX 痛点复核与可借鉴的设计

> 研究票：[liumingjian/dbx#8](https://github.com/liumingjian/dbx/issues/8)（Part of #1）
> 日期：2026-07-23
> 一手来源：`alibaba/DataX` master 源码与各插件 `doc/*.md`、DataX Issues、阿里云 DataWorks 官方文档、衍生项目 `WeiYe-Jing/datax-web` / `wgzhao/Addax`。

## 0. 结论速览

| 放弃 DataX 的理由 | 判定 | 一句话依据 |
| --- | --- | --- |
| (1) 不能自动建表 | **属实** | 开源 DataX 无任何 DDL 生成逻辑；官方 issue 里维护者/社区直接回答"不能"；自动建表能力只存在于商业版 DataWorks 数据集成的"整库迁移"解决方案层。 |
| (2) 特殊数据类型（CLOB/BLOB）支持粒度不够 | **部分属实** | CLOB/BLOB 本身读写是通的（`rs.getString` / `rs.getBytes`），但**全量物化进 JVM 堆**，且单条记录超过 channel `byteCapacity` 会被**静默丢进脏数据**而不是报错。真正"粒度不够"的是 JSON / ENUM / 数组 / 无符号类型这类语义类型，而不是 LOB。 |
| (3) 批量迁移时不够灵活 | **属实（是配置粒度问题，不是调度问题）** | 一个 job = 一份 JSON = 一对 reader/writer；多表只能靠 `connection[].table[]` 列表 + 同构假设，跨表列映射/类型差异无法表达。调度层（并发/限速/分片）反而是 DataX 最强的部分。 |

**最重要的反直觉发现**：常见说法"DataX 六种内部类型会丢 DECIMAL 精度"在 RDBMS→RDBMS 路径上**不成立**。DataX 的 `DoubleColumn` 内部用 `String`（`BigDecimal.toPlainString()`）存储，writer 对 `NUMERIC/DECIMAL` 又走 `setString`，全程不经过 IEEE754 double。真正丢的是**时间精度（毫秒截断）**和**语义类型信息**。详见 §2。

**值得搬进 DBX 的设计**（详见 §5）：
1. `errorLimit`（条数 / 百分比双阈值）+ 脏数据采样落盘 —— 优先级最高，成本最低。
2. 批量失败 → 逐条重试 → 定位脏行的降级写入策略（`doBatchInsert` catch → `doOneInsert`）。
3. 任务结束的标准统计块（读出行数 / 读写失败数 / 耗时 / 平均流量 / 写入速度）。
4. `splitPk` 分片读取的**具体做法与坑**（v1 虽不做，但 v2 要照抄它的坑规避清单）。
5. channel 语义：并发度、record/byte 双维度限速、单条记录大小上限，三件事统一在一个抽象里。

---

## 1. 痛点复核

### 1.1 不能自动建表 —— 属实

**证据（源码）**：`plugin-rdbms-util` 的写入侧只有三类 SQL 操作，没有 DDL 生成：
- `CommonRdbmsWriter.Job#init/prepare` 执行用户配置的 `preSql`/`postSql`；
- `WriterUtil.executeSqls` 只是原样执行用户给的语句；
- `CommonRdbmsWriter.Task#calcWriteRecordSql` 用 `resultSetMetaData`（**从目标表已存在的元数据读出来的**）拼 `INSERT`。

也就是说 writer 在 `init` 阶段就要求目标表已存在——它需要读目标表的列类型才能决定 `PreparedStatement` 的 `setXxx`。这是架构性的：DataX 的 writer 是"面向已存在的表"的。

**证据（官方 issue）**：
- [alibaba/DataX#1032「datax能自动建立目标表么」](https://github.com/alibaba/DataX/issues/1032) —— 回复：`不能`。
- [alibaba/DataX#972「有建表功能吗？」](https://github.com/alibaba/DataX/issues/972) —— 回复："可以自己写个 jdbc 去查数据库的元数据表，拿到列名和数据类型，再将建表语句的模板替换字符串"。即社区共识是"你自己写"。
- [alibaba/DataX#880「关系型数据库 presql 不支持执行DDL sql」](https://github.com/alibaba/DataX/issues/880) —— 连用 `preSql` 塞 DDL 这条 workaround 都有坑。

**证据（生态层）**：DataX 自述"是阿里云 DataWorks 数据集成的开源版本"（[README](https://github.com/alibaba/DataX)）。而"整库迁移 + 目标库建表 + 自动建任务"是 [DataWorks 数据集成](https://help.aliyun.com/zh/dataworks/datax) 商业版的**解决方案层**能力，没有开源。这条痛点不是"DataX 没做好"，而是"DataX 按设计就不含这一层"。

**衍生项目怎么办的**（§6 展开）：datax-web 把"表结构同步"列在 README 的 *后续规划* 里，未实现；Addax 仓库全库检索 `autoCreateTable` / `createTable` 命中数为 **0**（`gh api search/code repo:wgzhao/Addax`）。**整个 DataX 开源生态确实没人在框架层解决自动建表。**

> **对 DBX 的含义**：这条理由完全成立，而且是最硬的一条。DBX 把"源库 schema 读取 → 类型映射 → 目标 DDL 生成"做成一等公民，正好补的是 DataX 结构性缺失的那一层。

### 1.2 特殊数据类型（CLOB/BLOB）支持粒度不够 —— 部分属实

需要拆成两个问题看，因为"CLOB/BLOB"和"特殊类型"其实是两件事。

**(a) LOB 本身：能读能写，但内存模型是全量物化，且有隐式上限。**
`CommonRdbmsReader.Task#buildRecord`（[源码](https://github.com/alibaba/DataX/blob/master/plugin-rdbms-util/src/main/java/com/alibaba/datax/plugin/rdbms/reader/CommonRdbmsReader.java)）：

```java
case Types.CLOB:
case Types.NCLOB:
    record.addColumn(new StringColumn(rs.getString(i)));   // 整个 CLOB 变成一个 Java String
    break;
...
case Types.BINARY: case Types.VARBINARY:
case Types.BLOB:   case Types.LONGVARBINARY:
    record.addColumn(new BytesColumn(rs.getBytes(i)));     // 整个 BLOB 变成一个 byte[]
    break;
```

没有流式 `getBinaryStream` / `getCharacterStream`，没有分块。一行 = 一个 `Record` = 堆上的完整对象。所以"直连不落盘"的代价是：**峰值堆占用 ≈ 并发 task 数 × channel 缓冲 × 单行大小**，而不是"只占一行"。详见 §3。

**(b) 语义类型：这才是"粒度不够"的真身。**
`buildRecord` 的 `switch` 走的是 `java.sql.Types` 常量，`default` 分支直接抛 `DBUtilErrorCode.UNSUPPORTED_TYPE`。凡是 JDBC 驱动没映射到标准 `Types` 的类型，DataX 就地失败。真实反馈：
- [#1001「postgresqlreader不支持json，_text类型字段」](https://github.com/alibaba/DataX/issues/1001)
- [#1833「Map() 类型的字段无法识别」](https://github.com/alibaba/DataX/issues/1833)
- [#1782「mysql 迁 oceanbase，bit 字段报内容超长」](https://github.com/alibaba/DataX/issues/1782) —— 对应 mysqlreader 文档自己写的告警：``bit DataX属于未定义行为``。

`mysqlreader` 文档的类型转换表也直说了：``除上述罗列字段类型外，其他类型均不支持``（[mysqlreader.md §3.3](https://github.com/alibaba/DataX/blob/master/mysqlreader/doc/mysqlreader.md)）。表里没有 `json`、没有 `enum`、没有 `set`、没有 `geometry`。

**判定「部分属实」的理由**：如果原话是"CLOB/BLOB 不支持"，那不准确——支持，只是内存模型粗糙且有隐式上限。如果原话是"对特殊数据类型的支持粒度不够"，那准确——DataX 的类型分辨率就是 6 挡，超出 `java.sql.Types` 的一律不认。

### 1.3 批量迁移不够灵活 —— 属实，且根因是配置粒度而非调度

**是配置粒度问题。** 一个 DataX job 的 JSON 结构是：`job.content[]` → 每个元素一对 `reader`/`writer`。虽然 `content` 是数组，但 `JobContainer` 与调度实现上长期只支持**单个 content 元素**（社区共识与 [#304 类问题](https://github.com/WeiYe-Jing/datax-web/issues/304) 报的 `Code:[Framework-03] 引擎配置错误`同源）。多表的唯一表达方式是：

```json
"connection": [{ "table": ["t1","t2","t3"], "jdbcUrl": ["..."] }]
```

而这要求这批表**列集合同构**——因为 `column`、`splitPk`、writer 的 `preSql` 全是 job 级别的单一配置，不是 per-table 的。一旦两张表列不同，就必须拆成两个 job / 两份 JSON。

`ReaderSplitUtil.doSplit` 也印证了这个模型：多表时 `eachTableShouldSplittedNumber = adviceNumber / tableNumber`，所有表共享同一份 `column` / `where` / `splitPk`（[源码](https://github.com/alibaba/DataX/blob/master/plugin-rdbms-util/src/main/java/com/alibaba/datax/plugin/rdbms/reader/util/ReaderSplitUtil.java)）。

**不是调度问题。** DataX 的调度层（`JobContainer` → `doReaderSplit(needChannelNumber)` → `TaskGroupContainer` → channel 并发 + 全局限速）反而设计得相当好，是本次调研里最值得抄的部分。所以"不够灵活"的准确表述是：**DataX 是"单同步任务的执行引擎"，不是"迁移编排器"**；整库迁移需要在它之上再写一层生成 N 份 JSON 并调度的东西——这正是 datax-web 和 DataWorks 在做的事。

> **对 DBX 的含义**：成立，但要注意这条理由不能推出"DataX 的调度差"。DBX 如果只是把"生成 N 份配置 + 编排"这层做出来，本质上是在重做 datax-web 的定位；DBX 的差异化必须落在**自动建表 + 类型保真**上（§2、§6）。

---

## 2. 类型系统对比：DataX 六型 vs DBX 的 Connect Schema / Avro

### 2.1 "六种内部类型"这个说法：证实，但要加一条重要修正

源码位置：`common/src/main/java/com/alibaba/datax/common/element/Column.java`（[链接](https://github.com/alibaba/DataX/blob/master/common/src/main/java/com/alibaba/datax/common/element/Column.java)）

```java
public enum Type {
    BAD, NULL, INT, LONG, DOUBLE, STRING, BOOL, DATE, BYTES
}
```

枚举有 9 个成员，但 `BAD`/`NULL` 是哨兵、`INT` 没有对应的具体子类（同目录下只有 `LongColumn`、`DoubleColumn`、`StringColumn`、`BytesColumn`、`DateColumn`、`BoolColumn` 六个 `Column` 实现）。**所以"归约为 Long/Double/String/Bytes/Date/Bool 六种"属实。**

**必须加的修正 —— `DOUBLE` 不是 double。** `DoubleColumn` 的 `rawData` 实际是 `String`：

```java
public DoubleColumn(final BigDecimal data) {
    this(null == data ? (String) null : data.toPlainString());   // 存字符串
}
private DoubleColumn(final String data, int byteSize) {
    super(data, Column.Type.DOUBLE, byteSize);
}
```

注释里写得很直白：`Double无法表示准确的小数数据，我们不推荐使用该方法保存Double数据，建议使用String作为构造入参`。同理 `LongColumn` 内部是 `BigInteger` 而不是 `long`。

### 2.2 MySQL → PostgreSQL 具体会丢什么

按 `CommonRdbmsReader.Task#buildRecord` 与 `CommonRdbmsWriter.Task#fillPreparedStatementColumnType` 的实际代码逐项核对：

| 关注点 | DataX 实际行为 | 判定 |
| --- | --- | --- |
| **DECIMAL(38,10) 精度** | reader：`case Types.NUMERIC/DECIMAL: new DoubleColumn(rs.getString(i))` —— 走 `getString`，字符串进；writer：`case Types.NUMERIC/DECIMAL: preparedStatement.setString(idx, column.asString())` —— 字符串出。**全程不经过 IEEE754。** | **不丢**（推翻常见说法） |
| **无符号整型 `BIGINT UNSIGNED`** | reader：`case Types.BIGINT: new LongColumn(rs.getString(i))`，`LongColumn(String)` 内部 `NumberUtils.createBigDecimal(data).toBigInteger()` → `BigInteger`；writer 对 `BIGINT` 也走 `setString`。18446744073709551615 能原样过。 | **不丢**（但目标端 PG 无 unsigned，需要 `numeric(20,0)`——这是**建表**问题，不是传输问题） |
| **`DATETIME(6)` / `TIMESTAMP(6)` 微秒** | reader：`new DateColumn(rs.getTimestamp(i))` → `DateColumn` 的 `rawData` 是 `Long` 毫秒（`DateColumn(Long stamp)`：*"实际存储有date改为long的ms，节省存储"*），`nanos` 字段在这条路径上**从未被赋值**；writer：`new java.sql.Timestamp(utilDate.getTime())`。 | **丢**：亚毫秒精度被静默截断。实测反馈见 [#2359「timestamp(6) 类型字段精度丢失」](https://github.com/alibaba/DataX/issues/2359)、[#1544「datetime64 精度丢失」](https://github.com/alibaba/DataX/issues/1544) |
| **时区** | 时间在 Record 里是"绝对毫秒"，reader `rs.getTimestamp(i)`（不带 `Calendar`）按 **JVM 默认时区**解释，writer 又按 JVM 默认时区写回。`core/src/main/conf/core.json` 里 `common.column.timeZone` 硬编码 **`"GMT+8"`**。 | **易错**：单进程内自洽，但源/目标库 session 时区不同、或用了 `timeZone` 配置转字符串时会偏移。反馈见 [#106「部分 date 类型字段值在目标表中发生变化」](https://github.com/alibaba/DataX/issues/106) |
| **`JSON` 类型** | MySQL Connector/J 把 JSON 报成 `LONGVARCHAR`，因此**能过**，但落到 PG 端是 `text` 语义——DataX 不知道它是 JSON，PG 侧若目标列是 `jsonb`，writer 的 `resultSetMetaData` 拿到的是 `Types.OTHER`，落入 `default` 分支 → `UNSUPPORTED_TYPE` 抛错。对照 [#1001 postgresqlreader 不支持 json](https://github.com/alibaba/DataX/issues/1001) | **丢语义 / 常直接失败** |
| **`ENUM` / `SET`** | 报成 `CHAR`/`VARCHAR`，值能过，但"这是枚举、取值域是这些"的信息完全丢失，目标端只能建成 `varchar`。 | **丢语义** |
| **`BIT(n)`** | `bit(1)`→`Types.BIT`→`BoolColumn`；`bit(>1)`→`Types.VARBINARY`→`BytesColumn`。mysqlreader 文档自述 ``bit DataX属于未定义行为``；实测报错见 [#1782](https://github.com/alibaba/DataX/issues/1782) | **未定义行为** |
| **`YEAR`** | 特判：`metaData.getColumnTypeName(i).equalsIgnoreCase("year")` → `LongColumn`（绕 [MySQL bug#35115](http://bugs.mysql.com/bug.php?id=35115)）。文档表格却写 `year → String`，**文档与代码不一致**。 | 行为正确但文档误导 |
| **`GEOMETRY` / 数组 / `MEDIUMINT`外的自定义类型** | `default:` → `throw DataXException(UNSUPPORTED_TYPE)` | **直接失败** |

### 2.3 保真度判断：**DBX 经 Connect Schema / Avro 的路径更高，但优势的来源和直觉不同**

明确判断：**DBX 的路径保真度更高。** 但要说清楚优势到底在哪，否则会拿错论据说服自己。

**DataX 输在哪：**
1. **时间精度天花板是毫秒**，写死在 `DateColumn` 的存储形态里，无配置可绕。Connect 的 `org.apache.kafka.connect.data.Timestamp` 逻辑类型同样是毫秒基底 —— 所以这一条 **Connect Schema 默认路径并不比 DataX 强**；DBX 想赢必须显式用 Avro `timestamp-micros` / Connect `Decimal` 之类的逻辑类型，或对高精度时间列走字符串直通。**这是一条必须落到 DBX 设计里的行动项，不是白捡的优势。**
2. **没有"逻辑类型"层。** DataX 的 6 型是纯物理表示，Record 上不携带"这是 DECIMAL(38,10)/这是 JSON/这是 ENUM"。Connect Schema 有 `name` + `parameters`（`Decimal` 带 `scale`、`Date`/`Time`/`Timestamp`、以及 Debezium/JDBC connector 自定义的 `io.debezium.data.Json` 等），Avro 有 `logicalType`。**这是 DBX 真正的结构性优势**：类型元数据能随数据一起流到写入端，写入端因此有条件做正确的目标端类型决策——也正好是自动建表所需要的同一份信息。
3. **未知类型即失败，没有降级通道。** DataX `default: throw`。Connect/Avro 至少可以退化成带 `name` 标注的 `string`/`bytes`，保留"我原本是什么"。

**DataX 赢在哪（别忽略）：**
1. **DECIMAL 走 String 直通，反而比"经过一次 Avro `bytes+scale` 编解码"更不容易出错。** Avro `decimal` 是 `bytes` + `precision/scale`，如果 DBX 的建表阶段推断的 scale 与源库不一致，会在编码期就把数据截断——而 DataX 那种"字符串搬运"在同构精度下是无损的。**DBX 必须保证 scale 来自源库元数据而非采样推断。**
2. **少一次序列化跳板。** DataX 是 JDBC→内存→JDBC；DBX 是 JDBC→Connect Schema→Avro→Kafka→Avro→Connect Schema→JDBC。每一跳都是一次潜在的类型收窄点。保真度的优势只有在**每一跳都用对逻辑类型**时才兑现，否则可能反而更差。

**结论一句话**：DBX 的类型保真度上限明显更高（有逻辑类型层、有元数据随行、有降级通道），但**不是自动兑现的**；必须显式做三件事——高精度时间用微秒级逻辑类型或字符串直通、DECIMAL 的 precision/scale 取自源库 `information_schema` 而非推断、未知类型定义带标注的降级通道。做不到这三条，DBX 的实际保真度可能只是和 DataX 打平。
