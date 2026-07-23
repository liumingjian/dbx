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

