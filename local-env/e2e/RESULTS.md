# 票 #10 实测记录

> **状态：已执行。** 结论来自 `evidence/<sid>/FINDINGS.md` 与同目录原始产物；未用推理补齐实测格。

实测环境：macOS 26.6 / Apple arm64 / 16 GiB 宿主机内存 / 8 核 / Docker 29.3.1（Docker VM 约 8.2 GB 内存）/ 2026-08-02

---

## 问题 1 —— 手工按矩阵建表 + Source(Avro) + Sink(auto.create=false)，数据能否正确落地

| 断言 | 结果 | 证据 |
|---|---|---|
| `t_types` 4/4 行落库 | **否，0/4**。Source 在第 2 行 `BIGINT UNSIGNED` 上界溢出；Sink 在第 1 行因 `c_bit1` 目标为 boolean、实际值为 smallint 失败 | `evidence/s1/trace-s1-src.txt`、`trace-s1-sink.txt` |
| 无静默丢列（源列全部进 Avro schema） | **是**，源表列名与 Avro 字段名差集为空 | `evidence/s1/cols-dropped-t_types.txt`、`avro-dbx.dbx_src.t_types.json` |
| `c_bit1` / `c_set` / `c_year` 是否为全 NULL | **否**。隔离链路 4/4 落库，三列非空计数均为 3；但实际类型分别为 INT8 / STRING / Connect Date | `evidence/s1/pg-suspect-nonnull-counts-safe.txt`、`avro-dbx.dbx_src.t_types.json` |
| 数值列文本表示与源库一致 | 全类型链路无法完成；但 `DECIMAL(38,10)` 已在 S2 单独验证 4/4 逐值一致。`BIGINT UNSIGNED` 上界不可迁移 | `evidence/s1/trace-s1-src.txt`、`evidence/s2/mysql-decimal.txt`、`pg-decimal.txt` |

