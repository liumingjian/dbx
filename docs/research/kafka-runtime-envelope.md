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

---

## 5. topic 与磁盘管理

### 5.1 删 topic 的代价与风险

- **`delete.topic.enable` 默认 `true`**（*"When set to true, topics can be deleted by the admin client. When set to false, deletion requests will be explicitly rejected by the broker."*）。**但仍要在 Compose 里显式写上**：客户自有集群常有人把它关掉，DBX 的清理流程会静默失败并留下垃圾 topic。这是一条应当在预检阶段探测的能力项。
- **删除是异步的、由 controller 驱动的多阶段过程**，磁盘空间和元数据的回收**滞后于 API 返回**。副本经历 `OfflineReplica` → `ReplicaDeletionStarted` → 各 broker 清数据 → `ReplicaDeletionSuccessful` → 清元数据。再叠加 topic 级 `file.delete.delay.ms`（默认 **60000ms**，*"The time to wait before deleting a file from the filesystem"*）。**推论：`DELETE /topics` 返回成功不等于磁盘已释放，DBX 的「清理后再重跑」流程不能立刻假设空间可用**，需要轮询磁盘或至少等待 1–2 分钟。
- **controller 负载与分区数成正比，不是与 topic 数成正比。** [KIP-599](https://cwiki.apache.org/confluence/display/KAFKA/KIP-599:+Throttle+Create+Topic,+Create+Partition+and+Delete+Topic+Operations) 明确：create/delete topic 是「heavy operations with a direct impact on the overall load in the Kafka Controller」，其配额单位是 **partition mutations per second**，因为「controller load is highly correlated to the number of created or deleted partitions」。删一个 1000 分区的 topic 是 1000 次 mutation。
  **对 DBX 的直接推论：单表单 topic 用少量分区（见 §5.4），一次迁移 500 张表 = 500 topic × 1 分区 = 500 次 mutation，单机 controller 可以承受；但如果每个 topic 给 12 个分区，就变成 6000 次 mutation，建 topic 和删 topic 都会明显变慢，甚至触发配额限流（客户集群若配了 `controller_mutation_rate` 配额）。**
- **风险**：分区数**只能增不能减**（[KIP-694](https://cwiki.apache.org/confluence/display/KAFKA/KIP-694:+Support+Reducing+Partitions+for+Topics) 未合入主线）。选错了只能删 topic 重建。
- **风险**：删 topic 时若还有 consumer/producer 在连，客户端会看到 `UNKNOWN_TOPIC_OR_PARTITION` 并不断重试刷日志。**正确顺序是先 `PUT /connectors/{n}/stop` 停掉 connector、`DELETE /connectors/{n}` 删掉，再删 topic**（与 #3 的重跑流程一致）。

### 5.2 短保留期能让磁盘可控吗？能，但有个坑

`retention.ms` / `retention.bytes` 的删除**以 segment 文件为单位**，官方原话：*"Retention and cleaning is always done a file at a time so a larger segment size means fewer files but less granular control over retention."*

**坑在于：活跃 segment（正在写入的那个）永远不会被删。** 默认 `segment.bytes=1073741824`（1 GiB）、`segment.ms=604800000`（7 天）。也就是说，即使你把 `retention.ms` 设成 60000（1 分钟），只要该 topic 还没写满 1GiB 也没过 7 天，segment 不会 roll，**磁盘一个字节都不会释放**。这是「我设了短保留期为什么磁盘还在涨」的标准答案。

**DBX 的正确配置**（建 topic 时逐 topic 下发）：

```
retention.ms      = 3600000     # 1 小时；离线迁移不需要长期保留
retention.bytes   = 5368709120  # 单分区 5GiB 硬上限，兜底防撑爆
segment.bytes     = 268435456   # 256MiB —— 关键：让 segment 频繁 roll，retention 才能真正回收
segment.ms        = 300000      # 5 分钟强制 roll，兜底低流量表
max.message.bytes = 26214400
cleanup.policy    = delete
```

> `segment.bytes` 必须 **≥ `max.message.bytes`**，否则一条 20MB 消息装不进一个 segment。256MiB 有 10 倍余量。
>
> **不要**把 `segment.bytes` 设得过小（比如 32MiB）：500 张表 × 大量小 segment 会产生成千上万个文件句柄，触发 broker 的 `Too many open files`。256MiB 是文件数与回收粒度的折中。

**但要清醒：短保留期不能替代磁盘容量规划。** 见下节。

### 5.3 离线迁移的磁盘需求：估算与向用户表述

DBX 是**离线全量迁移**：数据必须先落 Kafka 再出去。关键问题是「Sink 消费的速度是否跟得上 Source 生产的速度」。

**保守估算公式（向用户表述用）**：

```
Kafka 磁盘需求 ≈ 单箱内最大表的数据量 × 复制因子(1) × Avro 膨胀系数 ÷ 压缩率 × 安全系数
```

- **Avro 膨胀系数**：Avro binary 通常比行式原始数据**小**（无字段名、变长整数），对宽表约 **0.6–0.9**；但 `bytes`/`blob` 字段是 1:1 原样。含大字段的表按 **1.0** 算。
- **压缩率**：`zstd` 对文本/JSON 列常见 **3–5x**；对已压缩的二进制 blob **≈1x（无收益）**。含大字段的表按 **1.0** 算。
- **安全系数**：`1.5`（覆盖 segment 未回收的滞留、`file.delete.delay.ms` 的延迟、以及 sink 短暂落后）。

**三档表述（建议直接写进方案书）**：

| 场景 | 磁盘需求 | 说明 |
|---|---|---|
| **理想（sink 跟得上）** | ≈ 单箱内最大表大小 × 1.5 | retention 持续回收，稳态占用只是「在途窗口」 |
| **推荐规划值** | ≈ **一次迁移中最大单表数据量 × 2**，且不少于 **50GB** | 给用户的默认建议 |
| **最坏（sink 完全阻塞）** | = 该箱全部表数据量之和 | 此时 `retention.bytes` 会开始丢数据 —— **对离线迁移是数据丢失，不是背压** |

> ⚠️ **必须向用户讲清的风险**：`retention.bytes` / `retention.ms` 在 sink 落后时会**静默删除尚未消费的数据**，导致目标库缺行。DBX 应当：
> 1. 把 `retention.ms` 设得足够长（≥1 小时）而不是极短；
> 2. **监控 consumer lag 与 topic 最早 offset**，一旦发现 sink 的 committed offset < topic 的 log start offset，立即判定该表迁移失败并要求重跑（而不是让它悄悄少数据）；
> 3. 预检阶段就用上面的公式估算并与实际可用磁盘比较，**磁盘不足时直接拒绝开工**，而不是跑到一半炸。
>
> 更安全的替代方案是**用装箱的箱大小来控制磁盘**：让「单箱内表数据量之和 ≤ 可用磁盘 × 0.6」，这样即使 sink 完全不动也不会丢数据。**建议把这条写进装箱调度器规格作为硬约束。**

### 5.4 单表单 topic 用几个分区？

**推荐：1 个分区（含大字段表/需要严格顺序的表必须为 1），普通表可用 2–4。**

依据与权衡：

- **顺序**：官方 *"Messages within a partition are always delivered in order to the consumer."* —— **顺序保证只在分区内成立**。多分区 + 多 task 并行写 PostgreSQL 时，同一张表的行到达顺序不确定。对 bulk 全量迁移（无主键更新、只有 INSERT）通常无害；但若目标表有自增依赖、触发器、或 sink 用 upsert 模式，多分区会引入难以复现的顺序问题。
- **并行度**：官方 *"you can have up to one consumer instance per partition (within a consumer group); any more will be idle."* —— **分区数是 Sink 侧并行度的硬上限**。1 分区 = 该表只能有 1 个 sink task 在写。
- **DBX 的并行度已经在别处拿到了**：一次迁移有几百张表、每表一个 topic，**跨表并行**已经足够压满单机；再靠**表内分区**并行的边际收益很小，却要付出顺序风险和 controller mutation 开销（§5.1）。
- **分区不可缩减**（KIP-694 未合入），所以「先给 1，不够再加」比「先给 12」安全 —— 加分区是支持的操作。
- **例外**：某张单表数据量占整次迁移的绝大部分（长尾大表）时，可给它 4–8 个分区以提高 sink 并行度，前提是确认该表可以乱序写入。**这应当是装箱调度器的一个显式决策点，而不是全局默认值。**

> Source 侧要点：分区数 > 1 时，JDBC source 若不指定消息 key，记录会被轮询分发到各分区，顺序即被打散。若要保留顺序又想多分区，必须**按主键做 key**，这样同一主键的记录仍落同一分区（前提是分区数不再变化）。

---

## 6. 错误信息可取得的接口

DBX 的「错误翻译层」有三个信息源，完整度依次递减/互补。

### 6.1 `GET /connectors/{name}/status` 的 `trace` 字段

官方对该端点的描述：*"current status of the connector, including if it is running, failed, paused, etc., which worker it is assigned to, **error information if it has failed**, and the state of all its tasks."*

响应结构（`connector` 与每个 `tasks[]` 元素各有独立状态）：

```json
{
  "name": "dbx-box-07-sink",
  "connector": { "state": "RUNNING", "worker_id": "connect:8083" },
  "tasks": [
    {
      "id": 0,
      "state": "FAILED",
      "worker_id": "connect:8083",
      "trace": "org.apache.kafka.connect.errors.ConnectException: Exiting WorkerSinkTask due to unrecoverable exception.\n\tat org.apache.kafka.connect.runtime.WorkerSinkTask.deliverMessages(...)\n\t... Caused by: org.apache.kafka.common.errors.RecordTooLargeException: The request included a message larger than the max message size the server will accept."
    }
  ]
}
```

**完整度评估（对错误翻译层最关键的一节）**：

| 能拿到 | 拿不到 |
|---|---|
| ✅ **完整的 Java 堆栈**（`trace` 是 `Throwable` 的完整 stack trace 字符串，含 `Caused by:` 链）—— 根因异常类名和消息都在里面 | ❌ **只有导致 task 进入 FAILED 的那一个异常**。`errors.tolerance=all` 下被容忍跳过的错误**不出现在 `trace` 里**（task 还是 RUNNING） |
| ✅ 区分 connector 级失败 vs task 级失败（例如配置错误 → connector FAILED；数据错误 → task FAILED） | ❌ 拿不到出错记录的 topic/partition/offset —— `trace` 里通常没有，得去日志或 DLQ 头 |
| ✅ `worker_id`，多 worker 时用于定位日志 | ❌ 历史错误。task 重启后 `trace` 被覆盖，只保留最近一次 |
| ✅ 无需读文件，纯 REST，容器化部署下最易采集 | ❌ 状态来自 `status.storage.topic`，有**秒级延迟**；rebalance 期间可能读到 `UNASSIGNED` |

> **给错误翻译层的建议**：以 `trace` 为主输入，按「最内层 `Caused by:` 的异常全限定类名 + 消息前缀」做规则匹配。§1.2 表里的每条错误文本都可以直接做成一条翻译规则。
>
> **注意 409**：rebalance 期间调 status 相关端点可能返回 **409 Conflict**（官方明确指出 restart task 时会），需退避重试，不要把 409 当成「connector 不存在」。

### 6.2 worker 日志

- **唯一能拿到「被容忍跳过的错误」的地方**（在没配 DLQ 时）。开 `errors.log.enable=true`（默认 `false`）后会 *"log details of each error and problem record's topic, partition, and offset"* —— **这正是 `trace` 缺失的定位信息**。
- `errors.log.include.messages` 默认 **`false`**，*"preventing record keys, values, and headers from being written to log files"*。
  **⚠️ DBX 绝对不要打开它**：一条 20MB 的记录会被整条写进日志，几条就撑爆磁盘，而且会把客户的业务数据（可能含敏感信息）明文落盘。
- 只有 worker 日志能看到：broker 连接问题、`InvalidReceiveException`（那条只在 **broker** 日志里）、rebalance 过程、插件加载失败、OOM。
- **单机部署下建议**：Compose 里给 connect 服务配 `logging: driver: json-file, options: {max-size: "100m", max-file: "3"}`，并把 broker 日志也纳入采集 —— §1.2 里 `socket.request.max.bytes` 那一档错误**只在 broker 日志里**，REST 和 DLQ 都看不到。

### 6.3 DLQ topic

依据 [KIP-298](https://cwiki.apache.org/confluence/display/KAFKA/KIP-298:+Error+Handling+in+Connect) 与 Confluent 文档：

- **只对 sink connector 有效**（*"Dead Letter Queues (DLQs) are only applicable for sink connectors."*）。**source 侧没有 DLQ**，source 的错误只能靠 §6.1 + §6.2。
- 需要同时设 `errors.tolerance=all` 与 `errors.deadletterqueue.topic.name=<topic>`，否则不生效。
- `errors.deadletterqueue.context.headers.enable` 默认 **`false`**，**必须显式打开**，否则 DLQ 里只有原始 key/value/headers，**没有任何错误原因**。打开后所有 error context header 以 `__connect.errors.` 前缀写入，值均为 UTF-8 字符串，且**只在原记录没有同名 header 时才写入**：

  `__connect.errors.topic`、`__connect.errors.partition`、`__connect.errors.offset`、`__connect.errors.connector.name`、`__connect.errors.task.id`、`__connect.errors.stage`（如 `VALUE_CONVERTER`）、`__connect.errors.class.name`、`__connect.errors.exception.class.name`、`__connect.errors.exception.message`、`__connect.errors.exception.stacktrace`

  **这是三个来源里唯一能同时给出「哪一条记录」+「哪一阶段」+「完整堆栈」的**，也是唯一可用于**定位到具体行并重放**的来源。
- **单机部署的两个必坑**：
  1. `errors.deadletterqueue.topic.replication.factor` **默认为 3**。单节点 Kafka 上 DLQ topic 会创建失败，sink task 直接 FAILED。**必须设 `errors.deadletterqueue.topic.replication.factor=1`。**
  2. DLQ topic 若由 Connect 自动创建，会用 broker 默认的 `max.message.bytes`（1MB）。20MB 的坏记录写不进去 → sink task 以 `RecordTooLargeException` 失败（§1.2）。**DBX 应当预先自建 DLQ topic 并设好 `max.message.bytes=26214400`。**
- `put()` 阶段的失败早期不进 DLQ（KIP-298 原始限制，因为 `put()` 批处理无法定位是哪条记录）；后来由 `ErrantRecordReporter` API 补齐，Connect 保证这类记录在 `SinkTask.preCommit()` 之前、即 offset 提交之前写入 error topic。**但是否使用取决于具体 sink connector 是否实现了该 API** —— 选型 JDBC sink 时需要验证。
- **DLQ 会放大磁盘占用**：坏记录原样落一份 + 完整堆栈 header。对 20MB 大字段场景，DLQ topic 也要配 retention（同 §5.2）。

### 6.4 三者对照

| 需要回答的问题 | status `trace` | worker 日志 | DLQ |
|---|:--:|:--:|:--:|
| 为什么 task 挂了 | ✅ 最直接 | ✅ | ❌（挂了就没 DLQ） |
| 完整堆栈 | ✅ | ✅ | ✅（header） |
| 哪一条记录出错 | ❌ | ⚠️ 需开 `errors.log.enable` | ✅ topic/partition/offset |
| 哪一阶段（converter/transform/put） | ⚠️ 从堆栈推断 | ⚠️ | ✅ `__connect.errors.stage` |
| 被容忍跳过的错误 | ❌ | ✅ | ✅ |
| source connector 的错误 | ✅ | ✅ | ❌ 不适用 |
| broker 层连接/协议错误 | ❌ | ✅ 仅 broker 日志 | ❌ |
| 可编程采集难度 | 最低（REST/JSON） | 高（需日志采集） | 中（需起 consumer） |

**建议的 DBX 采集策略**：轮询 `GET /connectors/{n}/status` 作为主状态源 → 命中 FAILED 时用 `trace` 做错误翻译 → 若配了 DLQ，起一个 consumer 读 DLQ header 补齐「哪一行、哪一阶段」→ worker/broker 日志作为兜底人工排查入口（不做自动解析）。

---

## 7. 可直接抄的 Docker Compose 片段

> 前六节结论的落地版本。宿主机建议 **≥16GB 内存、4 核**；`26214400` = 25 MiB（§1.1 的 20MB + 余量）。

```yaml
services:
  kafka:
    image: apache/kafka:3.9.0            # ≥3.6.0（#3 的版本下限结论）
    environment:
      # --- KRaft 单节点：broker 与 controller 合并进程 ---
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: 1
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      # --- 单节点必须全部降到 1，否则内部 topic 创建失败（§4.1）---
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_DEFAULT_REPLICATION_FACTOR: 1
      KAFKA_MIN_INSYNC_REPLICAS: 1
      # --- 20MB 大消息（§1.2）---
      KAFKA_MESSAGE_MAX_BYTES: 26214400       # 默认 1048588，不改则 RecordTooLargeException（broker 侧）
      KAFKA_REPLICA_FETCH_MAX_BYTES: 26214400 # RF=1 时不生效，防御性设置，成本为零
      KAFKA_SOCKET_REQUEST_MAX_BYTES: 104857600  # 默认值即够；调小会导致 broker 静默断连
      # --- topic 管理（§5）---
      KAFKA_DELETE_TOPIC_ENABLE: "true"       # 默认 true，显式写死；清理流程依赖它
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false" # 关键：禁止自动建 topic，否则会用 1MB 默认上限建出来
      KAFKA_NUM_PARTITIONS: 1                 # 单表单 topic 默认 1 分区，保序（§5.4）
      KAFKA_LOG_SEGMENT_BYTES: 268435456      # 256MiB，让 retention 能真正回收（§5.2）
      # --- 堆：Kafka 数据走 page cache，堆几乎不随数据量增长（§3.1）---
      KAFKA_HEAP_OPTS: "-Xms2g -Xmx2g"
    volumes:
      - kafka-data:/var/lib/kafka/data       # 容量按 §5.3 公式估算，不少于 50GB

  schema-registry:
    image: confluentinc/cp-schema-registry:7.9.0
    depends_on: [kafka]
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: PLAINTEXT://kafka:9092
      SCHEMA_REGISTRY_KAFKASTORE_TOPIC_REPLICATION_FACTOR: 1   # 单节点必须为 1
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081
      # SR 只存 schema 文本，负载与表数量成正比而非数据量（§3.1）
      SCHEMA_REGISTRY_HEAP_OPTS: "-Xms256m -Xmx512m"

  connect:
    image: confluentinc/cp-kafka-connect:7.9.0
    depends_on: [kafka, schema-registry]
    environment:
      CONNECT_BOOTSTRAP_SERVERS: kafka:9092
      CONNECT_REST_ADVERTISED_HOST_NAME: connect
      CONNECT_GROUP_ID: dbx-connect
      CONNECT_CONFIG_STORAGE_TOPIC: dbx-connect-configs
      CONNECT_OFFSET_STORAGE_TOPIC: dbx-connect-offsets
      CONNECT_STATUS_STORAGE_TOPIC: dbx-connect-status
      CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR: 1   # 单节点：三个内部 topic 都必须为 1（§4.1）
      CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_STATUS_STORAGE_REPLICATION_FACTOR: 1
      CONNECT_KEY_CONVERTER: io.confluent.connect.avro.AvroConverter
      CONNECT_VALUE_CONVERTER: io.confluent.connect.avro.AvroConverter
      CONNECT_KEY_CONVERTER_SCHEMA_REGISTRY_URL: http://schema-registry:8081
      CONNECT_VALUE_CONVERTER_SCHEMA_REGISTRY_URL: http://schema-registry:8081
      # --- rebalance：绝不能用 eager，否则每次开箱会 stop-the-world（§4.2）---
      CONNECT_CONNECT_PROTOCOL: sessioned
      CONNECT_SCHEDULED_REBALANCE_MAX_DELAY_MS: 30000  # 默认 300000；单 worker 重启后不必空等 5 分钟
      CONNECT_CONNECTOR_CLIENT_CONFIG_OVERRIDE_POLICY: All  # 3.0+ 默认即 All，显式写死（§1.3）
      CONNECT_TOPIC_CREATION_ENABLE: "false"  # 由 DBX 自建 topic，保证带上 max.message.bytes
      # --- worker 级兜底：所有 connector 的 producer/consumer 都吃这份（§1.3）---
      CONNECT_PRODUCER_MAX_REQUEST_SIZE: 26214400      # 漏配 → 本地抛 RecordTooLargeException，根本到不了 broker
      CONNECT_PRODUCER_BUFFER_MEMORY: 134217728        # 默认 32MiB 只够 1 条在途大消息
      CONNECT_PRODUCER_COMPRESSION_TYPE: zstd          # 降磁盘/网络；对已压缩 blob 无收益
      CONNECT_PRODUCER_MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION: 1  # 限制在途堆占用 + 单分区严格保序
      CONNECT_CONSUMER_MAX_PARTITION_FETCH_BYTES: 26214400  # 漏配不报错，只是退化成一次一条（最难诊断）
      CONNECT_CONSUMER_FETCH_MAX_BYTES: 52428800       # 默认值即够
      CONNECT_CONSUMER_MAX_POLL_RECORDS: 1             # 默认 500 → 500×20MB = 10GB，必然 OOM（§3.2）
      CONNECT_CONSUMER_MAX_POLL_INTERVAL_MS: 900000    # 单条 20MB 写 PG 可能超默认 5 分钟
      # --- 全套里最吃堆的组件（§3.1，官方区间 0.5–4GB 取上限）---
      KAFKA_HEAP_OPTS: "-Xms2g -Xmx4g"
    logging:                                  # 防日志写爆磁盘（§6.2）
      driver: json-file
      options: { max-size: "100m", max-file: "3" }

volumes:
  kafka-data:
```

**建 topic 时必须逐 topic 下发的配置**（DBX 平台代码负责，不在 compose 里）：

```
max.message.bytes=26214400   # 必须；topic 级优先于 broker 级，对接客户自有 Kafka 时是唯一保险
retention.ms=3600000         # 1 小时
retention.bytes=5368709120   # 5GiB/分区 兜底
segment.bytes=268435456      # 256MiB，必须 ≥ max.message.bytes
segment.ms=300000            # 5 分钟强制 roll，低流量表也能回收
cleanup.policy=delete
```

**DLQ topic 单独预建**（§6.3 的两个坑）：`max.message.bytes=26214400` + 单节点 sink connector 配 `errors.deadletterqueue.topic.replication.factor=1`、`errors.deadletterqueue.context.headers.enable=true`、`errors.tolerance=all`。

---

## 8. 待验证项

本报告的**配置项名称、默认值、语义、错误文本**均来自一手文档（已附 URL）。以下结论属于**文档推断或工程估计**，必须在 **#9（本地实验床）** 与 **#10（端到端原型）** 中实测确认。

| # | 待验证结论 | 出处小节 | 建议在哪验 | 验证方法 |
|---|---|---|---|---|
| V1 | **25 MiB（`26214400`）的余量是否足够** —— 20MB blob + 其余列 + Avro + batch 框架的实际总字节 | §1.1 | #9 | 造一张含 20MB `LONGBLOB` + 若干宽列的表，跑通后用 `kafka-log-dirs`/`kafka-dump-log` 量实际 record batch 字节数 |
| V2 | **§1.2 表里每一条「漏配后的错误文本」** —— 逐项故意漏配、抓实际异常字符串 | §1.2 | #9 | 8 个负例场景各跑一次，把 `trace` 原文归档为错误翻译层的测试夹具。**这是错误翻译层最有价值的产出** |
| V3 | Connect worker **4GB 堆能撑几个并发大字段 task** —— §3.2 的「4 份拷贝」是估计，不是实测 | §3.2 | #9 | 逐步加 task 数直到 OOM，记录 GC 日志与堆直方图 |
| V4 | **单节点 rebalance 的实际耗时量级**（本报告估计 100ms–数秒，官方无数据） | §4.2 | #9 | 连续创建/删除 20 个 connector，从 worker 日志的 rebalance 起止时间戳量测 |
| V5 | **409 Conflict 的实际触发频率与退避策略** —— 连续开箱时多久会撞上 | §4.2 | #10 | 装箱调度器压测：并发提交 10 个箱，统计 409 比例 |
| V6 | **单 worker 承载上限 ≤20 connector / ≤2×核数 task** —— 由官方经验法则推出，非实测 | §4.3 | #9 | 递增 connector 数，观察 rebalance 耗时、`status.storage.topic` 写入量、堆占用的拐点 |
| V7 | **`DELETE topic` 后磁盘多久真正释放** —— 推断为「异步 + `file.delete.delay.ms` 1 分钟」 | §5.1 | #9 | 删 topic 后按秒轮询 `du`，量测实际释放延迟 |
| V8 | **磁盘估算公式的 Avro 膨胀系数与 zstd 压缩率** —— 0.6–0.9 / 3–5x 是通用经验值，非本场景实测 | §5.3 | #10 | 用真实业务表跑，比对源库数据量与 Kafka 磁盘占用，回填系数 |
| V9 | **sink 落后导致 retention 静默丢数据的检测方案是否可靠** —— 「committed offset < log start offset」的判定 | §5.3 | #10 | 人为让 sink 阻塞并把 retention 调到极短，确认能被检出而非静默少行 |
| V10 | **JDBC sink 是否实现了 `ErrantRecordReporter`** —— 决定 `put()` 阶段失败能否进 DLQ | §6.3 | #9 | 造一条会在 `put()` 失败的记录（如类型不兼容），看它是否出现在 DLQ |
| V11 | **`connector.client.config.override.policy` 的预检探测方式** —— 用 `validate` 端点探测客户 worker 是否禁用覆盖 | §1.3 | #10 | 把 worker 设成 `None`，确认 `PUT /connectors/{n}/config/validate` 返回可识别的策略违规信息 |
| V12 | **Schema Registry 512MB 堆在几百张表规模下是否够** —— 官方未给数字，本报告为估计 | §3.1 | #9 | 注册 500 张表的 schema，观察 SR 堆占用 |

**未在本报告覆盖、但相邻的问题**（可能需要独立研究票）：

- Avro `bytes` 与 PostgreSQL `bytea` / `TEXT` 的类型映射与 20MB 写入性能（属于「大字段端到端配置规格」票的范畴）。
- Kafka Connect **exactly-once source support**（AK 3.3+）在 bulk 全量迁移下是否值得开启 —— 会引入事务性 producer，改变 §3.2 的堆估算。
- 对接**客户已有 Kafka/Connect** 时的能力探测清单（broker `message.max.bytes` 是否够、`delete.topic.enable`、override policy、可用磁盘）——本报告已零散提到，建议汇总成一张预检表。

