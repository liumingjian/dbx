# 单机 Kafka + Connect 的大消息、资源与运维包络

> 研究票：GitHub issue #6（parent #1）。结论用于支撑「装箱调度器规格」与「大字段端到端配置规格」两张决策票，以及方案书的「部署与运维要求」章节。
> 场景约束：DBX v1 单机 Docker Compose，MySQL 8.0 → PostgreSQL 15 离线迁移，单行/单字段上限 **20MB**，Avro + Schema Registry，装箱调度导致 connector 频繁创建/删除。
> 版本基线：Apache Kafka ≥ 3.6.0（沿用 #3 的结论），配置默认值取自 Kafka 4.x / Confluent Platform current 官方参考。

## 目录

1. [20MB 大消息全链路配置清单](#1-20mb-大消息全链路配置清单)
2. [Avro / Schema Registry 的额外上限](#2-avro--schema-registry-的额外上限)
3. [单机资源包络](#3-单机资源包络)
4. [connector 生命周期开销](#4-connector-生命周期开销)
5. [topic 与磁盘管理](#5-topic-与磁盘管理)
6. [错误信息可取得的接口](#6-错误信息可取得的接口)
7. [可直接抄的 Docker Compose 片段](#7-可直接抄的-docker-compose-片段)
8. [待验证项](#8-待验证项)

---

## 1. 20MB 大消息全链路配置清单

### 1.1 先算「该设多少」：协议与 schema 开销余量

`message.max.bytes` 的官方定义是 **"The largest record batch size allowed by Kafka (after compression if compression is enabled). This can be set per topic with the topic level `max.message.bytes` config."**（[Kafka broker configs](https://kafka.apache.org/41/configuration/broker-configs/)）。注意三点，都会吃掉余量：

1. **它管的是 record batch，不是单条 record。** 默认值 `1048588` 而不是 `1048576`，多出来的 12 字节就是 batch header 的余量提示。一个 batch 的固定头部约 61 字节，每条 record 还有 varint 长度、timestamp delta、offset delta、header 数组等若干十字节。
2. **Confluent 的 Avro wire format 每条消息额外 5 字节**：1 字节 magic byte（0）+ 4 字节 big-endian schema ID，之后才是 Avro binary 载荷（[Wire format](https://docs.confluent.io/platform/current/schema-registry/fundamentals/serdes-develop/index.html#wire-format)）。
3. **一行不止一个大字段的其它列**：题目约束是「单行/单字段 20MB」，实际 record = 20MB blob + 其余列 + Avro 框架 + Kafka batch 框架 + Connect header。

**结论：把 20MB（20 971 520 B）业务数据按 ~1.25x 放大，统一取 `26214400`（25 MiB）作为全链路上限值。** 这留出约 4MB 绝对余量，足以覆盖上面 3 项之和（实际开销是百字节量级，余量主要是给「同一行里还有别的列」用的）。若预检阶段能保证「整行序列化后 ≤ 20MB」而不只是单字段，可下调到 `23068672`（22 MiB）。

> 陷阱：`compression.type` 生效在 batch 层，broker 校验的是**压缩后**的大小。所以启用压缩后 20MB 文本可能只剩 2MB，broker 会放行；但**不要**因此把 broker 上限调回小值——不可压缩的二进制（已压缩图片、加密 blob）会原样通过，仍需 25MiB 的上限。压缩只降磁盘/网络，不降上限要求。

### 1.2 逐项配置表（含漏配后果）

| 层 | 配置项 | 默认值 | DBX 建议值 | 漏配会在哪一环炸 / 报什么 |
|---|---|---|---|---|
| broker | `message.max.bytes` | `1048588` | `26214400` | 生产端收到 broker 返回的 `MESSAGE_TOO_LARGE`（error code 10），客户端抛 `org.apache.kafka.common.errors.RecordTooLargeException: The request included a message larger than the max message size the server will accept.`；Connect source task 直接 FAILED，`trace` 里就是这行 |
| broker | `socket.request.max.bytes` | `104857600`（100 MiB） | 保持默认即可 | 只有当单个 produce 请求（可含多条 record）> 100MiB 才炸。届时 broker **不返回错误码而是直接断开连接**，broker 日志 WARN `org.apache.kafka.common.network.InvalidReceiveException: Invalid receive (size = N larger than 104857600)`，客户端只看到 `Connection to node -1 ... disconnected` —— 最难排查的一档。DBX 用 25MiB 上限 + 小 `batch.size` 时不会触发 |
| broker | `replica.fetch.max.bytes` | `1048576` | `26214400`（防御性） | **单机 RF=1 时不生效**（没有 follower）。但只要将来加副本或客户对接自有多节点集群，follower 拉不动大 batch 会导致 ISR 收缩、`under-replicated partitions` 报警。文档保证「first record batch 仍会返回」，所以现代版本不会永久卡死，但会退化成一次一条、复制严重滞后。**建议无条件设上，成本为零** |
| topic | `max.message.bytes` | `1048588` | `26214400` | 与 broker 项同错。**优先级高于 broker 级**：topic 级一旦显式设置就覆盖 broker 值。DBX 每表一个 topic 且由平台显式建 topic，**必须在建 topic 时带上这个 config**，不能只靠 broker 默认（否则一旦客户用自有 Kafka、broker 侧没改，就全炸） |
| producer | `max.request.size` | `1048576` | `26214400` | 客户端本地校验，`send()` 同步抛 `RecordTooLargeException: The message is <N> bytes when serialized which is larger than <max>, which is the value of the max.request.size configuration.`（旧版措辞：`...larger than the maximum request size you have configured with the max.request.size configuration.`）。**这条根本没到 broker**，所以 topic 配置对不对都没用 |
| producer | `buffer.memory` | `33554432`（32 MiB） | `134217728`（128 MiB） | 若 `buffer.memory` < 单条记录大小，`send()` 抛 `RecordTooLargeException: The message is <N> bytes when serialized which is larger than the total memory buffer you have configured with the buffer.memory configuration.`。默认 32MiB > 25MiB 勉强够，但只能容纳 ~1 条在途大消息，吞吐塌陷且极易在 `max.block.ms`（默认 60s）后抛 `TimeoutException: Failed to allocate memory within the configured max blocking time`。**必须调大** |
| producer | `compression.type` | `none` | `zstd`（或 `lz4`） | 不是正确性问题，是磁盘/网络问题。见 §5 磁盘估算。`zstd` 压缩率最高、CPU 最贵；`lz4` 折中。**注意 zstd 需要 broker ≥ 2.1**，我们基线 3.6 没问题 |
| producer | `batch.size` | `16384` | `16384`（保持默认，勿调大） | 见 §3：单条已 20MB，batch 再攒会直接把 Connect worker 堆打爆。batch.size 小于单条大小时，该条自成一批发送，是我们想要的行为 |
| consumer | `max.partition.fetch.bytes` | `1048576` | `26214400` | **不会硬失败**。官方文档明确：*"if the first record batch in the first non-empty partition of the fetch is larger than this limit, the batch will still be returned to ensure that the consumer can make progress"*（[Consumer configs](https://kafka.apache.org/41/configuration/consumer-configs/)）。漏配的后果是**每次 fetch 只回一条 → 吞吐劣化 + 往返次数暴增**，表现为 sink 侧「慢得离谱但不报错」，是最容易被误判成「Kafka 有问题」的场景 |
| consumer | `fetch.max.bytes` | `52428800`（50 MiB） | `52428800`（默认够用） | 同上「保证进度」语义，不硬失败。默认 50MiB > 25MiB，无需改。若把它调到 < 单条大小，同样只是退化为一次一条 |
| consumer | `max.poll.records` | `500` | `1`~`5` | 漏配的后果是**堆 OOM**：500 × 20MB = 10GB。见 §3 |
| Connect | `errors.deadletterqueue.topic.name` 对应的 DLQ topic | — | DLQ topic 也要设 `max.message.bytes=26214400` | **易漏**：主 topic 配好了，坏消息往 DLQ 写时被 broker 拒，sink task 反而以 `RecordTooLargeException` 失败——本来只是想「跳过一条坏数据」，结果整个 task 挂掉 |
| Connect | 内部 topic（`connect-offsets` / `connect-configs` / `connect-status`） | `1048588` | 保持默认 | bulk 模式不写 source offset（见 #3），offset 记录本身很小，无需放大 |

### 1.3 Connect worker 怎么把这些下发给 connector 的客户端

有两个作用域，DBX 两个都要用：

**A. worker 级（兜底，作用于该 worker 上所有 connector）**
worker 配置里以 `producer.` / `consumer.` 为前缀的键，会传给 worker 为 source connector 创建的 producer 和为 sink connector 创建的 consumer。Docker 镜像（`confluentinc/cp-kafka-connect`）的环境变量映射规则是 `CONNECT_` + 全大写 + `.`→`_`，即：

```
CONNECT_PRODUCER_MAX_REQUEST_SIZE: 26214400
CONNECT_PRODUCER_BUFFER_MEMORY: 134217728
CONNECT_PRODUCER_COMPRESSION_TYPE: zstd
CONNECT_CONSUMER_MAX_PARTITION_FETCH_BYTES: 26214400
CONNECT_CONSUMER_MAX_POLL_RECORDS: 1
```

**B. connector 级（每个 connector 单独覆盖）**
自 AK 2.3.0（[KIP-458](https://cwiki.apache.org/confluence/display/KAFKA/KIP-458:+Connector+Client+Config+Override+Policy)）起，connector 配置里可用 `producer.override.*`（source）、`consumer.override.*`（sink）、`admin.override.*` 覆盖 worker 派生出来的客户端配置。是否允许由 worker 配置 `connector.client.config.override.policy` 控制，取值 `All` / `Principal` / `None` 或自定义类全名。

**默认值有版本分水岭**：KIP-458 引入时默认 `None`（不允许任何覆盖）；[KIP-722](https://cwiki.apache.org/confluence/display/KAFKA/KIP-722:+Enable+connector+client+overrides+by+default) 在 **AK 3.0.0** 把默认改成 `All`。我们基线是 3.6.0，默认已是 `All`。但**对接客户已有 Connect 集群时不能假设**——客户可能显式设成 `None`。

> 落地建议：DBX 建 connector 时**同时**写 `producer.override.max.request.size` 等；如果客户 worker 是 `None`，REST 创建/校验阶段就会因策略违规**拒绝启动 connector**（policy 在配置 client 前被调用，也在 `PUT /connectors/{name}/config/validate` 时被调用）。这是一个可以在预检阶段主动探测的失败点：先 `validate` 一次，看是否报 policy 违规，再决定是「靠 worker 级兜底」还是「报错让客户改 worker 配置」。
>
> 另一个坑：Connect 框架**不允许把 producer/consumer 配置项 unset 或设为 null**，只能覆盖成新值。

**admin client 也要管**：source connector 启用自动建 topic 时、sink connector 启用 DLQ 时会用 admin client（对应 `admin.override.*`）。DBX 自己显式建 topic（可控地带上 `max.message.bytes`），因此建议 **关闭 Connect 的自动建 topic**，避免 topic 用 broker 默认的 1MB 上限被创建出来——这正是「主 topic 配置漏了」最常见的成因。

---

## 2. Avro / Schema Registry 的额外上限

- **Schema Registry 不对消息大小设限，只对 schema 文本设限。** 自建 Confluent Platform 上「there are no such limits on schemas」；Confluent Cloud 才有 1MB 的**单个 schema 文档**大小上限（可用 schema references 规避）。DBX 一张表的 Avro schema 是几十列的字段声明，KB 量级，远够。
- **大 `bytes` 字段本身没有 Avro 层上限。** Avro binary 编码 `bytes` = zigzag varint 长度 + 原始字节，20MB 只多几个字节的长度前缀。真正的天花板全部来自 Kafka（§1）与 JVM 堆（§3）。
- **每条消息 +5 字节**：Confluent wire format（magic byte + 4 字节 schema ID）。
- **内存放大才是实际风险**：Avro 序列化 `bytes` 时通常经历「JDBC 读出 byte[] → Avro 对象持有 → 序列化到输出流 → producer 的 batch buffer」，一条 20MB 记录在 Connect worker 堆里同时存在 **2~4 份拷贝**是常态。这直接决定了 §3 的堆大小和 `max.poll.records` 取值。
- Schema Registry 的 REST 有请求体上限（Jetty 层），但我们只发 schema 文本，不发数据，无影响。

---

## 3. 单机资源包络

### 3.1 官方数字 vs DBX 单机形态

官方给的是**生产集群**数字，不能照抄到「随用随起的单机迁移工具」。下表左列是官方原话，右列是 DBX 的取值与理由。

| 组件 | Confluent 官方生产建议 | DBX 单机取值 | 说明 |
|---|---|---|---|
| Kafka broker（KRaft combined） | 堆：*"Kafka uses heap space very carefully and does not require setting heap sizes more than 6 GB"*，示例 `-Xms6g -Xmx6g`；RAM 64GB；24 核；12×1TB RAID10（[deployment](https://docs.confluent.io/platform/current/kafka/deployment.html)） | 堆 **2GB**（`-Xms2g -Xmx2g`），最小 1GB | Kafka 的堆几乎不随数据量增长（数据走 page cache + 零拷贝），**堆主要被在途的 produce/fetch 请求缓冲占用**。20MB 消息 + 少量并发 → 2GB 绰绰有余。留给宿主机的空闲内存越多，page cache 越大，写入越不落盘等待 |
| KRaft controller | 4GB RAM / 64GB SSD / 4 核 / 3–5 节点 | 与 broker **合并进程**（`process.roles=broker,controller`），不额外分配 | 单机只能 1 个 controller，`KAFKA_PROCESS_ROLES: broker,controller` + `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093` |
| Connect worker | 堆 **0.5–4 GB**；RAM 8–32GB；4–8 核；磁盘 *"50 GB of disk space per worker is sufficient"*（仅装程序和日志）（[cluster sizing](https://docs.confluent.io/platform/current/connect/references/connect-cluster-sizing.html)） | 堆 **4GB**（取官方区间上限），最小 2GB | **这是整套里最吃堆的组件**，因为 20MB 记录完全驻留在堆里。官方明确 *"Memory requirements depend on the connector type and workload, especially for connectors that buffer large transactions or handle large messages"* |
| Schema Registry | 官方系统需求页未给具体堆数字，只说明用 `SCHEMA_REGISTRY_HEAP_OPTS` 设置 | 堆 **512MB** | SR 只存 schema 文本 + 一个 `_schemas` topic 的内存索引，负载与表数量成正比而非数据量。几百张表 → 几 MB 级 |
| 合计 | — | **约 8GB 内存起步，推荐 12–16GB 宿主机**；4 核起步 | 8GB = 2（Kafka 堆）+4（Connect 堆）+0.5（SR 堆）+ JVM 非堆/元空间/线程栈（每个 JVM 再加 0.3–0.7GB）+ 平台自身 + 留给 page cache |

### 3.2 20MB 消息对 Connect worker 堆的具体压力

这是必须算清楚的一笔账，否则默认配置**必然 OOM**。

**Sink 侧（消费端）——最危险**：
`max.poll.records` 默认 **500**。一次 `poll()` 返回 500 条 × 20MB = **10 GB**，任何合理堆都直接 `OutOfMemoryError: Java heap space`，而且是在 worker 层挂掉（不只是 task 挂）。
且这些记录在堆中会被放大：consumer fetch buffer（原始字节）→ Avro 反序列化对象 → Connect `SinkRecord` → JDBC sink 的 batch。**同一条记录同时存在 2–4 份拷贝**是常态。

推荐取值：
```
consumer.max.poll.records = 1        # 大字段表所在的箱
consumer.max.partition.fetch.bytes = 26214400
consumer.fetch.max.bytes = 52428800  # 默认
```
`max.poll.records=1` 时，单 task 峰值 ≈ 1 × 20MB × 4 ≈ 80MB；worker 上 8 个 task 并发 ≈ 640MB，加上 fetch buffer（每个 task 最多缓存 `fetch.max.bytes` = 50MB → 8×50MB=400MB），合计 ~1GB，4GB 堆有足够安全边际。

> **装箱调度的直接推论**：`max.poll.records` 是 **consumer 级**配置，通过 `consumer.override.max.poll.records` 逐 connector 下发。所以**不必**让所有箱都退化到 1 ——「含大字段的表」和「全是小行的表」应当分箱，前者 `max.poll.records=1`，后者可以保留 100~500，吞吐差一个数量级。**这是装箱策略的一条硬约束，建议写进装箱调度器规格。**

**Source 侧（生产端）**：
- `batch.size` 默认 16384（16KB）。单条 20MB 远大于它，producer 会让该条**自成一批**发送 —— 这正是我们要的。**不要调大 `batch.size`**：调到比如 1MB 也不会让 20MB 消息合批（一批只能装下一条），只是白白多占堆。
- `buffer.memory` 建议 128MB（§1.2）。producer 堆内还有压缩缓冲区（zstd 需要与消息同量级的临时空间）。
- `max.in.flight.requests.per.connection` 默认 5 → 最多 5 × 25MB = 125MB 在途未确认数据滞留堆中。若堆吃紧可降到 **1**（同时也保证单分区严格有序，见 §5.4）。

**副作用警告**：`max.poll.records=1` + 单条处理慢时，要留意 `max.poll.interval.ms`（默认 300000 = 5 分钟）。若 JDBC sink 写一条 20MB 记录到 PostgreSQL 超过 5 分钟，consumer 会被踢出组触发 rebalance，表现为「任务反复重启且没有明显错误」。DBX 建议显式设 `consumer.override.max.poll.interval.ms=900000`（15 分钟）。

### 3.3 磁盘

- Kafka broker：见 §5 的估算公式，**这是唯一随迁移数据量线性增长的磁盘需求**。
- Connect worker / Schema Registry：官方 *"50 GB of disk space per worker is sufficient"*，纯粹是程序 + 日志。DBX 单机可给 **10GB**（容器镜像 + 日志轮转），但要给 worker 日志配轮转，否则大消息的 `errors.log.include.messages=true` 会瞬间写爆磁盘（见 §6）。

---

## 4. connector 生命周期开销

装箱调度会**频繁创建和删除 connector**，所以这一节决定了「一次迁移能开多少箱、换箱要等多久」。

### 4.1 standalone vs distributed

| 维度 | standalone | distributed |
|---|---|---|
| connector 配置来源 | 启动时命令行传 `.properties` 文件 | REST API 提交，存 `config.storage.topic` |
| source offset 存储 | 本地文件 `offset.storage.file.filename` | Kafka topic（`connect-offsets`） |
| 状态存储 | 无（进程内） | `status.storage.topic`，`GET /status` 从这里读 |
| 增删 connector 是否 rebalance | **否**（没有 group，直接起停 task 线程） | **是**（每次都走 group coordination） |
| REST API | 现代版本也有，但配置改动不通过它落盘 | 唯一入口 |
| 容错 | 无 | worker 挂了任务会重分配 |

**DBX 该选哪个？** 表面看 standalone 更贴合「单机、频繁增删、不需要容错」，且**没有 rebalance 开销**。但：

1. **`DELETE /connectors/{name}/offsets` 这类 offset 管理 REST 端点只在 distributed 下有意义**（standalone 的 offset 在本地文件里）。#3 的重跑流程 `PUT /stop` → `DELETE /offsets` → `DELETE /connectors` 依赖它。
2. standalone 不支持通过 REST 动态新增 connector 并持久化 —— 装箱调度器要动态开箱，就得重启 worker。
3. 对接客户已有 Connect 集群时，客户几乎必然是 distributed。

**结论：用 distributed（单节点）**，并接受 rebalance 成本 —— 下面说明这个成本其实很小。

> `group.id`、`config.storage.topic`、`offset.storage.topic`、`status.storage.topic` 在单节点下的副本因子必须设成 **1**，否则 worker 启动时会因「副本数不足」建 topic 失败。这是单机部署最常见的启动失败原因。

### 4.2 单节点 distributed 下增删 connector 会 rebalance 吗？会。代价多大？

官方明确：*"When a connector is first submitted to the cluster, a rebalance is triggered between the Connect workers... This same rebalancing procedure is also used when connectors increase or decrease the number of tasks they require, when a connector's configuration is changed, or when a worker is added or removed from the group."*（[Connect Administration](https://kafka.apache.org/42/kafka-connect/administration/)）

**即使只有一个 worker 也会走 group coordination 路径**——它仍然是一个只有一个成员的 consumer group。

**但 KIP-415 的增量协作式 rebalance 把代价压到很低**：自 **AK 2.3.0** 起，*"a protocol that performs incremental cooperative rebalancing that incrementally balances the connectors and tasks across the Connect workers, **affecting only tasks that are new, to be removed, or need to move from one worker to another**"*。也就是说，新增第 N+1 个 connector **不会**停掉已经在跑的前 N 个 connector 的 task —— 这正是「装箱调度边跑边开新箱」可行的技术前提。

对比：`connect.protocol=eager`（2.3.0 之前的唯一行为）下，每次提交 connector **所有 connector 和 task 全部停掉再重分配**（stop-the-world）。**DBX 必须确保不用 eager。** 默认值有版本差异：Connect 配置参考里 `connect.protocol` 的 **Default 为 `sessioned`**（协议版本 2，含 KIP-507 内部请求签名，rebalance 行为与 cooperative 相同）；较早文档写的是默认 `compatible`（同时支持 eager 与 cooperative，优先 cooperative）。两者都走增量协作路径，**只有显式设成 `eager` 才会退化**。

**耗时量级**：官方没有给出 rebalance 耗时的数字。以下是基于协议配置默认值的**估计**，标明依据：

- rebalance 的**下界**是一次 JoinGroup + SyncGroup 往返，单机本地网络是 **毫秒级**。
- 上界由 `rebalance.timeout.ms`（默认 **60000ms**）兜底 —— 这是「worker 加入组的最大允许时间」，不是常态耗时。
- 停 task 时 worker 等 `task.shutdown.graceful.timeout.ms`（默认 **5000ms**，*"This is the total amount of time, not per task"*）。若 task 卡在写 PostgreSQL，删 connector 最多多花 5 秒。
- **实际预期：单节点、connector/task 数在两位数量级时，一次 rebalance 在 100ms ~ 数秒**。主导项不是协议往返，而是「新 task 的 `start()`（建 JDBC 连接、拉 schema）」和「旧 task 的 graceful shutdown」。
- **`scheduled.rebalance.max.delay.ms`（默认 300000ms / 5 分钟）在这里不适用** —— 它只在 **worker 离开组**时生效，用来等待 worker 回来。DBX 单 worker 场景下，只要 worker 不重启就永远不会触发。但反过来说：**worker 一旦重启，所有 task 会空转最多 5 分钟才被重新分配**，这在「迁移中途重启 Connect」时表现为「任务卡住不动」。如果 DBX 要支持快速重启恢复，建议把它调低到 `30000`（30 秒）甚至 `0`。

**副作用**：rebalance 期间 REST 有一致性窗口 —— *"If you try to restart a task while a rebalance is taking place, Connect will return a **409 (Conflict)** status code."* **装箱调度器必须对 409 做退避重试**，尤其在连续创建多个箱时。这是一条明确的实现要求。

### 4.3 单 worker 能承载多少 connector / task？瓶颈在哪

官方**没有给出硬上限**。可依据的官方数字只有两条（[cluster sizing](https://docs.confluent.io/platform/current/connect/references/connect-cluster-sizing.html)）：

- 经验法则 **"two tasks per CPU core"**；
- 堆 **0.5–4 GB**、RAM 8–32GB、**4–8 CPU cores**。

DBX 单机（假设 4–8 核、Connect 堆 4GB）由此推出的**工作上限**：

| 瓶颈 | 计算 | 得到的上限 |
|---|---|---|
| CPU（官方经验法则） | 2 tasks × 4~8 核 | **8–16 个并发 task** |
| 堆（大字段箱） | 4GB / (20MB × 4 份拷贝 + 50MB fetch buffer) ≈ 4096/130 | **~30 个 task**，但要留 GC 余量 → 实际 **≤16** |
| 每 connector 的固定开销 | 每个 connector 有独立 producer/consumer + 若干后台线程；100+ connector 会让 rebalance 计算与 `status.storage.topic` 写入变重 | 建议 **同时存活的 connector ≤ 20**（10 箱 × source+sink） |
| topic 数 | 每表一个 topic，见 §5 | 由 broker 侧分区总数限制，不是 Connect 瓶颈 |

**装箱调度器的推荐规格：同时存活 ≤ 10 个箱（≤20 个 connector），并发 task 总数 ≤ 2×CPU 核数。** 主瓶颈是 **Connect worker 堆**（大字段场景）和 **CPU 核数**（普通场景），不是 connector 数量本身。

**注意 `tasks.max` 与「每表一个 topic」的关系**：JDBC source connector 的 `tasks.max` 上限实际是「箱内表数」——一个表不会被拆到多个 task（bulk 模式下一个表就是一次全表查询）。所以**箱内表数 = 该箱可用的最大并行度**，装箱时不应该把箱做得太小。

