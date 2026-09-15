# S9 evidence: reference throughput band

Evidence for [任务：在实验床测出参考吞吐带](https://github.com/liumingjian/dbx/issues/63). The reference machine is in `single-a/env.txt`. Reproduction commands are in the S9 row of `../../README.md`.

Throughput is measured in ADR-0002 estimator bytes per second, end to end: from the first connector PUT until PostgreSQL holds every source row. Estimator bytes are `1.5 × max(DATA_LENGTH, rows × AVG_ROW_LENGTH)`.

## single/: the single-stream band (feeds 窗口下限, the minimum window)

There were two runs with exact completion counting. Each measured one table per shape, and each phase started on a freshly restarted worker. `single-a/` holds run A and `single-b/` holds run B.

| Shape | Table | Rows | Run A | Run B | Band (estimator MiB/s) | DATA_LENGTH MiB/s | Rows/s | Source read done |
|---|---|---|---|---|---|---|---|---|
| Narrow keyed | `b_narrow_1` | 8,000,000 | 15.53 | 15.45 | **15.4–15.6** | 10.1 | 133–134k | 17.6–17.9 s of ~60 s |
| Wide text | `b_wide_1` | 600,000 | 109.64 | 126.93 | **110–127** | 61–71 | 23.3–27.0k | 15.9–18.0 s of 22–26 s |
| Large-record | `b_lob_1` | 1,000 × 1.5 MiB | 208.00 | 200.77 | **201–208** | 133–138 | 87–90 | 5.6–5.9 s of 11–12 s |

The Source finishes reading well before PostgreSQL has every row, so in the lab the bottleneck is the Sink/target path.

The wide and large-record runs last only 11–26 s. About 3 s of connector startup is 10–25% of such a run, which is why the wide band is the widest. The narrow run, at about 60 s, is the steadiest.

The first single-stream figures (narrow 13.3, wide 86, lob 108 MiB/s) are superseded. S9 then detected completion through `pg_stat_user_tables.n_tup_ins`. PostgreSQL 15 publishes an idle backend's counters up to 10 s late, so every table ended with a flat ~10 s tail. S9 now switches to an exact `COUNT(*)` once the Source has read every row.

## concurrent-variant/: why there is no concurrent band yet

This run used eight single-table boxes, ADR-0002's default limit on this machine. Ordinary connectors ran without ADR-0003's size overrides. The Connect heap reached 4.07 GiB of 4 GiB at 27 s.

`histogram-concurrent.txt` shows about 2.45 million records submitted to the producer but not yet acknowledged. Producer `buffer.memory` caps only compressed bytes, so nothing bounds the objects Connect keeps per in-flight record. Traces keep only their non-frame lines.

Follow-ups:

- [决策：Connect 堆预算与默认准入 —— Source 在途记录没有上界](https://github.com/liumingjian/dbx/issues/75)
- [任务：在实验床测出并发参考吞吐带 —— 待堆预算与读取参数定稿后重测](https://github.com/liumingjian/dbx/issues/78)

## Transcribed from run receipts

The raw files of these runs are lost. Remote dispatch rsyncs the workspace with `--delete`, which removed the copies kept under `artifacts/`. The lines below were read from each run before that happened.

| Run | Setting | Observation |
|---|---|---|
| 1 | 7.75 GiB VM, no cursor fetch, 2.2 GB dataset | Concurrent phase: `OutOfMemoryError: Java heap space` in the large-record Sink at 44 s. `KafkaBasedLog Work Thread - dbx-connect-offsets` also hit OOM. |
| 2 | The same worker, not restarted | Every Source parked in `JdbcSourceTask.start` → `OffsetStorageReaderImpl.offsets` → `ConnectorOffsetBackingStore$1.get` (4259 s). Three 90-minute phases moved zero records. The container had started before run 1's OOM, and its log held 4 errors from the offsets thread. See [决策：Connect worker OOM 后「假健康」的探测与恢复](https://github.com/liumingjian/dbx/issues/77). |
| 3 | Cursor fetch, fresh worker, default `batch.max.rows` (1000) on the large-record Source | The large-record Source running alone failed: `Error while processing table querier` → `OutOfMemoryError: Java heap space`. See [决策：MySQL Source 的读取参数 —— cursor fetch、batch.max.rows 与大字段表的预读上界](https://github.com/liumingjian/dbx/issues/76). |
| 4 | 7.75 GiB VM, bounded large-record Source | Concurrent phase: the Connect container was `OOMKilled=true` (exit 137) at 23 s. |
| 5 | 16 GiB VM, plan settings | Concurrent phase: `OutOfMemoryError: Java heap space` in `tp-concurrent-sink-b_wide_3` at about 29 s. |
