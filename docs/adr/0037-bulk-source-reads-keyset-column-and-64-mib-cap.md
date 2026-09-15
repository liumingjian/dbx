---
status: accepted (amends ADR-0001's bulk path and ADR-0033's settings table)
---

# Bulk Source reads: the keyset column, bulk settings, and a 64 MiB cap

ADR-0033 gave every box `query.suffix=LIMIT N` and `poll.interval.ms=100`, but its facts cover only table-mode incrementing reads. ADR-0001 still sends tables without a "usable monotonic incrementing column" through a one-pass bulk read at `poll.interval.ms=2147483647`, and no document defined "usable" ([#84](https://github.com/liumingjian/dbx/issues/84)). Literally combined, the two break: kafka-connect-jdbc 10.9.6's `BulkTableQuerier` builds `SELECT * FROM t` and appends the suffix, so every bulk poll reads the same first N rows, and a 100 ms interval repeats it. The Source delivers duplicates and never reaches the baseline. Dropping the suffix is not enough: under cursor fetch, MySQL materializes the whole result into a temporary table as large as the source table, which ADR-0033 rejected because DBX cannot prove the source has room.

We decided that a table is read in keyset chunks whenever it has a keyset column. Only tables without one take the bulk path, and only while the whole read fits one chunk's source bound.

## The keyset column

A table's **keyset column** (键集列) is a single column that is integer-typed, `NOT NULL`, and unique (its primary key or a unique index), with a minimum of 0 or more and a maximum of at most 2⁶³−1 at the source baseline. Monotonic growth is not required: the write freeze keeps the source still, so `WHERE c > ? ORDER BY c LIMIT N` visits every row once whatever order the values were inserted in.

- **Minimum of 0 or more.** The connector starts from offset −1 when none is stored (`TimestampIncrementingOffset.getIncrementingOffset`), and 10.9.6 has no setting to change it. Rows at or below −1 would be skipped without error.
- **Maximum of at most 2⁶³−1.** The offset is a Java `long`; larger `BIGINT UNSIGNED` values overflow (#5).
- **Choice.** When several columns qualify, DBX takes the primary key, and otherwise the unique index with the lowest name in the source collation. The choice is part of the execution signature, as incrementing-column names already are (ADR-0002).

Preflight evaluates these conditions, and the source baseline re-verifies the minimum and maximum. If the baseline disagrees with preflight, the table fails before its box starts.

## Bulk path

A table without a keyset column (a composite, string, or UUID key, or no key) is read in bulk:

| Setting | Bulk box |
|---|---|
| `useCursorFetch` | `true` |
| `batch.max.rows`, `max.buffer.size` | ADR-0033's formulas, so ADR-0031's read-ahead term is unchanged |
| `query.suffix` | empty |
| `poll.interval.ms` | `2147483647` (ADR-0001) |

Mode is a connector-level setting, so bulk tables already form their own execution-signature groups.

## 64 MiB cap

A bulk read is one cursor, so its source temporary table holds the whole table. The bulk path is admitted only while the table's baseline row count × its exact largest row byte length (ADR-0003) is at most 64 MiB, which is the same per-connection bound as one ADR-0033 chunk. ADR-0033's source-side proof and its L3 bounded-read scenario therefore hold for every read without a second budget.

A larger table without a keyset column is a **阻塞** (blocking) preflight finding (ADR-0029). The explanation says that the table is too large to read safely without a single integer unique key and suggests adding one, such as an auto-increment column. It never mentions Kafka, Connect, cursors, or temporary tables (ADR-0030). ADR-0005's rule for an invalid or unusable incrementing column remains as the runtime fallback.

## Considered options

- **Bulk without a cap, disclosing the table-sized temporary table per table.** Rejected for ADR-0033's reason: DBX could warn but never prove the source has room.
- **A separate larger bulk budget, such as 1 GiB, counted against the source connection budget.** Rejected: it adds a second source-side bound to certify and still blocks tables above it.
- **Keeping "monotonic" in the definition.** Rejected: under the write freeze it only sends more tables through bulk, where they meet the cap.
- **Synthesizing a key in `query` mode, e.g. `ROW_NUMBER() OVER (ORDER BY pk)`.** Rejected: the window sort materializes the whole table on the source anyway.

## Consequences

- v1 blocks large tables whose only unique key is composite or non-integer, such as `order_items(order_id, line_no)`. This is the price of a single provable source bound.
- The 24-hour bulk run limit in ADR-0001 now covers only reads of 64 MiB or less.
- The L3 bounded-read scenario gains a bulk table at the cap, and a keyset table with a non-monotonic key and a zero minimum.
