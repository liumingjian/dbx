# DBX v1 本地验证环境（实验床）

对应票：[任务：搭建本地验证环境](https://github.com/liumingjian/dbx/issues/9)（地图 [#1](https://github.com/liumingjian/dbx/issues/1)）

一套 Docker Compose，起 **MySQL 8.0 + PostgreSQL 15 + Kafka(KRaft 单节点) + Kafka Connect + Schema Registry**，外加一份专门用来踩坑的 MySQL 种子数据。**这里不放产品代码**，只是后续原型票（[#10](https://github.com/liumingjian/dbx/issues/10) 起）的实验床。

> ✅ **2026-08-01 已在真实环境跑通。**
> 验证环境：Apple Silicon，宿主机 16GB / 8 核，Docker Desktop 虚拟机 7.65GiB；五个服务冷启动、19MiB 长文本迁移、命名卷持久化均已实测。结果见 §7，实际踩坑见 §8。

---

## 1. 前置要求

| 项 | 要求 | 出处 |
|---|---|---|
| 内存 | **≥16GB 推荐，8GB 是下限** | [#6](https://github.com/liumingjian/dbx/issues/6) §3.1：堆分配 Kafka 2GB + Connect 4GB + SR 512MB |
| CPU | ≥4 核 | 同上 |
| 磁盘 | ≥50GB 可用 | [#6](https://github.com/liumingjian/dbx/issues/6) §5.3。另需约 4GB 拉镜像 |
| Docker | Compose V2（`docker compose`，非 `docker-compose`） | 用到 `depends_on.condition` |
| 外网 | 首次 `fetch-plugins.sh` 与拉镜像时需要 | |

## 2. 一次性准备

```bash
cd local-env
./fetch-plugins.sh          # 下载 JDBC connector + MySQL Connector/J 到 connect/plugins/
docker compose build connect
```

`connect/plugins/` 已在 `.gitignore` 里，不入库。

> **许可提醒**（[#2](https://github.com/liumingjian/dbx/issues/2)）：`fetch-plugins.sh` 会下载 **MySQL Connector/J（GPLv2 + Universal FOSS Exception）**。
> UFE 只对 OSI/FSF 认可的自由软件生效，**对专有软件不生效** → 它绝不能打进 DBX 的发行包。
> 本地实验床自行下载无妨；产品安装器必须走「引导客户自备该 JAR」的路子（Confluent 自己也是这么做的）。
> `connect/Dockerfile` 里额外删掉了镜像自带的 `confluent-hub-client`（Confluent Enterprise License），让实验床与将来可分发的镜像保持同一形态。

## 3. 起停

```bash
docker compose up -d
docker compose ps                    # 等 5 个服务全部 healthy
docker compose logs -f connect
```

**首次启动会很慢**：MySQL 的种子脚本要在服务端逐块拼出 25MiB 的高熵随机 BLOB（见 §5），`mysql` 的 healthcheck `start_period` 已放到 300s。

停止与彻底清理：

```bash
docker compose down                  # 停，保留数据卷
docker compose down -v               # 连数据卷一起删（种子数据会重新生成）
```

## 4. 里面装了什么

| 组件 | 镜像 / 版本 | 端口 | 备注 |
|---|---|---|---|
| MySQL | `mysql:8.0.40` | 3306 | 库 `dbx_src`，账号 `dbx`/`dbx`，root 密码 `dbx` |
| PostgreSQL | `postgres:15.10` | 5432 | 库 `dbx_target`，账号 `dbx`/`dbx`，**空库**——建表由平台/人负责 |
| Kafka | `apache/kafka:3.9.0` | 29092（宿主） | KRaft 单节点。版本下限 3.6.0（[#3](https://github.com/liumingjian/dbx/issues/3)） |
| Schema Registry | `confluentinc/cp-schema-registry:7.9.0` | 8081 | |
| Kafka Connect | `confluentinc/cp-kafka-connect:7.9.0` + 插件 | 8083 | distributed 模式，group `dbx-connect` |
| JDBC Connector | `confluentinc-kafka-connect-jdbc` **10.9.6** | | CCL 许可，release date 2026-07-07 |

JDBC connector 包内已核对的事实（解包 zip 得到，非推测）：

- zip 大小 26,926,037 字节，sha256 `1581f133644c34b9a6cfcf0a6f2011fc1c66ecbb458a175c936018a38b72be27`
- **自带 `postgresql-42.7.11.jar`** → PG 驱动不用单独下载
- **不含任何 MySQL 驱动** → 印证 [#2](https://github.com/liumingjian/dbx/issues/2) 的判断，必须自备
- 另外自带 Oracle（`ojdbc8`/`orai18n`/`ucp`/`xdb`/`oraclepki` 等 19.7.0.0）、`mssql-jdbc-12.8.2`、`jtds-1.3.1`、`sqlite-jdbc-3.41.2.2` —— **DBX 只用 MySQL→PG，这些在发行包里应当裁掉**（Oracle 驱动有独立许可条款）。这一条以前没人提过，[#22](https://github.com/liumingjian/dbx/issues/22) 汇编方案书时要收进「发行与升级」章节。

关键配置全部在 `docker-compose.yml` 里逐行标注了出处小节，改之前先读 [#6](https://github.com/liumingjian/dbx/issues/6)。三条最容易踩的：

- `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false` + `CONNECT_TOPIC_CREATION_ENABLE=false` —— topic 必须显式建，否则会用 1MB 默认上限自动建出来，大字段直接炸。
- `CONNECT_CONSUMER_MAX_POLL_RECORDS=1` —— 默认 500 × 20MB = 10GB，必 OOM。想测小表吞吐就在 connector 配置里写 `consumer.override.max.poll.records=500` 覆盖（worker 已设 `override.policy=All`）。
- `CONNECT_CONNECT_PROTOCOL=sessioned` —— 绝不能退回 `eager`，否则每次增删 connector 都 stop-the-world。

## 5. 种子数据在验什么

库 `dbx_src`，五张表。除 `t_no_pk` 外都有自增主键 `id`，好让 Source 用 `mode=incrementing`（[#3](https://github.com/liumingjian/dbx/issues/3)：`bulk` 模式不写 offset，「查 offset 判完成」不成立）。

| 表 | 行数 | 验什么 |
|---|---|---|
| `t_types` | 4 | 类型映射矩阵（[#11](https://github.com/liumingjian/dbx/issues/11)）。22 个类型列 × 典型值/上界/下界/全 NULL 四行 |
| `t_no_pk` | 5 | 无主键表只能走 `bulk`；含两行完全重复的记录 —— 校验规格 [#16](https://github.com/liumingjian/dbx/issues/16) 的「重复检查」在这张表上无从下手 |
| `t_composite_pk` | 4 | 复合主键的 DDL 生成与 Sink `pk.mode`；主键里含四字节字符 |
| `t_large_text` | 4 | 60KiB / 1MiB / 19MiB 长文本 + 一条字符数≠字节数的 utf8mb4 陷阱行 |
| `t_large_blob` | 4 | 32KiB / 1MiB / 19MiB / **25MiB（超限）** |

几个刻意的设计，别当成随手写的：

- **大字段数据是不可压缩的随机字节**。Connect 配了 `producer.compression.type=zstd`，如果用 `REPEAT('x', N)` 造 19MiB，会被压成几 KB，而 `message.max.bytes` 管的是**压缩后的 record batch** —— 整个大消息验证就成了空转。所以种子脚本逐块拼 `RANDOM_BYTES(1024)`，代价是首次启动慢。
- **`t_large_blob` 里那条 25MiB 的行（`longblob-25mib-over-limit`）就是不该被迁走的那一条**。25MiB = 26214400 = `message.max.bytes` 本身，加上 Avro 与 batch 框架开销必然超限。它用来验证 [#15](https://github.com/liumingjian/dbx/issues/15) 的迁移前预检能在建表审核阶段红字拦住它。
- **`t_types` 里 `c_bit1` / `c_set` / `c_year` 是「静默丢列」的候选**。[#5](https://github.com/liumingjian/dbx/issues/5) 的头号风险：未知类型只打 WARN 并返回 null，配合 Sink 只按列名匹配（[#4](https://github.com/liumingjian/dbx/issues/4)）→ 任务全绿但该列全 NULL。跑完务必逐列比对，别只看行数。
- **`c_datetime6` 与 `c_timestamp6` 在 Connect 层不可区分**（[#5](https://github.com/liumingjian/dbx/issues/5)），上界行分别取 `9999-12-31` 和 TIMESTAMP 的 2038 天花板，用来看清这个丢失。

## 6. 手工跑通一条链路

以 `t_large_text` 为例（有自增 PK、类型简单、且直接压到大消息路径）。

### 6.1 建 topic —— 必须手工建，且必须带 `max.message.bytes`

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create \
  --topic dbx.dbx_src.t_large_text --partitions 1 --replication-factor 1 \
  --config max.message.bytes=26214400 \
  --config segment.bytes=268435456 \
  --config retention.ms=3600000 \
  --config segment.ms=300000 \
  --config cleanup.policy=delete
```

topic 名 = Source 的 `topic.prefix` + 表名。topic 级 `max.message.bytes` 优先于 broker 级，是对接客户自有 Kafka 时的唯一保险（[#6](https://github.com/liumingjian/dbx/issues/6) §1.2）。

### 6.2 在 PG 建目标表 —— 列名必须与 Connect 字段名逐字符相等

```bash
docker compose exec -T postgres psql -U dbx -d dbx_target <<'SQL'
CREATE TABLE t_large_text (
  id         integer PRIMARY KEY,
  label      varchar(64) NOT NULL,
  c_text     text,
  c_longtext text
);
SQL
```

> **[#4](https://github.com/liumingjian/dbx/issues/4) 的「半大小写不敏感」陷阱**：Sink 的缺列检测是大小写不敏感的，但它拼 SQL 时给列名加双引号原样引用 → **PG 列名必须与 Connect 字段名逐字符相等**。MySQL 列名是小写，PG 不加引号建表也是小写，正好对上；一旦有人手抖写成 `"C_Text"` 就会在首条记录写入时炸。
>
> 这里的 DDL 是为了走通链路手写的，**不是类型映射矩阵的结论** —— 那是 [#11](https://github.com/liumingjian/dbx/issues/11) 的产出。

### 6.3 建 Source connector

```bash
curl -sS -X PUT http://localhost:8083/connectors/src-t-large-text/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "io.confluent.connect.jdbc.JdbcSourceConnector",
  "connection.url": "jdbc:mysql://mysql:3306/dbx_src?useSSL=false&allowPublicKeyRetrieval=true",
  "connection.user": "dbx",
  "connection.password": "dbx",
  "mode": "incrementing",
  "incrementing.column.name": "id",
  "table.whitelist": "t_large_text",
  "topic.prefix": "dbx.dbx_src.",
  "poll.interval.ms": 1000,
  "batch.max.rows": 100,
  "tasks.max": 1
}' | jq
```

`batch.max.rows` **必须显式设**：[#3](https://github.com/liumingjian/dbx/issues/3) 发现源码 v10.9.6 的默认是 1000，而官方文档仍写着 100 —— 文档与源码不一致，别依赖默认。含大字段的表这个值要压得很低。

### 6.4 建 Sink connector —— 冒烟期 `batch.size` 设 1

```bash
curl -sS -X PUT http://localhost:8083/connectors/sink-t-large-text/config \
  -H 'Content-Type: application/json' -d '{
  "connector.class": "io.confluent.connect.jdbc.JdbcSinkConnector",
  "connection.url": "jdbc:postgresql://postgres:5432/dbx_target",
  "connection.user": "dbx",
  "connection.password": "dbx",
  "topics": "dbx.dbx_src.t_large_text",
  "table.name.format": "t_large_text",
  "auto.create": "false",
  "auto.evolve": "false",
  "insert.mode": "insert",
  "pk.mode": "none",
  "batch.size": "1",
  "errors.tolerance": "none"
}' | jq
```

`batch.size=1`：[#4](https://github.com/liumingjian/dbx/issues/4) 的结论是**类型错误最晚要攒够 `batch.size`（默认 3000）才炸**，而且一条坏行会毒掉整批（回滚含同 batch 的其它表）。冒烟阶段设 1，能第一时间定位到具体哪一行。

### 6.5 看状态、看数据

```bash
# connector 与 task 状态；失败时 trace 字段就是错误翻译层要吃的原料（#19）
curl -sS http://localhost:8083/connectors/sink-t-large-text/status | jq

# source offset（incrementing 模式才有；bulk 模式这里是空的 —— #3 的关键发现）
curl -sS http://localhost:8083/connectors/src-t-large-text/offsets | jq

# topic 里的实际内容。大消息 topic 一定要带 --max-messages 和大 fetch 上限，否则刷屏/卡死
docker compose exec schema-registry kafka-avro-console-consumer \
  --bootstrap-server kafka:9092 --topic dbx.dbx_src.t_types --from-beginning \
  --property schema.registry.url=http://schema-registry:8081 \
  --consumer-property max.partition.fetch.bytes=26214400 \
  --max-messages 4

# Connect 推给 Schema Registry 的 Avro schema —— 类型映射矩阵（#11）的一手证据
curl -sS http://localhost:8081/subjects | jq
curl -sS http://localhost:8081/subjects/dbx.dbx_src.t_types-value/versions/latest | jq -r .schema | jq

# 落地结果
docker compose exec postgres psql -U dbx -d dbx_target -c '\d+ t_large_text'
docker compose exec postgres psql -U dbx -d dbx_target \
  -c "SELECT id, label, length(c_longtext) FROM t_large_text ORDER BY id"
```

### 6.6 清理一条链路（重跑前）

```bash
curl -sS -X DELETE http://localhost:8083/connectors/src-t-large-text
curl -sS -X DELETE http://localhost:8083/connectors/sink-t-large-text
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --delete --topic dbx.dbx_src.t_large_text
docker compose exec postgres psql -U dbx -d dbx_target -c 'TRUNCATE t_large_text'
```

Connect **完全没有「跑完即停」语义**（[#3](https://github.com/liumingjian/dbx/issues/3)）—— 全量读完后 connector 会一直挂着轮询，必须由外部判定完成并主动 DELETE。这正是 [#13](https://github.com/liumingjian/dbx/issues/13) 要定的规格。

## 7. 实测数据

实测时间：2026-08-01。宿主机 Apple Silicon，16GB / 8 核；Docker Desktop 虚拟机实际分配 7.65GiB / 8 核，低于推荐的 16GB，但高于 8GB 下限。

| 项 | 值 |
|---|---|
| 各镜像 digest | `docker image inspect` 确认与下表完全一致 |
| JDBC Connector / JDBC 驱动 | Connector 10.9.6；MySQL 9.1.0；PostgreSQL 42.7.11 |
| 全新命名卷下 `up -d` 到 5/5 healthy | **40 秒**；MySQL 初始化约 15 秒，其中大字段种子 SQL 约 3 秒 |
| 保留命名卷的热启动 | **39 秒** |
| 19MiB 长文本端到端迁移 | **10 秒**；4/4 行落库，最大值 `octet_length=19922944` |
| 空载内存（迁移前） | Connect 1.68GiB；Kafka 398MiB；MySQL 438MiB；Schema Registry 276MiB；PostgreSQL 32MiB；合计约 **2.80GiB** |
| 迁移后内存 | Connect 2.12GiB；Kafka 447MiB；MySQL 486MiB；Schema Registry 290MiB；PostgreSQL 72MiB；合计约 **3.41GiB** |
| 镜像总体积 | 五个上游镜像按 Docker 展示尺寸合计约 **6.27GB**；Connect 派生镜像 2.16GB，但与基础镜像共享 2.13GB，仅新增约 31MB |
| 全新数据卷初始体积 | Kafka **1.76GB**；MySQL **359.8MB**；PostgreSQL **64.6MB**（迁移后约 86.6MB）；合计约 **2.18GB** |
| 运行态容器可写层 | 修复 Kafka 挂载后约 **348KiB**；数据不再写进容器层 |
| 遇到的坑 | Kafka 默认数据目录与原命名卷挂载不一致；Source offset REST 返回空；详见 §8 |

镜像 digest（实测由 `docker image inspect` 的 `RepoDigests` 确认）：

```
library/mysql:8.0.40                     sha256:d58ac93387f644e4e040c636b8f50494e78e5afc27ca0a87348b2f577da2b7ff
library/postgres:15.10                   sha256:d609c3005478af92bddad773423df829b7402ea0b356d5b72edd2fd54d1ad3ea
apache/kafka:3.9.0                       sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f
confluentinc/cp-schema-registry:7.9.0    sha256:7b9182366be178292cb9cf12af0dab5bb98f4daffef0cdb91e524379ac04208a
confluentinc/cp-kafka-connect:7.9.0      sha256:535b1751f64af95bee4bf15ad2ab6b1ca2b369131711801c93e3ceac836dd2a1
```

同时，[#6](https://github.com/liumingjian/dbx/issues/6) §8 列了 12 项「待实测」（V1–V12），其中 **V1、V2、V3、V4、V6、V7、V10、V12 指名要在本实验床上验**。最有价值的是 **V2：逐项故意漏配大消息相关参数，抓实际异常字符串** —— 那批 trace 原文是错误翻译层（[#19](https://github.com/liumingjian/dbx/issues/19)）最直接的素材，建议归档成测试夹具。

## 8. 已知坑

- **[已确认并修复] Kafka 命名卷必须对齐 `log.dirs`。** `apache/kafka:3.9.0` 默认写 `/tmp/kraft-combined-logs`，原 Compose 却把卷挂到 `/var/lib/kafka/data`，导致命名卷 0B、容器可写层约 1.78GB，普通 `docker compose down` 就会丢 topic。现已显式设置 `KAFKA_LOG_DIRS=/var/lib/kafka/data`；实测普通 `down/up` 后 topic 仍存在，容器可写层降到约 348KiB。
- **[已确认] Source offset REST 端点在本实验床返回空数组。** `mode=incrementing` 的 Source 成功读取到 id=4、4 行全部落库且 connector/task 均为 `RUNNING`，但 `GET /connectors/src-t-large-text/offsets` 在迁移后 10 秒仍返回 `{"offsets":[]}`。这与研究结论预期不符，完成判定不能只依赖此端点；应由 [#13](https://github.com/liumingjian/dbx/issues/13) 继续定位。
- **[已确认] topic 名同时含 `.` 与 `_` 会触发 Kafka 指标名碰撞警告。** 创建 `dbx.dbx_src.t_large_text` 时 Kafka CLI 明确警告两种字符可能碰撞。数据路径不受影响，但产品 topic 命名规则应只选其中一种分隔符，或接受指标歧义。
- **[已确认] `docker compose build connect` 前必须先跑 `./fetch-plugins.sh`**，否则 `COPY plugins/` 会因目录不存在直接失败。
- **[已证伪：本机不慢] 首次启动 MySQL。** 在 8 核 Apple Silicon Docker 虚拟机里，种子 SQL 约 3 秒、MySQL 初始化约 15 秒，五个服务 40 秒全部 healthy。慢机器仍可用 300 秒 `start_period` 兜底。
- **[已确认] `RANDOM_BYTES()` 单次上限 1024 字节**，种子脚本的双层循环不能合并成一次调用。
- **[已确认] `max_allowed_packet` 必须调大**（compose 里设为 256MiB），25MiB 用户变量与 `TO_BASE64` 中间结果都受它约束。
- **[配置已验证，故障形态待 #19 采集] 忘带 topic 级 `max.message.bytes`** 会使 19MiB 行超过默认约 1MiB 上限；本次成功路径确认动态 topic 配置为 `26214400`。
- **[配置已验证，退化形态待性能票采集] consumer 侧 `max.partition.fetch.bytes`** 已在 worker 级设为 25MiB；console consumer 仍需手工携带。
