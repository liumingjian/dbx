# 票 #10 实测记录（待回填）

> **状态：未执行。** 下表所有「结果」列必须由真实执行填写，
> 依据是 `artifacts/<sid>/FINDINGS.md` 与同目录下的原始产物。**不得据推理填写。**

实测环境：（宿主机 / 内存 / 核数 / Docker 版本 / 日期）

---

## 问题 1 —— 手工按矩阵建表 + Source(Avro) + Sink(auto.create=false)，数据能否正确落地

| 断言 | 结果 | 证据 |
|---|---|---|
| `t_types` 4/4 行落库 | | `s1/FINDINGS.md` |
| 无静默丢列（源列全部进 Avro schema） | | `s1/cols-dropped-t_types.txt` |
| `c_bit1` / `c_set` / `c_year` 是否为全 NULL | | `s1/pg-suspect-nonnull-counts.txt` |
| 数值列文本表示与源库一致 | | `s1/pg-numeric.txt` vs `mysql-numeric.txt` |

**结论（对 [#12](https://github.com/liumingjian/dbx/issues/12) 的输入）**：

## 问题 2 —— `DECIMAL(38,10)` 端到端精度与 `numeric.mapping`

| `numeric.mapping` | c_decimal 的 Avro 类型 | 是否变 double |
|---|---|---|
| `none` | | |
| `best_fit` | | |
| `best_fit_eager_double` | | |
| `precision_only` | | |

**[#5](https://github.com/liumingjian/dbx/issues/5) 的「对 MySQL 完全无效」是否成立**：

## 问题 3 —— `DATETIME` / `TIMESTAMP` 时区

| 项 | 结果 |
|---|---|
| MySQL 会话时区 | |
| `db.timezone=UTC` 落库值 | |
| `db.timezone=Asia/Shanghai` 落库值 | |
| 两者是否有差 | |
| 微秒是否被截断到毫秒 | |
| `9999-12-31 23:59:59.999999` 上界行是否幸存 | |

**平台该把 `db.timezone` 固定成什么**：

## 问题 4 —— utf8mb4 中文与 emoji

| 断言 | 结果 |
|---|---|
| id=2 的 `c_varchar`（中文 + 🚚📦🍜）逐字节一致 | |
| `c_json` 里的中文与 emoji 一致 | |

## 问题 5 —— 大字段

| 行 | 大小 | 是否通过 | 失败在哪一环 |
|---|---|---|---|
| `blob-32kib` | 32KiB | | |
| `blob-1mib` | 1MiB | | |
| `longblob-19mib` | 19MiB | | |
| `longblob-25mib-over-limit` | 25MiB | | |

**失败发生在 Source producer 侧还是 Sink consumer 侧**：

**异常原文（原样粘贴，这是 [#19](https://github.com/liumingjian/dbx/issues/19) 的头号素材）**：

```
```

**对 [#15](https://github.com/liumingjian/dbx/issues/15) 的输入**：

## 问题 6 —— 无主键表与复合主键表

| 项 | 结果 |
|---|---|
| `t_no_pk` + `insert.mode=insert` 落库行数 | |
| 两行重复记录是否原样保留 | |
| `mode=bulk` 的 offsets 端点返回 | |
| bulk 是否重复整表投递（30 秒后行数） | |
| `t_composite_pk` + `upsert` + `pk.mode=record_value` 落库行数 | |
| upsert 在重复投递下是否保持 4 行 | |

## 问题 7 —— 结构不匹配的失败形态与时机

| 变体 | 5 秒时状态 | 失败耗时 | 失败前已落库行数 | 异常首行 |
|---|---|---|---|---|
| 缺列 `c_text`（batch.size=1） | | | | |
| `c_longtext` 建成 integer（batch.size=1） | | | | |
| 同上但 batch.size=3000 | | | | |

**[#4](https://github.com/liumingjian/dbx/issues/4) 的「结构错误在首条记录写入时暴露、类型错误最晚攒够 batch.size 才炸」是否成立**：

**冒烟期该把 `batch.size` 设成多少**：

## 问题 8 —— 完成信号序列

| 信号 | 读完后的表现 | 能否作为完成判定依据 |
|---|---|---|
| connector / task state | | |
| `GET /connectors/{n}/offsets` | | |
| topic 末端 offset | | |
| 目标表行数 | | |
| topic offset 停滞时长 | | |

**DELETE connector 后 topic 数据是否留存**：

**对 [#13](https://github.com/liumingjian/dbx/issues/13) 的输入（完成判定的具体判据与阈值）**：

---

## 意外发现

（真跑之后一定会有。写在这里，并判断该外溢给哪张票。）
