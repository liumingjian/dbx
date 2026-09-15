# s9 —— reference throughput band

实测时间：2026-09-15T11:16:38+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- Source (every table): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=5000`; ordinary tables `batch.max.rows=1000` (connector default) and `max.buffer.size=0` (default); large-record table `batch.max.rows=1`, `max.buffer.size=4`; MySQL URL `useCursorFetch=true`, `defaultFetchSize` = the table's batch.max.rows; ADR-0003 producer overrides (zstd, 25 MiB request, 128 MiB buffer, in-flight 1)
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; `consumer.override.max.poll.records` = **500** for ordinary tables, **1** for the large-record table (ADR-0003); worker-level default is 1
- single stream `b_narrow_1`: 8000000 rows in 60.0s (source read done at 17.9s) → **15.45 MiB/s estimator bytes**, 10.10 MiB/s DATA_LENGTH, 133333 rows/s
- single stream `b_wide_1`: 600000 rows in 22.2s (source read done at 15.9s) → **126.93 MiB/s estimator bytes**, 70.54 MiB/s DATA_LENGTH, 27027 rows/s
- single stream `b_lob_1`: 1000 rows in 11.5s (source read done at 5.9s) → **200.77 MiB/s estimator bytes**, 133.18 MiB/s DATA_LENGTH, 87 rows/s
