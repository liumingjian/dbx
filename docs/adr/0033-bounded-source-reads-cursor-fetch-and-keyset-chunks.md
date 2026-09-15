---
status: accepted (amends ADR-0003's large-record envelope and ADR-0008's mandatory platform policies; supplies ADR-0031's read-ahead term; the settings table covers keyset reads only, and ADR-0037 sets bulk reads)
---

# Bounded Source reads: cursor fetch, byte-derived read-ahead, and keyset chunks

The plan never set the MySQL Source's read settings, and [#63](https://github.com/liumingjian/dbx/issues/63) showed that each default is unbounded in some direction ([#76](https://github.com/liumingjian/dbx/issues/76)):

- **No cursor fetch.** Without `useCursorFetch=true`, Connector/J buffers the whole result set in the Connect heap. A 270 MB table was "read" in 2.1 s, and eight concurrent boxes OOMed the worker.
- **Read-ahead counted in records.** kafka-connect-jdbc 10.9.6 calls `setFetchSize(batch.max.rows)` on every Source statement. That call overrides any URL `defaultFetchSize`. `batch.max.rows` defaults to 1000, and `max.buffer.size=0` means a queue of `4 × batch.max.rows` records. So an ordinary box can hold about 5000 rows, which at the 1 MiB ordinary-row ceiling exceeds the heap. With 1.5 MiB rows, this read-ahead alone OOMed a fresh 4 GiB worker.
- **Cursor fetch moves the cost to the source.** MySQL materializes a server-side cursor's whole result into an internal temporary table. That includes an index-ordered primary-key scan. Past `tmp_table_size` the table spills to disk in the customer's data directory, so an unchunked read writes a temp table as large as the source table.
- **Re-queries are paced only by `poll.interval.ms`.** With `query.suffix`, table-mode incrementing queries become `WHERE id > ? ORDER BY id ASC <suffix>`. The querier drains each result, waits until `lastUpdate + poll.interval.ms`, and re-queries from the last offset. The setting has no minimum and there is no backoff on empty results.

We decided that a Source's read is bounded in bytes on both sides: in the Connect heap and on the source server. Every bound derives from the box's frozen connector settings, never from connector or driver defaults.

## Bounded Source reads are a mandatory platform policy

Every source dialect must declare, as a typed execution requirement (ADR-0008), how its reads stay bounded in the Connect heap and on the source server. The core injects the settings and validates the normalized final configuration. No capability may omit or override them. A source dialect that cannot declare this requirement cannot be registered or certified.

For MySQL, every Source connection sets `useCursorFetch=true`. DBX does not set `defaultFetchSize`, because the connector's `setFetchSize(batch.max.rows)` always wins. Cursor fetch is not a DBA option and does not depend on table size.

## Settings

M is the largest exact row byte length across the box's tables, from ADR-0003's preflight. It is the same M ADR-0031 uses for the Sink. "pow2" means rounded down to a power of two.

| Setting | Ordinary box | Large-record box |
|---|---|---|
| `batch.max.rows` (also the JDBC fetch size) | `clamp(4 MiB ÷ M, 1, 1024)`, pow2 | 1 |
| `max.buffer.size` | equal to `batch.max.rows` | 4 |
| `query.suffix` | `LIMIT N`, with N = `clamp(64 MiB ÷ M, 1, 131072)`, pow2 | same formula |
| `poll.interval.ms` | 100 | 100 |

- **Execution signature.** All of these settings, plus `useCursorFetch`, are part of the execution signature and the configuration fingerprint. Every formula depends only on M's power-of-two class, the grouping ADR-0031's Sink already creates, so they add no signature groups.
- **Connect heap.** These settings supply ADR-0031's `read-ahead(#76)` term:

  ```text
  read-ahead = (batch.max.rows + max.buffer.size) × M × X
  ```

  The term is at most 8 MiB × X for an ordinary box and 5 × 20 MiB × X for a large-record box.
- **Source server.** Each Source connection's temporary table holds at most N × M, which is 64 MiB. A chunk above `tmp_table_size` may spill to disk; the spill is bounded, and DBX accepts it. The worst total is the source connection budget (ADR-0002) times 64 MiB. On the reference machine, that is 13 × 64 MiB.
- **Pacing.** `poll.interval.ms=100` adds 0.1 s per chunk. After the table is read, each table re-queries about ten times per second until DBX deletes the Source on read complete (ADR-0001).

The large-record column extends ADR-0003's envelope to its Source half, beside the Sink's single-record polling.

## Operator wording

A Source failing with MySQL error 1114 (`ER_RECORD_FILE_FULL`, table is full) gets a translation rule in ADR-0005's catalog. The rule says that DBX reads in chunks, using up to 64 MiB of source temporary space per connection. It tells the DBA to check the InnoDB temporary tablespace and the data directory's free disk. The interface does not announce this usage in advance: the bound is small, and an announcement would give the DBA information that needs no decision.

## Proof after an upgrade

A run can prove only that the frozen settings were delivered, which ADR-0008's validation of the normalized configuration already does. Whether the settings actually bound memory can only be shown under real load. ADR-0022's L3 suite therefore gains a **bounded read** scenario:

- **Setup.** A fresh worker at ADR-0031's 8 GiB tier (3 GiB heap).
- **Tables.** One large-record table, plus one narrow and one wide table, each much larger than the heap.
- **Pass condition.** No OOM. Each box's peak heap is at or below its R. The source's temporary-table peak stays at or below 64 MiB, observed through `performance_schema` or the InnoDB temporary tablespace delta.
- **When it must pass.** Every Connector/J version on the release allowlist (ADR-0027 E1) must pass, and a connector upgrade must pass again before release. A driver that ignores the fetch size, or a connector that changes how it sizes its buffers, fails here rather than at a customer.

The environment check gains no item for this, because a static check cannot observe the bound.

[#63](https://github.com/liumingjian/dbx/issues/63)'s single-stream band was measured on one host, with a fetch size of 1000 and no chunking. [#78](https://github.com/liumingjian/dbx/issues/78) re-measures at these settings.

## Considered options

- **Fixed `batch.max.rows=100` and `max.buffer.size=100`.** Rejected. It is bounded, but under cursor fetch every fetch is one network round trip. A narrow table at about 130k rows/s would make about 1300 round trips per second over the customer's LAN.
- **Connector defaults.** Rejected: about 5000 rows of read-ahead at 1 MiB each exceeds the heap.
- **No cursor fetch.** Rejected: the whole result set is buffered in the heap.
- **Connector/J streaming (`fetchSize=Integer.MIN_VALUE`).** Not available: the connector always calls `setFetchSize(batch.max.rows)` with a value of at least 1.
- **Unchunked reads with a warning about source disk.** Rejected: the source's free disk isn't observable through SQL, so DBX could warn but never prove. It would also silently write a table-sized temp table on the customer's database.
- **N from a 16 MiB budget, so chunks never spill at MySQL defaults.** Rejected: large-record tables would read one row per query, each followed by a 0.1 s wait.
- **`poll.interval.ms=0`.** Rejected: once the table is read, the task queries the source in a tight loop, about thousands of queries per second, until DBX deletes the Source.
- **`poll.interval.ms=5000`, the plan's old value.** Rejected: every chunk would wait 5 s.

## Consequences

- Source reads cost the source a bounded, explainable amount of temporary space. Throughput pays a 0.1 s gap per chunk.
- Changing any constant here changes execution signatures, so a recovering run keeps its frozen values (ADR-0008).
- A second source dialect, such as PostgreSQL with `autocommit=false` and a fetch size, must declare its own bounded read and pass the same L3 scenario.
