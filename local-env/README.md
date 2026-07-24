# DBX v1 本地验证环境（实验床）

对应票：[任务：搭建本地验证环境](https://github.com/liumingjian/dbx/issues/9)（地图 [#1](https://github.com/liumingjian/dbx/issues/1)）

一套 Docker Compose，起 **MySQL 8.0 + PostgreSQL 15 + Kafka(KRaft 单节点) + Kafka Connect + Schema Registry**，外加一份专门用来踩坑的 MySQL 种子数据。**这里不放产品代码**，只是后续原型票（[#10](https://github.com/liumingjian/dbx/issues/10) 起）的实验床。

> ⚠️ **本文件中的配置、脚本与命令尚未在真实环境跑过一次。**
> 编写它的机器没有 Docker（961MB 内存 / 1 核 / 4.8GB 磁盘）。
> 配置值全部来自研究票 [#2](https://github.com/liumingjian/dbx/issues/2)–[#8](https://github.com/liumingjian/dbx/issues/8) 的一手结论，插件 URL 与校验和是实际下载核对过的，但**启动流程本身没有被验证**。
> 第一位在够格机器上跑通的人，请把文末「待回填的实测数据」补上，并把踩到的坑写进「已知坑」——那才是这张票真正的交付物。

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

## 7. 待回填的实测数据

票 [#9](https://github.com/liumingjian/dbx/issues/9) 要求记录以下几项。**第一位跑通的人请补进这张表，并更新到 issue 的 resolution 评论里。**

| 项 | 值 |
|---|---|
| 各镜像 digest | 已从 registry 取到（下表），跑通后确认与 `docker images --digests` 一致 |
| JDBC Connector 版本 | 10.9.6（已核对，见 §4） |
| 首次 `up -d` 到全部 healthy 的耗时 | 待填（其中种子数据生成占多少？） |
| 空载内存占用（`docker stats`，逐容器） | 待填 |
| 镜像总体积 / 数据卷初始体积 | 待填 |
| 遇到的坑 | 待填 |

镜像 digest（撰写时从 Docker Hub registry 取的 manifest index digest，五个 tag 均已确认存在）：

```
library/mysql:8.0.40                     sha256:d58ac93387f644e4e040c636b8f50494e78e5afc27ca0a87348b2f577da2b7ff
library/postgres:15.10                   sha256:d609c3005478af92bddad773423df829b7402ea0b356d5b72edd2fd54d1ad3ea
apache/kafka:3.9.0                       sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f
confluentinc/cp-schema-registry:7.9.0    sha256:7b9182366be178292cb9cf12af0dab5bb98f4daffef0cdb91e524379ac04208a
confluentinc/cp-kafka-connect:7.9.0      sha256:535b1751f64af95bee4bf15ad2ab6b1ca2b369131711801c93e3ceac836dd2a1
```

同时，[#6](https://github.com/liumingjian/dbx/issues/6) §8 列了 12 项「待实测」（V1–V12），其中 **V1、V2、V3、V4、V6、V7、V10、V12 指名要在本实验床上验**。最有价值的是 **V2：逐项故意漏配大消息相关参数，抓实际异常字符串** —— 那批 trace 原文是错误翻译层（[#19](https://github.com/liumingjian/dbx/issues/19)）最直接的素材，建议归档成测试夹具。

## 8. 已知坑

> 目前只有「预判」，没有「实测」。跑过之后请把实际踩到的坑追加进来，并把预判条目标成已确认或已证伪。

- **[预判] `docker compose build connect` 前必须先跑 `./fetch-plugins.sh`**，否则 `COPY plugins/` 会因目录不存在直接失败。
- **[预判] 首次启动 MySQL 慢**：种子脚本要拼约 66MiB 的高熵随机数据（25+19+19+1+…），CPU 弱的机器可能要几分钟。healthcheck `start_period=300s` 是按此设的；若仍超时，先看 `docker compose logs mysql` 是不是还在跑 initdb。
- **[预判] `RANDOM_BYTES()` 单次上限 1024 字节**，所以种子脚本是双层循环拼的；别"优化"成一次调用。
- **[预判] `max_allowed_packet` 必须调大**（compose 里设了 256MiB）。25MiB 的用户变量与 `TO_BASE64` 的中间结果都受它约束。
- **[预判] 忘了带 `--config max.message.bytes` 建 topic**，症状是 19MiB 那行报 `MESSAGE_TOO_LARGE`，而小行一切正常 —— 部分成功最难查。
- **[预判] consumer 侧漏配 `max.partition.fetch.bytes` 不报错**，只是退化成一次 fetch 一条，表现为「慢得离谱但没有错误」（[#6](https://github.com/liumingjian/dbx/issues/6) §1.2）。worker 级已配好，但用 console consumer 手工消费时要自己带上。
