# s9 —— reference throughput band

实测时间：2026-09-15T16:01:03+08:00

- ADR-0002 default budgets here: Connect tasks 24 (2 x 12 vCPU), boxes 10, source connections 13 (max_connections 151), target connections 8 (max_connections 100) → at most **8** concurrent single-table boxes
- ADR-0031 platform memory budget: Connect heap 6144 MiB − B 512 MiB = **5632 MiB** of box reservations (E=100, E_lob=3, X=3); tier 16
- Source (every table, ADR-0033): `mode=incrementing` (ADR-0001), `tasks.max=1`, `poll.interval.ms=100`, MySQL URL `useCursorFetch=true` without `defaultFetchSize`; ordinary producer (ADR-0031) `buffer.memory=4 MiB`, `batch.size=256 KiB`, `linger.ms=10`, idempotence on, `acks=all`, in-flight 5, zstd; large-record producer (ADR-0003) 128 MiB buffer, 16 KiB batch, 25 MiB request, in-flight 1, zstd
- Sink: `insert.mode=insert`, `pk.mode=none`, `quote.sql.identifiers=always` (plan §7.4); `batch.size` unset → connector default 3000; fetch limits 8/2 MiB ordinary, 50/25 MiB large-record; `max.poll.interval.ms=900000`
- `b_narrow_1`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- heap (single-narrow): idle worker **111 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **22 MiB**; worst (post-GC − idle) / ΣR = **0.053** at 4.0s (post-GC 133 MiB, ΣR 416.4 MiB); **0 of 137** post-GC figures above idle + ΣR
- peak RSS (single-narrow, MiB): kafka=1903 postgres=380 schema-registry=306 mysql=574 connect=1346 
- source temp tables (single-narrow): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **18.0 MiB**; Created_tmp_disk_tables +61 Created_tmp_files +0 Created_tmp_tables +464 
- single stream `b_narrow_1`: 8000000 rows in 64.3s (source read done at 27.7s) → **14.42 MiB/s estimator bytes**, 9.42 MiB/s DATA_LENGTH, 124417 rows/s
- calibration `b_narrow_1`: peak excess 22 MiB of R 416.4 MiB → implied E = 1.4 (used 100)
- `b_wide_1`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (single-wide): idle worker **113 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **33 MiB**; worst (post-GC − idle) / ΣR = **0.076** at 13.5s (post-GC 146 MiB, ΣR 433.0 MiB); **0 of 42** post-GC figures above idle + ΣR
- peak RSS (single-wide, MiB): kafka=2023 postgres=385 schema-registry=307 mysql=578 connect=1378 
- source temp tables (single-wide): TempTable RAM high-water **17.0 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +37 Created_tmp_files +0 Created_tmp_tables +139 
- single stream `b_wide_1`: 600000 rows in 21.1s (source read done at 17.4s) → **133.55 MiB/s estimator bytes**, 74.22 MiB/s DATA_LENGTH, 28436 rows/s
- calibration `b_wide_1`: peak excess 33 MiB of R 433.0 MiB → implied E = 0.0 (used 100)
- `b_lob_1`: M=1572879 B (large-record); `batch.max.rows=1`, `max.buffer.size=4`, `query.suffix=LIMIT 32`, Sink `max.poll.records=1`; R = 511.0 MiB
- heap (single-lob): idle worker **117 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **1853 MiB**; worst (post-GC − idle) / ΣR = **3.626** at 6.0s (post-GC 1970 MiB, ΣR 511.0 MiB); **38 of 48** post-GC figures above idle + ΣR
- peak RSS (single-lob, MiB): kafka=2154 postgres=211 schema-registry=303 mysql=587 connect=1520 
- source temp tables (single-lob): TempTable RAM high-water **17.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +31 Created_tmp_files +0 Created_tmp_tables +133 
- single stream `b_lob_1`: 1000 rows in 12.2s (source read done at 6.4s) → **189.25 MiB/s estimator bytes**, 125.53 MiB/s DATA_LENGTH, 82 rows/s
- calibration `b_lob_1`: peak excess 1853 MiB of R 511.0 MiB → implied E = 13.5 (used 3)
- `b_narrow_2`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_3`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_narrow_4`: M=55 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 131072`, Sink `max.poll.records=500`; R = 416.4 MiB
- `b_wide_2`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- `b_wide_3`: M=2330 B; `batch.max.rows=1024`, `max.buffer.size=1024`, `query.suffix=LIMIT 16384`, Sink `max.poll.records=500`; R = 433.0 MiB
- heap (concurrent): idle worker **123 MiB** post-GC (ADR-0031 B=512); peak post-GC excess over idle **4653 MiB**; worst (post-GC − idle) / ΣR = **1.339** at 8.4s (post-GC 4776 MiB, ΣR 3475.6 MiB); **37 of 280** post-GC figures above idle + ΣR
- peak RSS (concurrent, MiB): kafka=2183 postgres=1413 schema-registry=235 mysql=721 connect=6849 
- source temp tables (concurrent): TempTable RAM high-water **129.5 MiB** (all connections), mmap high-water 0.0 MiB; largest session temp tablespace **52.0 MiB**; Created_tmp_disk_tables +386 Created_tmp_files +0 Created_tmp_tables +6052 
