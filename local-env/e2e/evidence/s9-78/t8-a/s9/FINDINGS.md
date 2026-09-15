# s9 —— reference throughput band

实测时间：2026-09-15T16:45:16+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- ADR-0031 platform memory budget: Connect heap 3072 MiB − B 512 MiB = **2560 MiB** of box reservations (E=100, E_lob=3, X=3); tier 8
- Source (every table, ADR-0033): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=100`, MySQL URL `useCursorFetch=true` without `defaultFetchSize`; ordinary producer (ADR-0031) `buffer.memory=4 MiB`, `batch.size=256 KiB`, `linger.ms=10`, idempotence on, `acks=all`, in-flight 5, zstd; large-record producer (ADR-0003) 128 MiB buffer, 16 KiB batch, 25 MiB request, in-flight 1, zstd
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; fetch limits 8/2 MiB ordinary, 50/25 MiB large-record; `max.poll.interval.ms=900000`
- `b_narrow_1`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- heap (single-narrow): idle worker **100 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **25 MiB**; worst (post-GC − idle) / ΣR = **0.060** at 7.1s (post-GC 125 MiB, ΣR 416.4 MiB); **0 of 136** post-GC figures above idle + ΣR
- heap live (single-narrow, full GCs only): no full GC in the phase
- peak RSS (single-narrow, MiB): kafka=1020 postgres=339 schema-registry=292 mysql=535 connect=1219 
- source temp tables (single-narrow): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **18.0 MiB**; Created_tmp_disk_tables +61 Created_tmp_files +0 Created_tmp_tables +465 
- single stream `b_narrow_1`: 8000000 rows in 64.2s (source read done at 26.4s) → **14.44 MiB/s estimator bytes**, 9.44 MiB/s DATA_LENGTH, 124611 rows/s
- calibration `b_narrow_1`: peak excess 25 MiB of R 416.4 MiB → implied E = 2.1 (used 100)
- `b_wide_1`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (single-wide): idle worker **100 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **33 MiB**; worst (post-GC − idle) / ΣR = **0.076** at 6.0s (post-GC 133 MiB, ΣR 433.0 MiB); **0 of 42** post-GC figures above idle + ΣR
- heap live (single-wide, full GCs only): no full GC in the phase
- peak RSS (single-wide, MiB): kafka=1012 postgres=349 schema-registry=307 mysql=533 connect=1252 
- source temp tables (single-wide): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +37 Created_tmp_files +0 Created_tmp_tables +139 
- single stream `b_wide_1`: 600000 rows in 23.3s (source read done at 16.9s) → **120.94 MiB/s estimator bytes**, 67.21 MiB/s DATA_LENGTH, 25751 rows/s
- calibration `b_wide_1`: peak excess 33 MiB of R 433.0 MiB → implied E = 0.0 (used 100)
- `b_lob_1`: M=1572879 B (large-record); `batch.max.rows=1`, `max.buffer.size=4`, `query.suffix=LIMIT 32`, Sink `max.poll.records=1`; R = 511.0 MiB
- heap (single-lob): idle worker **101 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **296 MiB**; worst (post-GC − idle) / ΣR = **0.579** at 3.1s (post-GC 397 MiB, ΣR 511.0 MiB); **0 of 79** post-GC figures above idle + ΣR
- heap live (single-lob, full GCs only): no full GC in the phase
- peak RSS (single-lob, MiB): kafka=1189 postgres=193 schema-registry=266 mysql=299 connect=1402 
- source temp tables (single-lob): TempTable RAM high-water **17.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +31 Created_tmp_files +0 Created_tmp_tables +133 
- single stream `b_lob_1`: 1000 rows in 12.0s (source read done at 6.2s) → **192.40 MiB/s estimator bytes**, 127.63 MiB/s DATA_LENGTH, 83 rows/s
- calibration `b_lob_1`: peak excess 296 MiB of R 511.0 MiB → implied E = 1.3 (used 3)
- `b_narrow_2`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_3`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_4`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_wide_2`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- `b_wide_3`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (concurrent): idle worker **101 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **1432 MiB**; worst (post-GC − idle) / ΣR = **0.699** at 143.1s (post-GC 392 MiB, ΣR 416.4 MiB); **0 of 582** post-GC figures above idle + ΣR
- heap live (concurrent, full GCs only): no full GC in the phase
- peak RSS (concurrent, MiB): kafka=1030 postgres=1137 schema-registry=158 mysql=368 connect=2804 
- source temp tables (concurrent): TempTable RAM high-water **97.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +386 Created_tmp_files +0 Created_tmp_tables +4371 
- concurrent (8 boxes, 5 admitted at once, peak 6 running): whole run **92.94 MiB/s estimator bytes (55.65 MiB/s DATA_LENGTH) over 14452 MiB in 155.5s**
- per-table admit→done (s): b_lob_1=0.8→33.5 b_narrow_1=39.1→131.3 b_narrow_2=68.6→148.5 b_narrow_3=39.0→136.9 b_narrow_4=0.9→116.9 b_wide_1=0.7→53.8 b_wide_2=0.6→57.9 b_wide_3=0.0→63.7 
