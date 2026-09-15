# s9 —— reference throughput band

实测时间：2026-09-15T16:40:19+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- ADR-0031 platform memory budget: Connect heap 6144 MiB − B 512 MiB = **5632 MiB** of box reservations (E=100, E_lob=3, X=3); tier 16
- Source (every table, ADR-0033): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=100`, MySQL URL `useCursorFetch=true` without `defaultFetchSize`; ordinary producer (ADR-0031) `buffer.memory=4 MiB`, `batch.size=256 KiB`, `linger.ms=10`, idempotence on, `acks=all`, in-flight 5, zstd; large-record producer (ADR-0003) 128 MiB buffer, 16 KiB batch, 25 MiB request, in-flight 1, zstd
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; fetch limits 8/2 MiB ordinary, 50/25 MiB large-record; `max.poll.interval.ms=900000`
- `b_lob_1`: M=1572879 B (large-record); `batch.max.rows=1`, `max.buffer.size=4`, `query.suffix=LIMIT 32`, Sink `max.poll.records=1`; R = 511.0 MiB
- heap (single-lob): idle worker **103 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **896 MiB**; worst (post-GC − idle) / ΣR = **1.753** at 3.6s (post-GC 999 MiB, ΣR 511.0 MiB); **19 of 36** post-GC figures above idle + ΣR; forced full GC every 2s
- heap live (single-lob, full GCs only): worst (live − idle) / ΣR **0.086** at 9.4 s, post-GC 147 MiB, ΣR 511.0 MiB; **0 of 1** above idle + ΣR
- peak RSS (single-lob, MiB): kafka=2093 postgres=442 schema-registry=142 mysql=428 connect=2742 
- source temp tables (single-lob): TempTable RAM high-water **17.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +31 Created_tmp_files +0 Created_tmp_tables +134 
- single stream `b_lob_1`: 1000 rows in 11.7s (source read done at 6.3s) → **197.33 MiB/s estimator bytes**, 130.90 MiB/s DATA_LENGTH, 85 rows/s
- calibration `b_lob_1`: peak excess 896 MiB of R 511.0 MiB → implied E = 6.0 (used 3)
