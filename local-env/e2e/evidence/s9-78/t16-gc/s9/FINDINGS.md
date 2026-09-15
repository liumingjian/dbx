# s9 —— reference throughput band

实测时间：2026-09-15T16:19:17+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- ADR-0031 platform memory budget: Connect heap 6144 MiB − B 512 MiB = **5632 MiB** of box reservations (E=100, E_lob=3, X=3); tier 16
- Source (every table, ADR-0033): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=100`, MySQL URL `useCursorFetch=true` without `defaultFetchSize`; ordinary producer (ADR-0031) `buffer.memory=4 MiB`, `batch.size=256 KiB`, `linger.ms=10`, idempotence on, `acks=all`, in-flight 5, zstd; large-record producer (ADR-0003) 128 MiB buffer, 16 KiB batch, 25 MiB request, in-flight 1, zstd
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; fetch limits 8/2 MiB ordinary, 50/25 MiB large-record; `max.poll.interval.ms=900000`
- `b_narrow_1`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- heap (single-narrow): idle worker **135 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **84 MiB**; worst (post-GC − idle) / ΣR = **0.202** at 16.1s (post-GC 219 MiB, ΣR 416.4 MiB); **0 of 154** post-GC figures above idle + ΣR; forced full GC every 5s
- peak RSS (single-narrow, MiB): kafka=2121 postgres=454 schema-registry=181 mysql=390 connect=1335 
- source temp tables (single-narrow): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **18.0 MiB**; Created_tmp_disk_tables +61 Created_tmp_files +0 Created_tmp_tables +513 
- single stream `b_narrow_1`: 8000000 rows in 66.6s (source read done at 28.2s) → **13.92 MiB/s estimator bytes**, 9.10 MiB/s DATA_LENGTH, 120120 rows/s
- calibration `b_narrow_1`: peak excess 84 MiB of R 416.4 MiB → implied E = 16.9 (used 100)
- `b_wide_1`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (single-wide): idle worker **110 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **31 MiB**; worst (post-GC − idle) / ΣR = **0.072** at 13.8s (post-GC 141 MiB, ΣR 433.0 MiB); **0 of 45** post-GC figures above idle + ΣR; forced full GC every 5s
- peak RSS (single-wide, MiB): kafka=2071 postgres=428 schema-registry=187 mysql=413 connect=1395 
- source temp tables (single-wide): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +37 Created_tmp_files +0 Created_tmp_tables +139 
- single stream `b_wide_1`: 600000 rows in 21.3s (source read done at 17.4s) → **132.29 MiB/s estimator bytes**, 73.52 MiB/s DATA_LENGTH, 28169 rows/s
- calibration `b_wide_1`: peak excess 31 MiB of R 433.0 MiB → implied E = -0.5 (used 100)
- `b_lob_1`: M=1572879 B (large-record); `batch.max.rows=1`, `max.buffer.size=4`, `query.suffix=LIMIT 32`, Sink `max.poll.records=1`; R = 511.0 MiB
- heap (single-lob): idle worker **118 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **883 MiB**; worst (post-GC − idle) / ΣR = **1.728** at 3.8s (post-GC 1001 MiB, ΣR 511.0 MiB); **22 of 30** post-GC figures above idle + ΣR; forced full GC every 5s
- peak RSS (single-lob, MiB): kafka=2082 postgres=277 schema-registry=182 mysql=430 connect=1907 
- source temp tables (single-lob): TempTable RAM high-water **17.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +31 Created_tmp_files +0 Created_tmp_tables +133 
- single stream `b_lob_1`: 1000 rows in 12.3s (source read done at 6.1s) → **187.71 MiB/s estimator bytes**, 124.51 MiB/s DATA_LENGTH, 81 rows/s
- calibration `b_lob_1`: peak excess 883 MiB of R 511.0 MiB → implied E = 5.9 (used 3)
- `b_narrow_2`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_3`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_4`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_wide_2`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- `b_wide_3`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (concurrent): idle worker **115 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **5313 MiB**; worst (post-GC − idle) / ΣR = **1.529** at 9.9s (post-GC 5428 MiB, ΣR 3475.6 MiB); **39 of 554** post-GC figures above idle + ΣR; forced full GC every 5s
- peak RSS (concurrent, MiB): kafka=2033 postgres=1489 schema-registry=178 mysql=414 connect=6390 
- source temp tables (concurrent): TempTable RAM high-water **129.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +386 Created_tmp_files +0 Created_tmp_tables +6112 
- concurrent (8 boxes, 8 admitted at once, peak 8 running): whole run **82.82 MiB/s estimator bytes (49.59 MiB/s DATA_LENGTH) over 14452 MiB in 174.5s**
- all-streams-active window (first 46.1s): **153.69 MiB/s** estimator bytes
- per-table admit→done (s): b_lob_1=0.8→46.1 b_narrow_1=1.1→149.2 b_narrow_2=1.2→157.2 b_narrow_3=1.0→162.6 b_narrow_4=0.9→167.1 b_wide_1=0.7→74.3 b_wide_2=0.6→80.9 b_wide_3=0.0→87.7 