**结论（对 [「任务创建编排与 DDL 审核流」](https://github.com/liumingjian/dbx/issues/12) 的输入）**：现有映射矩阵不能直接生成可工作的全类型 DDL。至少须修正 `BIT(1) → smallint`、`YEAR → date`，并对超出 signed bigint 的 `BIGINT UNSIGNED` 做迁移前阻断或专用 query/cast。不能把“Avro schema 有字段”当作可写入保证。

## 问题 2 —— `DECIMAL(38,10)` 端到端精度与 `numeric.mapping`

| `numeric.mapping` | c_decimal 的 Avro 类型 | 是否变 double |
|---|---|---|
| `none` | Connect Decimal：bytes，precision=38，scale=10 | 否 |
| `best_fit` | Connect Decimal：bytes，precision=38，scale=10 | 否 |
| `best_fit_eager_double` | Connect Decimal：bytes，precision=38，scale=10 | 否 |
| `precision_only` | Connect Decimal：bytes，precision=38，scale=10 | 否 |

四种配置的 schema 完全相同；`none` 变体经 Source → Avro → Sink → PostgreSQL 后 4/4 逐值一致，包括正负 28 位整数部分边界与 NULL。

**[「MySQL→Connect 类型元数据与 numeric.mapping 行为」](https://github.com/liumingjian/dbx/issues/5) 的「对 MySQL 完全无效」是否成立**：**成立**。

证据：`evidence/s2/c_decimal-*.json`、`mysql-decimal.txt`、`pg-decimal.txt`、空的 `decimal.diff`。

## 问题 3 —— `DATETIME` / `TIMESTAMP` 时区

| 项 | 结果 |
|---|---|
| MySQL 会话时区 | `SYSTEM / SYSTEM`（容器环境实测为 UTC） |
| `db.timezone=UTC` 落库值 | 4/4；典型行 `2026-07-24 10:30:00.123` |
| `db.timezone=Asia/Shanghai` 落库值 | 4/4；典型行 `2026-07-24 02:30:00.123` |
| 两者是否有差 | **有，Asia/Shanghai 相对 UTC 平移 −8 小时**；DATETIME 与 TIMESTAMP 都发生平移 |
| 微秒是否被截断到毫秒 | **是**，`.123456 → .123`、`.999999 → .999` |
| `9999-12-31 23:59:59.999999` 上界行是否幸存 | **是**；UTC 为 `9999-12-31 23:59:59.999`，Asia/Shanghai 为 `9999-12-31 15:59:59.999` |

**平台该把 `db.timezone` 固定成什么**：在当前 MySQL 容器 session 为 UTC 的基线下固定 **UTC**。产品实现必须先读取/约束源 session 时区，不能把该参数暴露给 DBA，也不能默认 Asia/Shanghai。

证据：`evidence/s3/pg-UTC.txt`、`pg-Asia-Shanghai.txt`、`tz.diff`。

## 问题 4 —— utf8mb4 中文与 emoji

| 断言 | 结果 |
|---|---|
| id=2 的 `c_varchar`（中文 + 🚚📦🍜）逐字节一致 | **否**。PG 为典型 mojibake（UTF-8 被按错误字符集解码） |
| `c_json` 里的中文与 emoji 一致 | **否**。`json` cast 可写入，但字符串值同样 mojibake；数字与结构保留 |

证据：`evidence/s1/mysql-utf8mb4.txt` vs `pg-utf8mb4.txt`、`mysql-json.txt` vs `pg-json-normalized.txt`。

## 问题 5 —— 大字段

| 行 | 大小 | 是否通过 | 失败在哪一环 |
|---|---|---|---|
| `blob-32kib` | 32 KiB | 是 | — |
| `blob-1mib` | 1 MiB | 是 | — |
| `longblob-19mib` | 19 MiB | 是 | — |
| `longblob-25mib-over-limit` | 25 MiB | 否 | **Source producer**，序列化后 26,214,526 bytes 超过 `max.request.size=26,214,400` |

**失败发生在 Source producer 侧还是 Sink consumer 侧**：**Source producer 侧**；topic 只有 3 条，Sink 正常写入这 3 条。

**异常原文**：完整原文见 `evidence/s5/trace-s5-src.txt`。

```text
org.apache.kafka.connect.errors.ConnectException: Unrecoverable exception from producer send callback
Caused by: org.apache.kafka.common.errors.RecordTooLargeException: The message is 26214526 bytes when serialized which is larger than 26214400, which is the value of the max.request.size configuration.
```

**对 [「超大字段预检规则与执行时机」](https://github.com/liumingjian/dbx/issues/15) 的输入**：迁移前必须在源端执行字节长度预检；“业务上限 20 MiB”要预留 Avro/record 开销，不能仅把 broker/producer 上限设成与字段大小相等后期待边界值通过。

## 问题 6 —— 无主键表与复合主键表

| 项 | 结果 |
|---|---|
| `t_no_pk` + `insert.mode=insert` 落库行数 | 5/5 |
| 两行重复记录是否原样保留 | 是，重复组数 1 |
| `mode=bulk` 的 offsets 端点返回 | `{"offsets":[]}` |
| bulk 是否重复整表投递（30 秒后行数） | 否；`poll.interval.ms=1h` 下 30 秒仍为 5 行 |
| `t_composite_pk` + `upsert` + `pk.mode=record_value` 落库行数 | 4/4 |
| upsert 在重复投递下是否保持 4 行 | **是**。显式重启 bulk Source 后 topic offset 4→8，PG 仍为 4 行 |

证据：`evidence/s6/offsets-bulk.json`、`nopk-dupes.txt`、`cpk-rows.txt`、`cpk-replay.txt`。

## 问题 7 —— 结构不匹配的失败形态与时机

| 变体 | 5 秒时状态 | 失败耗时 | 失败前已落库行数 | 根因 |
|---|---|---|---|---|
| 缺列 `c_text`（batch.size=1） | FAILED | 5 秒 | 0 | `missing fields ... c_text ... auto-evolution is disabled` |
| `c_text` 建成 integer（batch.size=1） | RUNNING | 35 秒 | 0 | `column "c_text" is of type integer but expression is of type character varying` |
| 同上但 batch.size=3000 | RUNNING | 35 秒 | 0 | 同上 |

**[「Sink 预建表写入约束与 PostgreSQL 方言行为」](https://github.com/liumingjian/dbx/issues/4) 的「结构错误在首条记录写入时暴露、类型错误最晚攒够 batch.size 才炸」是否成立**：前半句成立；后半句被实测推翻。类型错误在两种 batch.size 下都约 35 秒失败，没有等待累计到 3000 条。

**冒烟期该把 `batch.size` 设成多少**：本次结果不支持仅为了提早暴露类型错误而强制设 1；`batch.size=3000` 没有延迟本例失败。仍可因诊断粒度采用 1，但不能引用“必须攒满 batch 才报错”作为理由。

完整 trace：`evidence/s7/trace-s7-sink-v2-*.txt`。

## 问题 8 —— 完成信号序列

| 信号 | 读完后的表现 | 能否作为完成判定依据 |
|---|---|---|
| connector / task state | Source/Sink connector 与 task 全程保持 RUNNING | 否 |
| `GET /connectors/{n}/offsets` | 完成时（t=5s）仍为 `[]`；约 t=62s 才出现 incrementing=4 | 不能作为及时主信号；可作延迟确认 |
| topic 末端 offset | t=5s 达到 4，与源行数相等 | 是，主信号之一 |
| 目标表行数 | t=5s 达到 4 | 是，最终事实之一 |
| topic offset 停滞时长 | t=5s 后至 120s 不再变化 | 可作稳定窗口；本次最小样本中 `2 × poll.interval`（10s）已足够，但下游应结合目标行数和任务健康状态，不单独使用 |

**DELETE connector 后 topic 数据是否留存**：**是**，topic end offset 仍为 4。另一个实测陷阱是 Source offsets 也会跨 connector 删除保留；可重复执行必须在删除前 stop 并调用 `DELETE /connectors/{name}/offsets`。

**对 [「完成判定、重跑与失败恢复状态机」](https://github.com/liumingjian/dbx/issues/13) 的输入**：建议完成判据为“Source/Sink task 健康 + topic end offset 达到源端预估行数 + PG 行数达到预估 + 连续至少 2 个 poll interval 无增长”；offsets 端点只能作延迟辅助信号。重跑必须重置 connector offsets，并显式删除旧 topic 或使用新的运行前缀。无主键 bulk 场景 offsets 恒为空。

---

## 意外发现

1. **默认 MySQL JDBC 字符集链路产生 mojibake**：varchar 与 JSON 字符串都受影响，应外溢给 [「任务创建编排与 DDL 审核流」](https://github.com/liumingjian/dbx/issues/12) 或单独建立连接参数修复票。
2. **`BIGINT UNSIGNED` 上界在 Source 读取阶段即失败**：目标 PostgreSQL `numeric(20,0)` 无法挽救 Source 的 Java Long 溢出；DDL 映射之外还需要 Source query/cast 或明确阻断。
3. **`BIT(1)` 与 `YEAR` 的实际 Connect 类型推翻现有 DDL 假设**：分别为 INT8 与 Connect Date，不是 boolean 与 smallint。
4. **Schema Registry subject 会在 topic 删除后保留**：scenario 必须先确认 topic 有本轮记录，不能仅凭 latest schema subject 判断成功。
5. **Kafka topic 删除是异步的**：删除后立即同名创建会竞态；公共清理已改为等待 topic 确认消失。
6. **connector 删除不会自动清 Source offsets**：公共清理已改为 stop → reset offsets → delete，保证同名复跑从空 offset 开始。
