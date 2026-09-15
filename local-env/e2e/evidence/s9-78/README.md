# S9 evidence for #78: concurrent reference band at ADR-0031 and ADR-0033 settings

Evidence for [任务：在实验床测出并发参考吞吐带 —— 待堆预算与读取参数定稿后重测](https://github.com/liumingjian/dbx/issues/78). It re-measures the single-stream band from [#63](https://github.com/liumingjian/dbx/issues/63) and measures the concurrent band. Both use the settings from ADR-0031 (heap budget) and ADR-0033 (bounded Source reads). Each run directory holds S9's raw output: `FINDINGS.md`, `env.txt`, results, timelines, heap checks, RSS and temp-table samples, and connector configs.

Throughput is in ADR-0002 estimator bytes per second, measured end to end, as on #63.

## Runs

| Run | Tier | Forced full GC | Used for |
|---|---|---|---|
| `t16-a`, `t16-b` | ≥16 GiB: 16 GiB VM, Connect heap 6 GiB, Kafka heap 2 GiB | no | bands, RSS, temp tables |
| `t16-gc` | ≥16 GiB | `jcmd GC.run` every 5 s: **no effect**, see below | throughput and RSS only |
| `t16-gc2` | ≥16 GiB | class histogram every 10 s | live heap |
| `t16-lobgc` | ≥16 GiB, large-record table only | class histogram every 2 s | live heap |
| `t8-a`, `t8-b` | 8 GiB: 8 GiB VM, Connect heap 3 GiB, Kafka heap 1 GiB | no | bands, RSS, OOM check |

Forced-GC runs are excluded from the bands, because the pauses cost throughput: the narrow table dropped to 11.96 MiB/s in `t16-gc2`.

## Settings

Derived per table from M, the exact maximum row byte length (see each `FINDINGS.md`).

| Table | M | `batch.max.rows` = `max.buffer.size` | `query.suffix` | Sink `max.poll.records` | R |
|---|---|---|---|---|---|
| `b_narrow_*` | 55 B | 1024 | `LIMIT 131072` | 500 | 416.4 MiB |
| `b_wide_*` | 2330 B | 1024 | `LIMIT 16384` | 500 | 433.0 MiB |
| `b_lob_1` | 1,572,879 B | 1 and 4 (large-record) | `LIMIT 32` | 1 | 511.0 MiB |

All other connector settings are ADR-0031's and ADR-0033's, listed in `FINDINGS.md`. R uses ADR-0031's provisional constants (E=100, E_lob=3, X=3). Admission is largest planned bytes first, under ADR-0002's box cap (8 here, bound by the target connection budget) and ADR-0031's memory budget (Connect heap − 512 MiB).

## ≥16 GiB tier

### Single-stream band

| Shape | `t16-a` | `t16-b` | Band (estimator MiB/s) | DATA_LENGTH MiB/s | Rows/s | #63 band |
|---|---|---|---|---|---|---|
| Narrow keyed | 14.42 | 14.48 | **14.4–14.5** | 9.4–9.5 | 124–125k | 15.4–15.6 |
| Wide text | 133.55 | 130.45 | **130–134** | 72.5–74.2 | 27.8–28.4k | 110–127 |
| Large-record | 189.25 | 190.81 | **189–191** | 125.5–126.6 | 82–83 | 201–208 |

The narrow band is about 7% below #63's, which read without chunks at a fetch size of 1000. Wide and large-record moved within their own run-to-run noise.

### Concurrent band

The memory budget admitted all 8 tables at once (ΣR 3,476 of 5,632 MiB).

| Run | Whole run: estimator MiB/s to last table written | All-streams-active window | Last table written |
|---|---|---|---|
| `t16-a` | 91.9 (14,452 MiB ÷ 157.3 s) | 145.46 (first 46.0 s) | 157.3 s |
| `t16-b` | 88.4 (14,452 MiB ÷ 163.5 s) | 150.86 (first 44.8 s) | 163.5 s |
| **Band** | **88–92** | **145–151** | |

S9's own whole-run figure for `t16-b`, 85.01 MiB/s over 170.0 s, includes about 6.5 s of connector deletes and probes after the last table was written. `t16-a` lost its summary to a scoping bug, since fixed; its figures come from `results-concurrent.tsv` and `timeline-concurrent.tsv`.

Every Source finished reading by 57 s, so the Sink and PostgreSQL path is again the bottleneck in the lab.

### Heap against R

- **Idle worker:** 103 MiB live, after a full GC (`t16-gc2`). ADR-0031's provisional B is 512 MiB.
- **Ordinary boxes:** live heap at most 28–84 MiB above idle, 0.04–0.20 × R. The narrow implied E is 1.4–16.9, against 100 used.
- **Concurrent, after the large-record box finished:** live heap at most 196 MiB, 0.03–0.04 × ΣR (`t16-gc2`, 12 full GCs from 78 s on).
- **Large-record box during its read: no live figure.** A forced full GC never landed while the large-record Source was reading: not at a 10 s cadence in `t16-gc2`, nor at 2 s in `t16-lobgc`. The first one came after the read ended: 147 MiB, 0.09 × R. Young-GC figures during the read reached 896–1,853 MiB above idle against R = 511 MiB. These are upper bounds that include garbage. The first full GC in `t16-gc2`'s concurrent phase took the heap from 3,940 MiB to 196 MiB.
- **Why `t16-gc` shows nothing live:** Kafka's launcher adds `-XX:+ExplicitGCInvokesConcurrent` to Connect, so `jcmd GC.run` never produced a full GC. S9 now forces one with `GC.class_histogram`.

### Component RSS, peak

| Component | Single stream (MiB) | Concurrent (MiB) | ADR-0031 ≥16 GiB row |
|---|---|---|---|
| Connect | 1,285–2,742 | 6,337–6,849 | 6 GiB heap + 1 GiB non-heap |
| Kafka | 1,903–2,173 | 2,183–2,217 | 2 GiB + 0.5 GiB |
| Schema Registry | 142–307 | 159–235 | 0.75 GiB |
| MySQL (source, lab only) | 346–587 | 409–721 | — |
| PostgreSQL (target, lab only, shared memory counted per process) | 70–442 | 1,413–1,505 | — |

### Source temp tables (ADR-0033)

- **Largest session temp tablespace:** 52 MiB in every phase, against ADR-0033's 64 MiB per connection. Narrow reached 18 MiB.
- **TempTable RAM:** 17–17.5 MiB single stream, and 129.5 MiB summed over all connections concurrently. `tmp_table_size` is 16 MiB, so larger chunks spill to the session tablespace.

## 8 GiB tier

Pending.

## Reproduce

On a Mac with Docker Desktop, from `local-env/` on this branch, with the bulk dataset seeded (`bash e2e/bulk/lab-up.sh` on first use):

```bash
bash e2e/bulk/lab-run.sh 16 t16-a          # tier, run name; artifacts in ~/dbx-lab/78/t16-a/
bash e2e/bulk/lab-wait.sh t16-a            # blocks, then prints FINDINGS
bash e2e/bulk/lab-run.sh 16 t16-gc2 both all 10     # live-heap run: forced full GC every 10 s
bash e2e/bulk/lab-run.sh 8 t8-a            # resizes the Docker VM to 8 GiB first
```
