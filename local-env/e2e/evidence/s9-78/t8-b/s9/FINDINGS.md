# s9 —— reference throughput band

实测时间：2026-09-15T16:53:32+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- ADR-0031 platform memory budget: Connect heap 3072 MiB − B 512 MiB = **2560 MiB** of box reservations (E=100, E_lob=3, X=3); tier 8
- Source (every table, ADR-0033): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=100`, MySQL URL `useCursorFetch=true` without `defaultFetchSize`; ordinary producer (ADR-0031) `buffer.memory=4 MiB`, `batch.size=256 KiB`, `linger.ms=10`, idempotence on, `acks=all`, in-flight 5, zstd; large-record producer (ADR-0003) 128 MiB buffer, 16 KiB batch, 25 MiB request, in-flight 1, zstd
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; fetch limits 8/2 MiB ordinary, 50/25 MiB large-record; `max.poll.interval.ms=900000`
- `b_narrow_1`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- heap (single-narrow): idle worker **100 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **24 MiB**; worst (post-GC − idle) / ΣR = **0.058** at 14.1s (post-GC 124 MiB, ΣR 416.4 MiB); **0 of 136** post-GC figures above idle + ΣR
- heap live (single-narrow, full GCs only): no full GC in the phase
- peak RSS (single-narrow, MiB): kafka=871 postgres=401 schema-registry=169 mysql=322 connect=1224 
- source temp tables (single-narrow): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **18.0 MiB**; Created_tmp_disk_tables +61 Created_tmp_files +0 Created_tmp_tables +464 
- single stream `b_narrow_1`: 8000000 rows in 64.7s (source read done at 28.1s) → **14.33 MiB/s estimator bytes**, 9.37 MiB/s DATA_LENGTH, 123648 rows/s
- calibration `b_narrow_1`: peak excess 24 MiB of R 416.4 MiB → implied E = 1.9 (used 100)
- `b_wide_1`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (single-wide): idle worker **101 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **31 MiB**; worst (post-GC − idle) / ΣR = **0.072** at 6.9s (post-GC 132 MiB, ΣR 433.0 MiB); **0 of 42** post-GC figures above idle + ΣR
- heap live (single-wide, full GCs only): no full GC in the phase
- peak RSS (single-wide, MiB): kafka=801 postgres=401 schema-registry=174 mysql=326 connect=1310 
- source temp tables (single-wide): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +37 Created_tmp_files +0 Created_tmp_tables +139 
- single stream `b_wide_1`: 600000 rows in 20.5s (source read done at 16.4s) → **137.45 MiB/s estimator bytes**, 76.39 MiB/s DATA_LENGTH, 29268 rows/s
- calibration `b_wide_1`: peak excess 31 MiB of R 433.0 MiB → implied E = -0.5 (used 100)
- `b_lob_1`: M=1572879 B (large-record); `batch.max.rows=1`, `max.buffer.size=4`, `query.suffix=LIMIT 32`, Sink `max.poll.records=1`; R = 511.0 MiB
- heap (single-lob): idle worker **101 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **327 MiB**; worst (post-GC − idle) / ΣR = **0.640** at 1.0s (post-GC 428 MiB, ΣR 511.0 MiB); **0 of 84** post-GC figures above idle + ΣR
- heap live (single-lob, full GCs only): no full GC in the phase
- peak RSS (single-lob, MiB): kafka=896 postgres=252 schema-registry=174 mysql=261 connect=1513 
- source temp tables (single-lob): TempTable RAM high-water **17.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +31 Created_tmp_files +0 Created_tmp_tables +133 
- single stream `b_lob_1`: 1000 rows in 12.0s (source read done at 6.3s) → **192.40 MiB/s estimator bytes**, 127.63 MiB/s DATA_LENGTH, 83 rows/s
- calibration `b_lob_1`: peak excess 327 MiB of R 511.0 MiB → implied E = 1.6 (used 3)
- `b_narrow_2`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_3`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_4`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_wide_2`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- `b_wide_3`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (concurrent): idle worker **100 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **1642 MiB**; worst (post-GC − idle) / ΣR = **0.738** at 10.3s (post-GC 1742 MiB, ΣR 2226.4 MiB); **0 of 669** post-GC figures above idle + ΣR
- heap live (concurrent, full GCs only): no full GC in the phase
- peak RSS (concurrent, MiB): kafka=1139 postgres=1032 schema-registry=166 mysql=338 connect=2869 
- source temp tables (concurrent): TempTable RAM high-water **97.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +386 Created_tmp_files +0 Created_tmp_tables +5589 
- concurrent (8 boxes, 5 admitted at once, peak 6 running): whole run **70.26 MiB/s estimator bytes (42.07 MiB/s DATA_LENGTH) over 14452 MiB in 205.7s**
- per-table admit→done (s): b_lob_1=1.2→33.0 b_narrow_1=49.1→176.3 b_narrow_2=67.3→201.5 b_narrow_3=49.0→186.7 b_narrow_4=1.3→139.5 b_wide_1=1.1→53.0 b_wide_2=1.0→59.2 b_wide_3=0.0→63.3 
