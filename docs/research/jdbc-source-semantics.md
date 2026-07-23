# JDBC Source 的离线全量语义与「跑完即停」的判定手段

> 研究票：[liumingjian/dbx#3](https://github.com/liumingjian/dbx/issues/3)
> 代码基线：`confluentinc/kafka-connect-jdbc` **v10.9.6**（本仓 clone，`git describe` = v10.9.6）。
> 文中源码引用均为该 tag；与旧版本的差异单列一节。

---

## 结论摘要

1. **JDBC Source 没有任何「跑完一遍就停」的官方语义。** `mode=bulk` 的定义就是「每次 poll 整表重读」（`MODE_DOC`: *"bulk: perform a bulk load of the entire table each time it is polled"`），`BulkTableQuerier` 的类注释直接写着 *"BulkTableQuerier always returns the entire table"*。跑完一轮后 querier 被 `reset()` 并重新入队，等 `poll.interval.ms`（默认 5000ms）后再来一遍，**无限循环**。所以「读完即停」必须由 DBX 平台在外部判定并主动 `DELETE` connector。
2. **bulk 模式不写 source offset。** `BulkTableQuerier.extractRecord()` 构造 `new SourceRecord(partition, null, topic, ...)` —— offset 参数是 `null`。因此**「查 source offset 判断进度」这条路在 bulk 模式下完全不可用**（`GET /connectors/{name}/offsets` 对 bulk 模式的 JDBC source 返回空/无意义）。这是本票最重要的负面结论。
3. **推荐的完成判定信号：Kafka topic 的 end offset（committed 记录数）与源表 `COUNT(*)` 对齐**，辅以「end offset 在 N 个采样周期内不再增长」的停滞检测。这是唯一在 bulk 模式下可靠、且与 Connect 内部实现解耦的信号。详见「完成判定信号清单」。
4. **`mode=incrementing` 可以被借用做一次性全量，且比 bulk 更好**：它写 source offset（可查、可续跑）、天然幂等、崩溃重启不会重复整表。终点用「启动前抓一次 `MAX(pk)`」界定，平台轮询 offset 到达该值即判完成。代价是要求单调递增非空数值列（MySQL 自增主键满足；联合主键/UUID 主键不满足，退回 bulk）。
5. **`query` 模式与表白名单互斥**（`JdbcSourceConnector.start()` 显式抛 `ConnectException`），且**永远只产生 1 个 task**（`Collections.singletonList(taskProps)`），`tasks.max` 在该模式下无效。
6. **表模式下一张表只归一个 task**，`numGroups = min(表数, tasks.max)`，用 `ConnectorUtils.groupPartitions` 均分。**单表无法被多 task 并行读** —— 这直接决定了 DBX 的「装箱」并行度上限 = 箱内表数。
7. **topic 名 = `topic.prefix` + 表名**，硬编码在 `BulkTableQuerier.extractRecord()` 里，唯一的定制手段是 `RegexRouter` 等 SMT。
8. **`DELETE /connectors/{name}` 会走正常 stop 路径**：`JdbcSourceTask.stop()` → 关 ResultSet/Statement、`db.commit()` 释放读事务、关连接。不会在源库留下脏状态；但**已经产出到 topic 的记录不会回滚**，所以中途删除 = topic 里有部分数据，重跑必须先清 topic 或换新 topic。

---

## 1. `mode=bulk` 的确切语义

### 1.1 每次 poll 都整表重读

- 配置文档（`JdbcSourceConnectorConfig.java`，`MODE_DOC`）：
  > `bulk: perform a bulk load of the entire table each time it is polled`
- `BulkTableQuerier` 类注释：`/** BulkTableQuerier always returns the entire table. */`
- `BulkTableQuerier.createPreparedStatement()` 在 `QueryMode.TABLE` 下拼的就是 `SELECT * FROM <table>`（可再追加 `query.suffix`），**没有任何 WHERE/游标条件**。
- 来源：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/source/BulkTableQuerier.java>

### 1.2 循环是怎么形成的

`TableQuerierProcessor.process()`（v10.9.x 引入的后台处理线程）：

```java
while (destination.isRunning() && !tableQueue.isEmpty()) {
  final TableQuerier querier = tableQueue.peek();
  if (!querier.querying()) {
    final long nextUpdate = querier.getLastUpdate()
        + config.getInt(JdbcSourceTaskConfig.POLL_INTERVAL_MS_CONFIG);
    ...sleep...
  }
  processQuerier(destination, querier);   // 内层 while(querier.next()) 把整个 ResultSet 抽干
}
```

`processQuerier()` 抽干 ResultSet 后调用 `resetAndRequeueHead(querier, false)`；因为 `resetOffset=false`，querier 被 **重新加回 `tableQueue`**，`lastUpdate` 更新为当前时间。于是下一轮等 `poll.interval.ms` 再整表重读。

- `poll.interval.ms` 默认 **5000**（`POLL_INTERVAL_MS_DEFAULT = 5000`）。它**不是**「一次 poll 取多少」，而是「同一张表两轮全量扫描之间的间隔」。
- `batch.max.rows` 默认 **1000**（`BATCH_MAX_ROWS_DEFAULT = 1000`），控制一次交给 Connect 框架的批大小，不影响是否重读。
- 来源：`TableQuerierProcessor.java`、`TableQuerier.java`、`JdbcSourceConnectorConfig.java` @ v10.9.6。

**对 DBX 的含义**：把 `poll.interval.ms` 设成一个很大的值（如 `2147483647`，约 24.8 天）可以让「第二轮全量」实际上永不发生，从而把 bulk 变成事实上的一次性全量。这是社区常用做法，但**不是官方保证的语义**，只是拖延而非终止。平台仍必须主动删 connector。

### 1.3 有没有「只跑一遍」的官方手段？

没有。Kafka Connect 框架层面也没有：`SourceTask` 的契约是 `poll()` 持续被调用直到 task 被停止；框架不提供「task 自行宣告完成」的回调。`poll()` 返回 `null` 只表示「本次没有数据」，框架会继续调用。

- Kafka Connect 开发指南（SourceTask 契约）：<https://kafka.apache.org/documentation/#connect_developing>
- `SourceTask` javadoc：<https://kafka.apache.org/40/javadoc/org/apache/kafka/connect/source/SourceTask.html>

---

## 2. `mode=incrementing` / `timestamp` 能否借用做一次性全量

**能，而且对 DBX 更有利。** 推荐优先用 `incrementing`。

`TimestampIncrementingTableQuerier` 在 `incrementing` 模式下生成的 SQL 形如：

```sql
SELECT * FROM <table> WHERE <incCol> > ? ORDER BY <incCol> ASC
```

每次 poll 从上次 offset 之后继续，**不会重读已读的行**（源码：`TimestampIncrementingTableQuerier.createPreparedStatement()` 中 `incrementingWhereClause` 分支）。当没有新行时返回空结果集，poll 变成空转。

对 DBX 的用法：

1. 建 connector 前，平台执行 `SELECT MAX(pk) FROM t` 记为 `target`（离线迁移，源库静态，`target` 是确定的终点）。
2. `mode=incrementing`、`incrementing.column.name=<pk>`。
3. 平台轮询 `GET /connectors/{name}/offsets`，读到该表 partition 的 `incrementing` 值 >= `target` 即判完成，删 connector。

优点：
- **offset 可见**，进度可量化（不像 bulk 完全不可见）。
- **崩溃/重启幂等**：Connect 从 offset 续跑，不会重复整表。bulk 模式下 task 重启就是从头再来一遍，topic 里出现重复。
- 可与 `query.suffix`/`query` 组合做范围切分。

约束（源码 `JdbcSourceTask.validateNonNullable()`）：
- `incrementing.column.name` 指定的列**不能可空**，否则启动即 `ConnectException`（可用 `validate.non.null=false` 关掉检查，但风险自负）。
- 列必须严格递增。MySQL `AUTO_INCREMENT` 主键满足；**UUID/字符串主键、联合主键不满足** → 这类表只能退回 bulk。
- `timestamp` 模式还多一层 `timestamp.delay.interval.ms` 与时区语义，离线全量场景没必要引入；不推荐。

WHERE 子句的确切生成代码在 `TimestampIncrementingCriteria.incrementingWhereClause()`：

```java
protected void incrementingWhereClause(ExpressionBuilder builder) {
  builder.append(" WHERE ");
  builder.append(incrementingColumn);
  builder.append(" > ?");
  builder.append(" ORDER BY ");
  builder.append(incrementingColumn);
  builder.append(" ASC");
}
```

注意 **没有上界**（`timestamp` 模式才有 `< ?` 上界）。所以「终点」必须由平台在外部用 `MAX(pk)` 界定，连接器自己不会停。

来源：
- <https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/source/TimestampIncrementingTableQuerier.java>
- <https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/source/TimestampIncrementingCriteria.java>
- <https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/source_config_options.html>

---

## 3. `query` 模式的限制

源码 `JdbcSourceConnector.start()` @ v10.9.6：

```java
if (config.getQuery().isPresent()) {
  if (whitelistSet != null || blacklistSet != null
      || includeListSet != null || excludeListSet != null) {
    throw new ConnectException(JdbcSourceConnectorConfig.QUERY_CONFIG
        + " may not be combined with whole-table copying settings.");
  }
  whitelistSet = Collections.emptySet();
}
```

- **不能与 `table.whitelist` / `table.blacklist` / `table.include.list` / `table.exclude.list` 共存**，一旦共存 connector 直接 FAILED。
- `taskConfigs(int maxTasks)` 在 query 模式下：`taskConfigs = Collections.singletonList(taskProps);` —— **无视 `tasks.max`，永远 1 个 task**。
- query 模式下 topic 名 = `topic.prefix` **本身**（不拼表名），见 `BulkTableQuerier.extractRecord()` 的 `case QUERY: topic = topicPrefix;`。
- `query` 的 SQL **不得自带 WHERE**（若用增量模式），因为连接器要往后追加 WHERE 子句（`QUERY_DOC` 原文）。需要 WHERE 时用 `query.suffix`，或自行处理增量。
- `JdbcSourceTask.start()` 另有校验：一个 task 不能同时被分配 table 和 query。

**对 DBX 的含义**：query 模式 = 单 topic、单 task。如果 DBX 想用它做「一箱一 query」，就丧失了箱内并行，且 topic 与表的一一对应关系被打破。**不建议作为主路径**；可作为「宽表分片」的特例手段（每个分片一个独立 connector + 独立 topic.prefix）。

---

## 4. `tasks.max` 与多表 whitelist 的分配规则

`JdbcSourceConnector.taskConfigs(int maxTasks)`：

```java
int numGroups = Math.min(currentTables.size(), maxTasks);
List<List<TableId>> tablesGrouped = ConnectorUtils.groupPartitions(currentTables, numGroups);
for (List<TableId> taskTables : tablesGrouped) {
  taskProps.put(JdbcSourceTaskConfig.TABLES_CONFIG, <逗号分隔的表名>);
}
```

- 表清单被**均分**给 task，**一张表只属于一个 task**（`groupPartitions` 是不相交切分）。
- **一张表不能被多个 task 并行读**。单表想并行只能由 DBX 自己切：每个分片一个独立 connector（`query` + 不同 `topic.prefix`，或 `table.whitelist=单表` + `query.suffix` 加范围条件）。
- `tasks.max > 表数` 时多余的 task 不产生（`numGroups` 被 min 掉）。
- 表清单为空时会生成 1 个「空 task」占位；若 `tables.fetched=true` 且仍无表，task 启动即抛 `ConfigException`（"Task is being killed because it was not assigned a table nor a query"）→ connector 进 FAILED。**这是 DBX 需要区分的失败态：白名单写错 vs 真的没表。**
- 表清单由后台 `TableMonitorThread` 每 `table.poll.interval.ms`（默认 **60000**）刷新；表变化会触发 task 重配置。离线迁移建议把它调大，避免迁移中途无谓的 task 重启。

来源：<https://github.com/confluentinc/kafka-connect-jdbc/blob/v10.9.6/src/main/java/io/confluent/connect/jdbc/JdbcSourceConnector.java>

---

## 5. topic 命名规则与定制程度

`BulkTableQuerier.extractRecord()`（`TimestampIncrementingTableQuerier` 同构）：

```java
case TABLE:
  String name = tableId.tableName();      // 只取表名，不含 schema/catalog
  topic = topicPrefix + name;
  break;
case QUERY:
  topic = topicPrefix;                    // 整个 query 一个 topic
```

- **规则**：`topic.prefix` + **裸表名**（不含库名/schema）。`topic.prefix` 是纯字符串拼接，**不会自动加分隔符**，要 `mig.users` 必须写 `topic.prefix=mig.`。
- 因为只取 `tableName()`，**不同 schema 下的同名表会撞到同一个 topic** —— DBX 的装箱器必须保证一箱内表名不重复，或为每个 schema 用独立 connector + 独立 prefix。
- 唯一定制手段是 SMT，标准做法是 `org.apache.kafka.connect.transforms.RegexRouter`：
  ```
  transforms=route
  transforms.route.type=org.apache.kafka.connect.transforms.RegexRouter
  transforms.route.regex=(.*)
  transforms.route.replacement=dbx_$1
  ```
  文档：<https://docs.confluent.io/platform/current/connect/transforms/regexrouter.html>
- Sink 侧对应地由 `topics` / `topics.regex` 订阅，表名由 JDBC Sink 的 `table.name.format` 决定。

---

## 6. 删除 connector 时正在进行的读取如何终止

**平台侧动作**：`DELETE /connectors/{name}`。
- Apache Kafka 官方 Connect 用户指南原文：
  > `DELETE /connectors/{name}` - delete a connector, halting all tasks and deleting its configuration
- 文档：<https://kafka.apache.org/40/kafka-connect/user-guide/> / <https://docs.confluent.io/platform/current/connect/references/restapi.html>

**连接器侧发生了什么**（v10.9.6）：

1. `JdbcSourceTask.stop()` → `engine.stop()`，然后 `engine.awaitTermination(ENGINE_SHUTDOWN_TIMEOUT 秒)`；超时只打一条 WARN，不阻塞删除。
2. `TableQuerierProcessor.process()` 的循环条件是 `destination.isRunning()`，会在**当前行处**跳出；正在进行的 `SELECT` 不会被主动 cancel，但 ResultSet 被关闭。
3. `tableQuerierProcessor.shutdown()` 把队列里每个 querier 都 `reset(now, resetOffset=true)`，**不再重新入队**。
4. `TableQuerier.reset()` → `closeResultSetQuietly()` + `closeStatementQuietly()` + `releaseLocksQuietly()`（后者执行 `db.commit()`，**显式提交/释放只读事务**）。
5. `JdbcSourceTask.closeResources()` 关闭 `CachedConnectionProvider` 与 dialect。

**脏状态评估**：

| 位置 | 是否留下脏状态 | 说明 |
|---|---|---|
| MySQL 源库 | 否 | 只读事务被 commit 释放；连接池被关闭。极端情况下（进程被 kill -9）连接由 MySQL `wait_timeout` 回收 |
| Kafka topic | **是（部分数据）** | 已产出的记录不会回滚。中途删除后重跑必须先删 topic 或换新 topic，否则 Sink 侧重复写入 |
| Connect offset topic | bulk：无记录；incrementing：**保留** | offset 以 connector 名为 key。同名重建 connector 会**继承旧 offset**，导致「以为重跑实际上续跑」。DBX 必须在重跑前用 `DELETE /connectors/{name}/offsets` 或换 connector 名 |
| Connect config topic | 否 | DELETE 会清掉配置 |

**重跑前清 offset 的正确顺序**（Kafka 官方用户指南原文：*"the offsets for a connector can be only modified via the offsets management endpoints if it is in the stopped state"*）：

```
PUT    /connectors/{name}/stop        # 进入 STOPPED（不是 PAUSED）
DELETE /connectors/{name}/offsets     # 必须是 STOPPED，否则 400
DELETE /connectors/{name}             # 再删掉
```

**注意 `pause` 与 `stop` 的区别**：`PUT /connectors/{name}/pause` 保留 task 占用的资源（JDBC 连接不释放），`PUT /connectors/{name}/stop` 才会关闭 task 并释放资源。**DBX 若要「暂停一箱」应该用 `stop` 而不是 `pause`**，否则源库连接会一直被占着。

- KIP-875：<https://cwiki.apache.org/confluence/display/KAFKA/KIP-875%3A+First-class+offsets+support+in+Kafka+Connect>
- 官方端点说明：<https://kafka.apache.org/40/kafka-connect/user-guide/>

---

## 7. 平台可用的完成判定信号清单

> 这一节是给下游决策票（#10 / #13 / #19）用的。按可用性排序。

### 信号 A：Kafka topic 的 end offset vs 源表 COUNT(*) —— **推荐主信号**

| 项 | 内容 |
|---|---|
| 取得方式 | Java AdminClient / `KafkaConsumer.endOffsets(partitions)`；或 `kafka-run-class kafka.tools.GetOffsetShell --topic t --time -1`。源侧 `SELECT COUNT(*) FROM t`（迁移前抓一次即可，离线库静态） |
| 判定 | `sum(endOffset - beginningOffset) over partitions == rowCount` |
| 可靠性 | **高**，前提是：(1) topic 未开启 compaction（用 `cleanup.policy=delete` 且 retention 足够长）；(2) 无事务标记膨胀（Connect source 默认非 EOS 时无 control record；若开 `exactly.once.source.support`，每个事务会插入 control record，end offset 会**大于**记录数，此时必须改用 `read_committed` consumer 计数或直接比对 Sink 侧行数） |
| 延迟 | 秒级；受 producer `linger.ms` / `batch.size` 与 Connect 的 offset flush 影响，通常 < 1s |
| 失效场景 | 源表迁移期间被写入（离线场景不应发生，需在方案里锁定）；topic 被提前清理；bulk 模式下 task 重启导致**重复投递** → end offset 大于行数，此时相等判定会永远不成立，需退化为「>= rowCount 且停滞」 |

### 信号 B：end offset 停滞检测（配合 A 使用）

| 项 | 内容 |
|---|---|
| 取得方式 | 同 A，周期性采样 end offset |
| 判定 | 连续 N 个周期（如 3 次 × 10s）end offset 无变化，且 connector 状态为 RUNNING、无 FAILED task |
| 可靠性 | 中。**只能作为辅助**：慢查询、大表长扫描、源库锁等待都会造成假停滞 |
| 延迟 | N × 采样周期 |
| 失效场景 | 大表首轮扫描期间 producer 持续输出，不会误判；但**网络分区/源库 hang 时会误判为完成** —— 必须与 A 的行数比对做「与」逻辑 |

### 信号 C：source offset（`GET /connectors/{name}/offsets`）

| 项 | 内容 |
|---|---|
| 取得方式 | `GET /connectors/{name}/offsets`（**Kafka 3.5.0 起**，KIP-875 Part 1）。返回 `{"offsets":[{"partition":{"table":"users"},"offset":{"incrementing":12345}}]}` |
| 可靠性 | **bulk 模式：完全不可用**（`BulkTableQuerier` 传 `null` offset，源码确证）。**incrementing/timestamp 模式：高** |
| 延迟 | 受 `offset.flush.interval.ms`（worker 级，Kafka 官方文档 **默认 60000 (1 minute)**）影响 —— **默认最慢 60 秒**。DBX 应把它调到 5000~10000ms |
| 判定 | offset 的 `incrementing` 值 >= 迁移前抓取的 `MAX(pk)` |
| 失效场景 | bulk 模式；offset flush 未触发（task 刚起）；旧版 Kafka（< 3.5）没有该端点，只能自己去读 `connect-offsets` topic |
| 来源 | <https://cwiki.apache.org/confluence/display/KAFKA/KIP-875%3A+First-class+offsets+support+in+Kafka+Connect> |

### 信号 D：connector / task 状态（`GET /connectors/{name}/status`）

| 项 | 内容 |
|---|---|
| 取得方式 | `GET /connectors/{name}/status` → `{"connector":{"state":"RUNNING"},"tasks":[{"id":0,"state":"RUNNING"}]}` |
| 可靠性 | **不能用于判定完成**。JDBC source 跑完全表后状态仍是 RUNNING —— 没有 SUCCEEDED / COMPLETED 状态。**只能用于判定失败**（FAILED + `trace` 字段拿堆栈） |
| 延迟 | 即时 |
| 用途 | 异常兜底：task FAILED 时立刻终止本箱、上报错误；UNASSIGNED 表示 rebalance 中 |
| 来源 | <https://docs.confluent.io/platform/current/connect/references/restapi.html#get--connectors-(string-name)-status> |

### 信号 E：JMX 指标 `source-record-poll-total` / `source-record-write-total`

| 项 | 内容 |
|---|---|
| 取得方式 | MBean 组 `source-task-metrics`（`ConnectMetricsRegistry.SOURCE_TASK_GROUP_NAME = "source-task-metrics"`），tag 为 `connector` + `task`，即 `kafka.connect:type=source-task-metrics,connector=<name>,task=<id>` |
| 关键属性 | `source-record-poll-total`（"The total number of records produced/polled (before transformation) by this task"）、`source-record-write-total`（"...written to Kafka **since the task was last restarted**"）、`source-record-poll-rate`、`source-record-write-rate`、`source-record-active-count`（"records that have been produced by this task but not yet completely written to Kafka"）、`poll-batch-avg-time-ms` |
| 判定 | `source-record-write-rate` 归零持续 N 秒 **且** `source-record-active-count == 0`（说明没有在途未落 Kafka 的记录）→ 疑似跑完 |
| 可靠性 | 中。`*-total` 在 `AbstractWorkerSourceTask.SourceTaskMetricsGroup` 里注册为 `new CumulativeSum()`，**task 重启后归零**，不能跨重启做累计比对。速率归零同样有慢查询假阳性 |
| 延迟 | 指标窗口 `metrics.sample.window.ms`，Kafka 官方文档 **默认 30000 (30 seconds)** → **速率类指标最长有 30s 滞后** |
| 失效场景 | 需要 Connect 进程开 JMX 端口（DBX 部署时要显式配 `JMX_PORT` / `KAFKA_JMX_OPTS`），离线客户环境可能不允许；多 task 时要按 task 聚合 |
| 来源 | <https://github.com/apache/kafka/blob/4.0/connect/runtime/src/main/java/org/apache/kafka/connect/runtime/ConnectMetricsRegistry.java> / `AbstractWorkerSourceTask.java` / <https://kafka.apache.org/documentation/#connect_monitoring> |

`source-record-active-count` 值得单独强调：它是 DBX 唯一能拿到的「在途记录数」指标，判「Source 真的排空了」时应作为必要条件之一。

### 信号 F：Sink 侧目标表行数（PostgreSQL `COUNT(*)`）

| 项 | 内容 |
|---|---|
| 取得方式 | 在目标 PG 上 `SELECT COUNT(*) FROM <target>` |
| 可靠性 | **最高 —— 这才是业务上真正关心的「迁完了」**。它同时覆盖了 source 和 sink 两段 |
| 延迟 | 受 Sink 的 `batch.size` / `flush` 与 consumer lag 影响，通常几秒 |
| 失效场景 | Sink 用 `insert.mode=upsert` 时重复记录被合并，行数会「正确」但掩盖了 source 侧重复；`COUNT(*)` 在超大表上本身耗时 |
| 建议 | 作为**最终验收信号**（迁移完成后的对账），不作为「何时删 connector」的触发信号 |

### 信号 G：consumer lag（Sink 侧读完 topic）

| 项 | 内容 |
|---|---|
| 取得方式 | `kafka-consumer-groups --describe --group connect-<sink-connector-name>`，或 AdminClient `listConsumerGroupOffsets` + `endOffsets` |
| 判定 | lag == 0 → Sink 已消费完所有已产出记录 |
| 可靠性 | 高，用于**判定「Sink 追平了 Source」**，即停 Sink connector 的时机 |
| 延迟 | 受 Sink 的 offset 提交周期影响，秒级 |
| 失效场景 | lag=0 只说明「已产出的都消费了」，不说明「Source 产出完了」。**必须先确认 Source 完成（A），再等 lag=0（G），最后停 Sink** |

### 推荐组合（给装箱调度器）

```
1. 迁移前：对箱内每表 SELECT COUNT(*) 与 MAX(pk)，落库为期望值。
2. 起 Sink connector → 起 Source connector。
3. 轮询循环（每 5~10s）：
   a. GET /connectors/{src}/status 与 /{sink}/status → 任一 FAILED 则中止本箱。
   b. 每表 endOffset - beginningOffset 与期望行数比对（信号 A）；
      incrementing 模式下同时校验 offset >= MAX(pk)（信号 C）。
   c. 全部达标 → 判定 Source 完成。
4. DELETE /connectors/{src}。
5. 等 Sink consumer group lag == 0（信号 G）→ DELETE /connectors/{sink}。
6. 对账：目标 PG COUNT(*) 与期望行数比对（信号 F）。不一致 → 本箱失败。
7. 清理：删 topic；若可能重跑，先 PUT /connectors/{name}/stop + DELETE /connectors/{name}/offsets。
```

关键 worker 级配置（DBX 必须显式设置，不能用默认值）：

| 配置 | 默认 | DBX 建议 | 理由 |
|---|---|---|---|
| `offset.flush.interval.ms` | 60000 | 5000 | 否则 source offset 信号最慢 60s 才可见 |
| `poll.interval.ms`（connector 级） | 5000 | 很大值（如 86400000） | bulk 模式下避免第二轮全量重读 |
| `table.poll.interval.ms` | 60000 | 很大值 | 避免迁移中途表变更触发 task 重配置 |
| `batch.max.rows` | 1000 | 按行宽调大 | 吞吐 |

---

## 8. 版本差异

| 版本 | 差异 |
|---|---|
| **v10.9.x**（当前基线，含 10.9.6） | 引入 `TableQuerierProcessor` + `RecordQueue` 引擎：查询在**独立后台线程**里跑，`JdbcSourceTask.poll()` 只从队列取批。新增 `query.retry.attempts`、`query.masked`、`table.include.list` / `table.exclude.list`（regex 版白名单）、`timestamp.column.mapping` / `incrementing.column.mapping`（按表正则指定列） |
| **v10.7.x 及更早** | `JdbcSourceTask.poll()` 自己跑 `while` 循环执行查询并组装批次，没有后台线程。**外部可观测行为（bulk 循环、offset 语义、topic 命名、task 分配）与 10.9.x 一致** |
| 全版本一致 | `BulkTableQuerier` 传 `null` offset；`topicPrefix + tableName`；query 模式单 task 且与白名单互斥；`groupPartitions` 不相交切表 |

关于 Kafka 侧（KIP-875 分两批落地，DBX 的 Kafka 版本下限由此确定）：

| 能力 | 最低 Kafka 版本 |
|---|---|
| `GET /connectors/{name}/offsets`、`PUT /connectors/{name}/stop`、STOPPED 状态 | **3.5.0** |
| `PATCH /connectors/{name}/offsets`、`DELETE /connectors/{name}/offsets` | **3.6.0** |
| `POST /connectors` 带 `initial_state`（STOPPED/PAUSED/RUNNING）| 3.6.0（KIP-980）|

**建议 DBX 把 Kafka 版本下限定在 3.6.0**：低于此则「重跑前清 offset」只能直接往 `connect-offsets` topic 写 tombstone，脆弱且需要绕过 Connect 的内部协议。

- Kafka 3.6.0 发布公告：<https://kafka.apache.org/blog/2023/10/10/apache-kafka-3.6.0-release-announcement/>
- KIP-980（以 STOPPED 状态创建 connector）：<https://cwiki.apache.org/confluence/display/KAFKA/KIP-980%3A+Allow+creating+connectors+in+a+stopped+state>
- Connect REST API 全量端点（Apache）：<https://kafka.apache.org/40/kafka-connect/user-guide/>
- Confluent 版 REST API 参考：<https://docs.confluent.io/platform/current/connect/references/restapi.html>
- JDBC Source 配置项官方文档：<https://docs.confluent.io/kafka-connectors/jdbc/current/source-connector/source_config_options.html>

---

## 9. 对 DBX 的直接结论

1. **「跑完即停」由平台实现，不存在连接器内建方案。** 装箱调度器 = 「起 connector → 轮询完成信号 → DELETE connector」的状态机。
2. **模式选择二分法**：表有单调递增非空数值主键 → `mode=incrementing`（offset 可见、可续跑）；否则 → `mode=bulk` + 超大 `poll.interval.ms`（offset 不可见，只能靠 topic end offset 判定，且必须容忍重启重复）。
3. **并行度上限 = 箱内表数**，单表无法被多 task 并行。要提升单表吞吐只能由 DBX 自己按主键范围切成多个 connector。
4. **topic 命名要防撞**：只取裸表名，跨 schema 同名表必须隔离到不同箱或不同 prefix。
5. **重跑不干净**：重跑前必须删 topic + 清 source offset（或换 connector 名），否则会出现「以为全量实际续跑」和 Sink 侧重复。
