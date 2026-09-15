# s9 —— reference throughput band

实测时间：2026-09-15T16:09:25+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- ADR-0031 platform memory budget: Connect heap 6144 MiB − B 512 MiB = **5632 MiB** of box reservations (E=100, E_lob=3, X=3); tier 16
- Source (every table, ADR-0033): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=100`, MySQL URL `useCursorFetch=true` without `defaultFetchSize`; ordinary producer (ADR-0031) `buffer.memory=4 MiB`, `batch.size=256 KiB`, `linger.ms=10`, idempotence on, `acks=all`, in-flight 5, zstd; large-record producer (ADR-0003) 128 MiB buffer, 16 KiB batch, 25 MiB request, in-flight 1, zstd
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; fetch limits 8/2 MiB ordinary, 50/25 MiB large-record; `max.poll.interval.ms=900000`
- `b_narrow_1`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- heap (single-narrow): idle worker **119 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **23 MiB**; worst (post-GC − idle) / ΣR = **0.055** at 21.3s (post-GC 142 MiB, ΣR 416.4 MiB); **0 of 137** post-GC figures above idle + ΣR
- peak RSS (single-narrow, MiB): kafka=2173 postgres=403 schema-registry=169 mysql=346 connect=1285 
- source temp tables (single-narrow): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **18.0 MiB**; Created_tmp_disk_tables +61 Created_tmp_files +0 Created_tmp_tables +463 
- single stream `b_narrow_1`: 8000000 rows in 64.0s (source read done at 26.7s) → **14.48 MiB/s estimator bytes**, 9.47 MiB/s DATA_LENGTH, 125000 rows/s
- calibration `b_narrow_1`: peak excess 23 MiB of R 416.4 MiB → implied E = 1.6 (used 100)
- `b_wide_1`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (single-wide): idle worker **114 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **29 MiB**; worst (post-GC − idle) / ΣR = **0.067** at 7.0s (post-GC 143 MiB, ΣR 433.0 MiB); **0 of 43** post-GC figures above idle + ΣR
- peak RSS (single-wide, MiB): kafka=2039 postgres=403 schema-registry=173 mysql=369 connect=1414 
- source temp tables (single-wide): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +37 Created_tmp_files +0 Created_tmp_tables +139 
- single stream `b_wide_1`: 600000 rows in 21.6s (source read done at 15.1s) → **130.45 MiB/s estimator bytes**, 72.50 MiB/s DATA_LENGTH, 27778 rows/s
- calibration `b_wide_1`: peak excess 29 MiB of R 433.0 MiB → implied E = -1.0 (used 100)
- `b_lob_1`: M=1572879 B (large-record); `batch.max.rows=1`, `max.buffer.size=4`, `query.suffix=LIMIT 32`, Sink `max.poll.records=1`; R = 511.0 MiB
- heap (single-lob): idle worker **117 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **1403 MiB**; worst (post-GC − idle) / ΣR = **2.746** at 5.5s (post-GC 1520 MiB, ΣR 511.0 MiB); **43 of 53** post-GC figures above idle + ΣR
- peak RSS (single-lob, MiB): kafka=2041 postgres=254 schema-registry=177 mysql=392 connect=1561 
- source temp tables (single-lob): TempTable RAM high-water **17.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +31 Created_tmp_files +0 Created_tmp_tables +133 
- single stream `b_lob_1`: 1000 rows in 12.1s (source read done at 6.2s) → **190.81 MiB/s estimator bytes**, 126.57 MiB/s DATA_LENGTH, 83 rows/s
- calibration `b_lob_1`: peak excess 1403 MiB of R 511.0 MiB → implied E = 10.0 (used 3)
- `b_narrow_2`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_3`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_4`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_wide_2`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- `b_wide_3`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (concurrent): idle worker **110 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **4334 MiB**; worst (post-GC − idle) / ΣR = **1.247** at 7.4s (post-GC 4444 MiB, ΣR 3475.6 MiB); **25 of 270** post-GC figures above idle + ΣR
- peak RSS (concurrent, MiB): kafka=2217 postgres=1505 schema-registry=159 mysql=409 connect=6337 
- source temp tables (concurrent): TempTable RAM high-water **129.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +386 Created_tmp_files +0 Created_tmp_tables +6025 
- concurrent (8 boxes, 8 admitted at once, peak 8 running): whole run **85.01 MiB/s estimator bytes (50.90 MiB/s DATA_LENGTH) over 14452 MiB in 170.0s**
- all-streams-active window (first 44.8s): **150.86 MiB/s** estimator bytes
- per-table admit→done (s): b_lob_1=1.1→44.8 b_narrow_1=1.4→147.1 b_narrow_2=1.5→153.5 b_narrow_3=1.3→160.1 b_narrow_4=1.2→163.5 b_wide_1=1.0→96.2 b_wide_2=0.9→75.6 b_wide_3=0.0→83.8 
