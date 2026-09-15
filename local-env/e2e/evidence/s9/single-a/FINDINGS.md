# s9 —— reference throughput band

实测时间：2026-09-15T11:10:05+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- Source (every table): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=5000`; ordinary tables `batch.max.rows=1000` (connector default) and `max.buffer.size=0` (default); large-record table `batch.max.rows=1`, `max.buffer.size=4`; MySQL URL `useCursorFetch=true`, `defaultFetchSize` = the table's batch.max.rows; ADR-0003 producer overrides (zstd, 25 MiB request, 128 MiB buffer, in-flight 1)
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; `consumer.override.max.poll.records` = **500** for ordinary tables, **1** for the large-record table (ADR-0003); worker-level default is 1
- single stream `b_narrow_1`: 8000000 rows in 59.7s (source read done at 17.6s) → **15.53 MiB/s estimator bytes**, 10.15 MiB/s DATA_LENGTH, 134003 rows/s
- single stream `b_wide_1`: 600000 rows in 25.7s (source read done at 18.0s) → **109.64 MiB/s estimator bytes**, 60.93 MiB/s DATA_LENGTH, 23346 rows/s
- single stream `b_lob_1`: 1000 rows in 11.1s (source read done at 5.6s) → **208.00 MiB/s estimator bytes**, 137.97 MiB/s DATA_LENGTH, 90 rows/s
