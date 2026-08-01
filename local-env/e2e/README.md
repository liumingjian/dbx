# 端到端最小链路验证套件（票 #10）

对应票：[原型：端到端最小链路验证（平台建表 + Sink 只写数据）](https://github.com/liumingjian/dbx/issues/10)（地图 [#1](https://github.com/liumingjian/dbx/issues/1)）

抛弃型原型，**不进主干**。它在 [#9](https://github.com/liumingjian/dbx/issues/9) 搭好的实验床上，把票 #10 的 8 个问题逐条跑成可重复执行的脚本，并把**原始输出**（尤其是失败 trace）全部落盘。

> ⚠️ **本套件尚未在真实环境执行过。** 脚本已写完并通过语法检查，但撰写它的机器没有 Docker（1GB 内存）。
> `artifacts/` 为空，`RESULTS.md` 全是待回填的空格。**任何人不得在未实跑的情况下填写结论。**

## 为什么值得写成脚本而不是手敲 curl

票 #10 要的不是"跑通一次"，而是三样后续票直接依赖的东西：

- **失败原文**：S5 的 25MiB 超限、S7 的结构不匹配，它们的 stack trace 是错误翻译层（[#19](https://github.com/liumingjian/dbx/issues/19)）规则表的第一批条目，必须原样归档成夹具。
- **时机与时间线**：S7 的"什么时候报错"、S8 的"完成信号序列"，手敲 curl 根本抓不准，只能靠固定间隔采样。
- **可复跑**：这几条结论会被 [#12](https://github.com/liumingjian/dbx/issues/12)/[#13](https://github.com/liumingjian/dbx/issues/13)/[#15](https://github.com/liumingjian/dbx/issues/15) 反复引用，换机器、换版本要能一键复核。

## 跑法

```bash
cd local-env
./fetch-plugins.sh && docker compose build connect && docker compose up -d
docker compose ps                 # 等 5/5 healthy（冷启动约 40 秒，见 ../README.md §7）

./e2e/run-all.sh                  # 全跑，约 10–15 分钟
./e2e/run-all.sh s5 s7            # 只跑指定几条
```

依赖：`docker compose`、`curl`、`jq`。

## scenario 与票 #10 问题的对应

| scenario | 票 #10 的问题 | 验什么 | 主要产物 |
|---|---|---|---|
| `s1` | 1、4 | `t_types` 全类型主链路；utf8mb4 中文与 emoji；**静默丢列**（源表列名 vs Avro 字段名差集） | `avro-*.json`、`cols-dropped-*.txt` |
| `s2` | 2 | `numeric.mapping` 四种取值下 `DECIMAL(38,10)` 的 Avro 类型是否真的不变（[#5](https://github.com/liumingjian/dbx/issues/5) 的核心结论） | `c_decimal-*.json` |
| `s3` | 3 | `db.timezone` 取 UTC 与 Asia/Shanghai 时 `DATETIME`/`TIMESTAMP` 的落库差异；毫秒截断 | `pg-*.txt`、`tz.diff` |
| `s5` | 5 | 32KiB/1MiB/19MiB 通过，25MiB 在**哪一环**炸、报**什么错** | `oversize-evidence.txt`、`timeline.tsv` |
| `s6` | 6 | 无主键表 `insert`（含 bulk 的 offset 与重复投递）；复合主键表 `upsert` | `offsets-bulk.json`、`cpk-rows.txt` |
| `s7` | 7 | 缺列 / 类型错 / 类型错+默认 batch.size 三个变体的失败形态与**时机** | `trace-*.txt` |
| `s8` | 8 | 从创建到读完的五路信号同时采样，1 秒一采 | `signals.tsv` |

没有 `s4`：问题 4（utf8mb4）与问题 1 是同一条链路上的两个断言，拆开跑等于白跑一遍主链路，已并入 `s1`。

## 产物的读法

```
artifacts/<sid>/
  FINDINGS.md          ← 人读的结论，run-all.sh 末尾会汇总打印
  run.log              ← 全过程
  connector-*.json     ← 实际下发的 connector 配置（方案书要贴的就是这份）
  status-*.json        ← REST 状态快照
  trace-*.txt          ← FAILED 时抽出的 stack trace（#19 的素材）
  connect-log-*.txt    ← worker 日志切片（REST 的 trace 只有最后一跳，根因常在这里）
  avro-*.json          ← Schema Registry 里的实际 Avro schema（#11 的一手证据）
  *.tsv                ← 时间线采样
```

`artifacts/` 不入库（见 `.gitignore`）。**要沉淀的证据请显式挑出来** commit 到 `evidence/`，尤其是 S5 与 S7 的 trace —— 它们要变成 [#19](https://github.com/liumingjian/dbx/issues/19) 的测试夹具。

## 已知限制

- **每条 scenario 顺序执行、互不并发**。Connect 增删 connector 会触发 rebalance（[#6](https://github.com/liumingjian/dbx/issues/6)：KIP-415 后只影响增减的 task），并发跑会污染时间线测量。
- **S7 的第三个变体要等满 120 秒**才能判定"是否被推迟到攒够 batch"。这是有意的等待，不是卡住。
- **S2 不接 Sink**：判定只需要 Avro schema 与 topic 内容，接 Sink 反而要为四种取值各建一张目标表。
- **`topic_end_offset` 依赖 `kafka-get-offsets.sh`**（Kafka ≥ 3.0）。实验床是 3.9.0，满足。
- 脚本假设种子数据是**初始状态**（`t_types` 4 行、`t_large_blob` 4 行…）。若之前手工往源库插过数据，先 `docker compose down -v` 重来。
