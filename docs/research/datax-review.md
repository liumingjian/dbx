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

---

## 3. 大字段（BLOB/CLOB）：DataX 的内存模型与真实上限

### 3.1 读写方式

- 读：`CommonRdbmsReader.Task#buildRecord` —— CLOB/NCLOB → `rs.getString(i)`；BLOB/LONGVARBINARY/VARBINARY/BINARY → `rs.getBytes(i)`。**没有任何流式读取**。
- 写：`CommonRdbmsWriter.Task#fillPreparedStatementColumnType` —— CLOB/NCLOB 与 CHAR/VARCHAR 合并处理，统一 `preparedStatement.setString(idx, column.asString())`；BLOB 走 `setBytes`。同样无流式。

### 3.2 内存模型：不落盘，但也不是"只占一行"

数据路径：`reader 线程 → BufferedRecordExchanger → MemoryChannel(ArrayBlockingQueue) → BufferedRecordExchanger → writer 线程 → writeBuffer`。全部在**同一个 JVM 进程的堆上**，没有磁盘、没有网络中转。峰值堆占用大致是：

```
并发 task 数 × ( exchanger bufferSize(默认32条) ×2 + channel capacity(默认512条) + writer batchSize(默认1024条) ) × 单行字节数
```

配置来源 `core/src/main/conf/core.json`（[链接](https://github.com/alibaba/DataX/blob/master/core/src/main/conf/core.json)）：
`transport.exchanger.bufferSize=32`、`transport.channel.capacity=512`、`transport.channel.byteCapacity=67108864`（64MB）、`container.taskGroup.channel=5`、`entry.jvm="-Xms1G -Xmx1G"`。
postgresqlwriter 默认 `batchSize=1024`，其文档自己警告：*"该值设置过大可能会造成 DataX 运行进程 OOM 情况"*（[postgresqlwriter.md](https://github.com/alibaba/DataX/blob/master/postgresqlwriter/doc/postgresqlwriter.md)）。

**默认堆只有 1GB**。粗算：5 并发 × (512+1024) 行 × 平均 1MB 的 BLOB ≈ 7.5GB —— 远超默认堆。所以实践中带大 BLOB 的表必须手工把 `channel`、`batchSize`、`byteCapacity` 一起调小并调大 `-Xmx`，这个调参负担全部落在使用者身上。

### 3.3 实际上限：有一个隐式硬上限，而且失败方式很糟

`BufferedRecordExchanger#sendToWriter`（[源码](https://github.com/alibaba/DataX/blob/master/core/src/main/java/com/alibaba/datax/core/transport/exchanger/BufferedRecordExchanger.java)）：

```java
if (record.getMemorySize() > this.byteCapacity) {
    this.pluginCollector.collectDirtyRecord(record,
        new Exception(String.format("单条记录超过大小限制，当前限制为:%s", this.byteCapacity)));
    return;   // <-- 直接丢弃，继续跑
}
```

三个结论：
1. **DataX 的单行上限 = `core.transport.channel.byteCapacity`，默认 64MB**（代码里的 fallback 常量是 8MB，配置文件覆盖为 64MB）。这一点几乎没有文档提及。
2. 超限的行**不是报错，是被计入脏数据后丢弃**。如果 `errorLimit` 没配（默认不限），一张有几十行超大 BLOB 的表会**静默少数据**，作业依然显示成功。这是 DataX 在大字段场景最危险的行为。
3. `getMemorySize()` 是 `ClassSize.DefaultRecordHead + Σ(ClassSize.ColumnHead + column.getByteSize())` 的**估算值**（`DefaultRecord#incrByteSize`），不是精确字节数；`StringColumn` 的 `byteSize` 用的是字符串长度而非 UTF-8 字节数，多字节字符会低估。所以这个上限本身也是模糊的。

### 3.4 与 DBX "过 Kafka + 20MB 上限"的对比

| 维度 | DataX（直连不落盘） | DBX（过 Kafka，20MB 上限） |
| --- | --- | --- |
| 单行上限 | 64MB（隐式、可调、估算） | 20MB（显式、由 broker `message.max.bytes` 等强制） |
| 超限行为 | **静默丢弃 + 计入脏数据**，作业可能仍然成功 | 生产端 `RecordTooLargeException`，**显式失败**，可分类上报 |
| 端到端延迟 | 低（一跳） | 高（多一次序列化 + 落盘 + 反序列化） |
| 吞吐上的额外成本 | 无 | 大字段要写一遍 Kafka 磁盘，等于额外一次全量 I/O |
| 失败恢复 | 进程挂 = 从头重跑（无断点） | Kafka 是持久缓冲，writer 侧可从 offset 续跑；reader 与 writer 可解耦重启 |
| 内存压力 | 全在一个 JVM，并发 × 缓冲 × 行大小，易 OOM | 生产端/消费端各自有界，反压由 Kafka 承担 |
| 调参负担 | 高（channel/batchSize/byteCapacity/-Xmx 四处联动） | 中（主要是 batch 与 fetch 大小） |

**判断**：DBX 的 20MB 比 DataX 的 64MB 更严格，看上去是劣势，但**失败语义好得多**——这是更重要的属性。真正需要补的不是把上限提高，而是：
1. **超限行必须显式可见**：预检阶段用 `SELECT MAX(OCTET_LENGTH(col))` 之类的方式扫出会超限的列/行，在迁移开始前就告知用户，而不是运行到一半才炸。
2. **为超大 LOB 留一条旁路**：若确有 >20MB 的行，考虑不走 Kafka（外部存储 + 引用，或 writer 侧直连补写），而不是抬高 Kafka 上限——抬上限会让整个 topic 的内存与 GC 特性劣化。
3. 无论如何都**不要抄 DataX 的静默丢弃**。

---

## 4. 批量 / 整库迁移："不够灵活"的具体含义

### 4.1 是配置粒度问题（已在 §1.3 判定），具体表现为四点

1. **一 job 一 JSON，且实质单 content。** 表数量一多就是"生成 N 份 JSON + 外部调度"的手工活。
2. **多表共享同一份 `column`/`where`/`splitPk`。** `ReaderSplitUtil.doSplit` 中 `splitPk` 从 `originalSliceConfig` 取，是 job 级的；这意味着 `connection[].table[]` 里的表必须**同构且主键同名**，否则只能拆 job。
3. **并发预算按表数平摊，不看表大小。** `eachTableShouldSplittedNumber = ceil(adviceNumber / tableNumber)` —— 一张 10 亿行的表和一张 100 行的表分到同样的并发。大小表混排时长尾极其严重。
4. **没有作业间的依赖/顺序表达。** 外键顺序、先建表后灌数、失败后从第 K 张表续跑，全部需要外部编排。

### 4.2 调度层反而是强项（不要误伤）

`JobContainer#split` → `adjustChannelNumber()`（按 `byte`/`record` 全局限速与 `channel` 数推导并发）→ `doReaderSplit(needChannelNumber)` → `doWriterSplit(taskNumber)` → `TaskGroupContainer` 按 taskGroup 分组执行，并带 `LOAD_BALANCE_RESOURCE_MARK`（从 jdbcUrl 抽 IP 打标，让 core 做有意义的 shuffle，避免同一台库实例的 task 挤在同一个 taskGroup）。这套东西是成熟的。

### 4.3 衍生项目如何缓解

| 项目 | 做法 | 缓解了什么 / 没缓解什么 |
| --- | --- | --- |
| [WeiYe-Jing/datax-web](https://github.com/WeiYe-Jing/datax-web) | 可视化"JSON 构建"：选数据源 → 读元数据 → 生成字段映射 → 套任务模板 → **批量创建 RDBMS 同步任务**；集成 xxl-job 做分布式调度、增量字段自动取区间、实时日志、KILL 进程 | 缓解了 §4.1 的第 1 点（批量出 JSON）和第 4 点（调度/续跑）。**没有**解决自动建表——README 把"表结构同步"明确列在*后续规划*里 |
| [wgzhao/Addax](https://github.com/wgzhao/Addax) | DataX 的现代化重构分支：升级 JDK/依赖、大量新增插件、`rdbmswriter` 通用化、文档化 | 缓解的是插件覆盖面和工程质量。检索 `autoCreateTable`/`createTable` 命中 **0**，**没有**自动建表 |
| 阿里云 DataWorks 数据集成（商业版） | "整库迁移 / 一键全增量"解决方案：目标库自动建表 + 自动建离线与实时任务 + 自动启动 + 全流程监控与分步重试（[官方文档](https://help.aliyun.com/zh/dataworks/datax)） | 这是唯一真正把 §4.1 四点全解决的实现，**且不开源** |

**结论**：DataX 生态里，"批量/整库"这层要么靠 datax-web 这类外挂编排（解决配置生成与调度，不解决建表），要么就是闭源的 DataWorks。DBX 把"整库迁移 + 自动建表"作为产品内建能力，在开源侧确实是空白点。

---

## 5. 值得搬进 DBX 的设计

按"投入产出比"排序。

### 5.1 `errorLimit`：条数与百分比双阈值 —— 优先级最高

源码：`core/src/main/java/com/alibaba/datax/core/util/ErrorRecordChecker.java`（[链接](https://github.com/alibaba/DataX/blob/master/core/src/main/java/com/alibaba/datax/core/util/ErrorRecordChecker.java)）

设计要点（三条都值得照抄）：
1. **两种阈值语义不同、检查时机也不同**：`errorLimit.record`（绝对条数）在**运行中**持续检查，一超立刻失败；`errorLimit.percentage`（0.0~1.0）在**任务结束时**校验——因为比例在早期样本少时没有意义。
2. **`record` 优先级高于 `percentage`**：两者都配时，构造函数直接把 `percentageLimit = null`。避免"两个阈值互相打架"的语义歧义。
3. **`errorLimit.record = 0` 表示零容忍**，是可表达的（`recordLimit < errorNumber` 而非 `<=`）。

配套的脏数据采样：`core.json` 里 `core.statistics.collector.plugin.maxDirtyNumber = 10`，`StdoutPluginCollector` 只打印前 N 条脏记录，避免脏数据风暴打爆日志。writer 侧另有独立的 `dumpRecordLimit`（`needToDumpRecord()`）。

> **搬到 DBX**：迁移任务配置里加 `errorLimit: { record: N, percentage: P }`，语义与优先级照抄。脏数据落到独立的表/文件而不是日志，但**保留"只详细打印前 N 条"**这个防风暴设计。

### 5.2 批量失败 → 逐条重试的降级写入

`CommonRdbmsWriter.Task#doBatchInsert`（[链接](https://github.com/alibaba/DataX/blob/master/plugin-rdbms-util/src/main/java/com/alibaba/datax/plugin/rdbms/writer/CommonRdbmsWriter.java)）：

```java
try {
    connection.setAutoCommit(false);
    ... addBatch(); executeBatch(); connection.commit();
} catch (SQLException e) {
    LOG.warn("回滚此次写入, 采用每次写入一行方式提交. 因为:" + e.getMessage());
    connection.rollback();
    doOneInsert(connection, buffer);   // 逐条 autoCommit=true 重放，失败的那条进脏数据
}
```

这是很聪明的一招：**批量写快，但批量失败无法定位是哪一行；回滚后逐条重放，就把"整批失败"降级成"精确到行的脏数据"**，其余行仍然写入。代价只有出错批次的一次重放。

> **搬到 DBX**：JDBC Sink 端（无论是 Confluent JDBC Connector 还是自研 writer）遇到批量失败时，不要整批丢/整任务停，走同样的"回滚 → 逐条 → 定位脏行 → 计入 errorLimit"。**注意坑**：`doOneInsert` 里对每条都 `preparedStatement.clearParameters()` 放在 `finally`，漏了会串参数。

### 5.3 任务结束的统计块 —— 直接作为 DBX 进度/校验展示的模板

`JobContainer#logStatistics` 输出的字段（[源码](https://github.com/alibaba/DataX/blob/master/core/src/main/java/com/alibaba/datax/core/job/JobContainer.java)）：

```
任务启动时刻 / 任务结束时刻 / 任务总计耗时(s) / 任务平均流量(B/s)
记录写入速度(rec/s) / 读出记录总数 / 读写失败总数
```

值得注意的两点：
- 它给的是**"读出记录总数" + "读写失败总数"**，而不是"写入成功数"。这在校验上是不够的——用户真正想知道的是"源 N 行，目标 M 行，差多少"。DataX 这里是**反面教材**：作业成功但静默丢行（§3.3）时，"读出记录总数"看起来完全正常。
- Transformer 的三个计数（成功/失败/过滤）只在非零时才打印，避免噪音。这个"零值不展示"的小习惯值得学。

> **搬到 DBX**：至少输出 `源行数 / 已读 / 已写成功 / 脏数据数 / 跳过数 / 耗时 / 速率`，**且"已写成功"必须来自目标端确认而不是发送端计数**。这正是 DataX 缺的那一格，也是 DBX 校验功能的立足点。

### 5.4 channel 并发模型

`core/src/main/java/com/alibaba/datax/core/transport/channel/Channel.java` 类注释就一句话：*"统计和限速都在这里"*。设计上把三件事收敛进同一个抽象：

| 能力 | 参数（`core.json`） | 默认 |
| --- | --- | --- |
| 并发度 | `core.container.taskGroup.channel` | 5 |
| 队列深度（条） | `core.transport.channel.capacity` | 512 |
| 队列深度（字节） | `core.transport.channel.byteCapacity` | 64MB |
| 字节限速 | `core.transport.channel.speed.byte`（bps） | -1（关） |
| 记录限速 | `core.transport.channel.speed.record`（tps） | -1（关） |
| 限速采样间隔 | `core.transport.channel.flowControlInterval` | 20ms |

关键设计：**并发度不是直接配的，而是由限速反推的**。`JobContainer#adjustChannelNumber()` 根据全局 `byte`/`record` 限速与单 channel 限速算出 `needChannelNumber`，再 `doReaderSplit(needChannelNumber)`。也就是说用户表达的是"我允许占多少带宽"，框架决定开几个并发——比让用户直接猜并发数好。

限速在 channel 层做（`flowControlInterval` 周期性比较 `currentCommunication`/`lastCommunication` 并 sleep），**读写两侧共享同一个背压点**，不需要 reader/writer 各自实现限流。

> **搬到 DBX**：Kafka 天然提供了缓冲和背压，所以 channel 的"队列"部分不用抄。值得抄的是**(a) 用"带宽/行速上限"表达意图、由系统反推并发**；**(b) 双维度限速（字节 + 行数）**——只限行数遇到宽表会打爆源库，只限字节遇到窄表会并发不足；**(c) 限速采样间隔要可配**（20ms 这种量级）。

### 5.5 `splitPk` 单表分片读取 —— 重点：它怎么做的，以及有哪些坑

源码：`plugin-rdbms-util/.../reader/util/SingleTableSplitUtil.java` 与 `ReaderSplitUtil.java`。

**做法（四步）**：
1. **算分片数**：`eachTableShouldSplittedNumber = ceil(channel数 / 表数)`；**单表时再乘 `splitFactor`（默认 5）**。注释解释了这个魔数的来历——早期是 `×2+1`，`+1` 导致长尾，后来改成 `×5` 并可通过 `splitFactor` 配置。即：**故意切得比并发数多，用小任务填平长尾**。
2. **取范围**：`SELECT MIN(splitPk), MAX(splitPk) FROM table [WHERE ...]`（`genPKRangeSQL`）。
3. **等分**：`RdbmsRangeSplitWrap.splitAndWrap(min, max, num, pkName)`，对整型用 `BigInteger` 均分成 `[a,b)` 区间；对字符串按字符序均分。
4. **拼 SQL**：每个分片一条 `SELECT col FROM t WHERE (原where AND) pk >= a AND pk < b`，**额外再生成一条 `pk IS NULL` 的分片**兜住空值行。

**坑（这些是 DBX 做 v2 单表分片时必须规避的）**：

1. **只支持单列、整型或字符串。** 浮点/日期/复合主键直接抛 `ILLEGAL_SPLIT_PK`。mysqlreader 文档更严：``目前splitPk仅支持整形数据切分，不支持浮点、字符串、日期等其他类型``——**文档与代码不一致**（代码里 `isStringType` 分支是存在的），说明字符串切分是不被推荐的路径。
2. **字符串切分几乎必然数据倾斜。** 按字符码点等分 `['a...','z...']` 与实际数据分布毫无关系（UUID 尚可，业务编码则灾难）。
3. **假设 PK 均匀分布。** MIN/MAX 等分对**有空洞的自增主键**（大量删除过、或分库分表后 ID 段稀疏）会切出大量空分片和少数超大分片。DataX 没有任何采样或直方图矫正。
4. **`SELECT MIN/MAX` 本身可能很贵。** 代码里专门为 OceanBase 写了绕过（*"OceanBase 对 `SELECT MIN(%s),MAX(%s)` 没有做查询改写，会进行全表扫描"*），说明这不是理论担忧。MySQL InnoDB 上单列索引的 MIN/MAX 很快，但**带 `where` 条件时优化器可能退化成全扫**。
5. **`pk IS NULL` 分片是全表扫描。** 它无法走范围索引，且在主键上永远返回 0 行——纯粹的浪费。只有 `splitPk` 用了可空的非主键列时才有意义。
6. **分片之间没有一致性快照。** 各分片是**独立的连接、独立的事务、不同的时刻**发起查询。源库在迁移期间有写入的话，分片之间会看到不一致的数据（丢行/重行都可能）。DataX 完全不处理这个问题——它默认场景是"离线、源库静止"。
7. **切分数与最终 task 数不等。** 注释明写 *"最终切分份数不一定等于 eachTableShouldSplittedNumber"*、*"向上取整可能和 adviceNumber 没有比例关系了"*。做容量规划时不能假设 task 数 = channel 数。

> **对 DBX v1 排除单表分片这个决定的评估**：**决定是对的**。上面 7 条里，第 6 条（跨分片一致性快照）是根本性的——要做对必须用 `REPEATABLE READ` + 单一事务/一致性快照点（MySQL 可用 `FLUSH TABLES WITH READ LOCK` 取 GTID/binlog 位点后各连接 `START TRANSACTION WITH CONSISTENT SNAPSHOT`），这是一个独立的、有分量的设计任务，不该塞进 v1。
>
> **v2 做的时候的规避清单**：(a) 只支持整型单列主键，字符串一律不做；(b) 用采样（如 `NTILE` 或按索引页采样）替代 MIN/MAX 等分，或至少用"按行数偏移取边界值"；(c) 一致性快照必须先于分片确定；(d) 分片数 = 并发数 × 小倍数（DataX 用 5，可作起点）且倍数可配；(e) 去掉无意义的 `IS NULL` 分片，改成"仅当 splitPk 可空时才生成"；(f) 分片边界与实际读到的行数要作为进度/校验元数据落库。

---

## 6. 自动建表：DataX 生态里有人解决过吗？

**框架层：没有。** 结论及证据已在 §1.1 与 §4.3 给出，汇总一下：

| 层次 | 实现 | 状态 |
| --- | --- | --- |
| DataX 本体 | 无 DDL 生成，writer 依赖目标表已存在（`calcWriteRecordSql` 读目标表 metadata） | 明确不做。[#1032](https://github.com/alibaba/DataX/issues/1032) 回复"不能" |
| `preSql` workaround | 用户自己在 `preSql` 写 `CREATE TABLE IF NOT EXISTS` | 可行但需手写每张表的 DDL；且有 [#880 「preSql 不支持执行 DDL」](https://github.com/alibaba/DataX/issues/880) 这类坑 |
| 社区自助方案 | [#972](https://github.com/alibaba/DataX/issues/972) 的官方式回答："自己写个 JDBC 查元数据表拿列名和类型，再替换建表语句模板" | 就是让用户自己造 DBX 要造的那个轮子 |
| datax-web | README 把"表结构同步"列在**后续规划**；[PR #338](https://github.com/WeiYe-Jing/datax-web/pull/338) 只是批量构建时从 Hive 建表语句读字段信息填进 JSON——是**读源端 DDL 做字段映射**，不是**在目标端建表** | 未实现 |
| Addax | 仓库检索 `autoCreateTable` / `createTable` 命中 0 | 未实现 |
| DataWorks 数据集成（闭源） | "整库迁移/一键全增量"：目标库自动建表 + 自动建任务 + 自动启动 + 分步重试 | 已实现，**不开源** |

**为什么没人在开源层做成**——两个结构性原因，DBX 必须正面回答：
1. **自动建表需要"逻辑类型"信息，而 DataX 的 Record 只有 6 种物理类型。** 拿到一个 `DoubleColumn` 无法反推该建 `numeric(10,2)` 还是 `double precision`。DataX 的类型系统从根上就不支持这件事——这也解释了为什么它"按设计不做"。
2. **建表还需要列以外的东西**：主键、唯一约束、索引、自增/序列、默认值、NOT NULL、注释、字符集/排序规则。这些都不在数据流里，必须走**独立的 schema 读取通道**（`information_schema`）。

> **对 DBX 的含义（本票最有力的结论）**：DBX 选 Kafka + Connect 路线时，`ConnectSchema` 的 `name`/`parameters` 恰好提供了第 1 点缺的逻辑类型层；而"独立的 schema 读取 → 类型映射 → DDL 生成"这条通道是 DBX 的核心增量，DataX 生态在开源侧从未有人做出来。**这条路是真空地带，不是重复造轮子。**

---

## 7. 给 DBX 的行动项汇总

| # | 行动项 | 来源 | 建议优先级 |
| --- | --- | --- | --- |
| 1 | `errorLimit` 双阈值（record 绝对数 + percentage，record 优先）+ 脏数据采样上限 | §5.1 | 高（v1） |
| 2 | 统计输出必须区分"已读 / 已写成功（目标端确认）/ 脏数据 / 跳过"，不要抄 DataX 只报"读出总数" | §5.3 | 高（v1） |
| 3 | 超 20MB 的行必须**显式失败并前置探测**，绝不静默丢弃 | §3.3/§3.4 | 高（v1） |
| 4 | DECIMAL 的 precision/scale 取自 `information_schema`，禁止采样推断 | §2.3 | 高（v1） |
| 5 | 高精度时间列用微秒级逻辑类型或字符串直通，不要落进毫秒基底 | §2.2/§2.3 | 高（v1） |
| 6 | 未知/不可映射类型定义带标注的降级通道（不要 `default: throw`） | §2.3 | 中（v1） |
| 7 | 批量写失败 → 回滚 → 逐条重放定位脏行 | §5.2 | 中（v1） |
| 8 | 限速用"字节 + 行数"双维度表达，由系统反推并发度 | §5.4 | 中（v1/v2） |
| 9 | 单表分片按 §5.5 的规避清单实现（一致性快照先行、只整型、采样切分、分片数 = 并发 × 倍数） | §5.5 | v2 |
| 10 | 并发预算按表**大小**分配而非表数量平摊（DataX 的长尾问题） | §4.1 | v2 |

