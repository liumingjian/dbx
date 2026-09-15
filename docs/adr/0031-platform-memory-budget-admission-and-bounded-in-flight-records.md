---
status: accepted (amends ADR-0002 admission, ADR-0003 Connect heap and connector overrides, ADR-0027 E5 and JVM observability)
---

# Admission by platform memory budget, with bounded in-flight records per box

At ADR-0002's default admission, eight single-table boxes exhausted ADR-0003's 4 GiB Connect heap in under 30 s on the reference machine. At 7.75 GiB the kernel killed the whole Connect container ([#63](https://github.com/liumingjian/dbx/issues/63), [#75](https://github.com/liumingjian/dbx/issues/75)). The cause is structural. Each record a Source has sent but Kafka has not yet acknowledged keeps its SourceRecord, converted ProducerRecord, callback, Thunk, future and SubmittedRecord reachable, about 1.65 KB of heap for a narrow row. Producer `buffer.memory` counts only compressed batch bytes, and Connect 3.9 has no setting that caps outstanding records. Connect also sets `max.block.ms=Long.MAX_VALUE`, so a full buffer blocks the task instead of failing it. With compressible narrow rows at a few dozen compressed bytes each, 128 MiB of buffer held about 2.45 million records.

We decided that every box has a heap reservation derived from its own connector settings, and that admission never lets the reservations of running boxes exceed the Connect heap. A bigger heap or a lower box limit alone bounds nothing; the next table shape would break it.

## Per-box bounds

The settings below are part of the execution signature.

- **Ordinary boxes, Source producer.** `buffer.memory=4194304` (4 MiB), `batch.size=262144`, `linger.ms=10`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, `acks=all`, `compression.type=zstd`. Idempotence keeps per-partition order at five in-flight requests, so the small buffer does not cost the ordering that `max.in.flight=1` bought. Ordinary rows are at most 1 MiB (ADR-0003), so 4 MiB still fits the largest record.
- **Ordinary boxes, Sink consumer.** `fetch.max.bytes=8388608`, `max.partition.fetch.bytes=2097152`. `max.poll.records` is `clamp(64 MiB ÷ M, 1, 500)` rounded down to a power of two, where M is the largest exact row byte length across the box's tables from ADR-0003's preflight. Narrow tables keep 500. The power-of-two rounding limits how finely this splits execution signatures.
- **Large record tables.** ADR-0003's settings stand: 128 MiB `buffer.memory`, 16 KiB `batch.size`, `max.in.flight.requests.per.connection=1`, Sink `max.poll.records=1` with 25/50 MiB fetch limits. Their record count is already bounded by row size.
- **Source read-ahead** (`batch.max.rows`, cursor fetch, `max.buffer.size`) is decided by [ADR-0033](0033-bounded-source-reads-cursor-fetch-and-keyset-chunks.md), which bounds it in bytes by M: `read-ahead = (batch.max.rows + max.buffer.size) × M × X`.

## Platform memory budget: the sixth admission gate

Each box's reservation is computed when the scheduling plan is made, from the box's settings and M:

```text
R = buffer.memory × E  +  read-ahead (ADR-0033)  +  2 × fetch.max.bytes  +  max.poll.records × M × X
```

A box is admitted only while the sum of R over running boxes stays at or below `Connect heap − B`. The gate is cumulative, like the Kafka disk budget, so large-box starvation protection applies unchanged. It combines with ADR-0002's other gates by taking the tightest limit. It is static: the heap in the formula is the effective heap read at the pre-admission environment check, never a live usage figure.

Provisional constants, versioned like the 25 MiB transport allowance and re-measured on connector, serializer, Kafka or JVM upgrades:

| Constant | Value | Basis |
|---|---|---|
| E, heap bytes per compressed in-flight byte | 100 | about 1.65 KB of heap ÷ about 20 compressed bytes per narrow row, rounded up |
| X, heap bytes per decoded source byte on the Sink | 3 | provisional |
| B, worker base overhead | 512 MiB | provisional |
| E for large-record boxes | 3 | 1.5 MiB rows keep a Struct `byte[]`, a serialized `byte[]` and a compressed batch |

With these values a narrow ordinary box reserves about 512 MiB, and a large-record box about 1 GiB. [#78](https://github.com/liumingjian/dbx/issues/78) calibrates the constants at the decided settings. It passes only if, for every shape, the measured peak heap per box is at or below that box's R.

## Deployment memory tiers

The Connect heap is fixed at deployment by host-memory tier, never adapted at runtime. Each tier's budget is stated in RSS, because the kernel kills a container on RSS, not heap. At the reference run, Connect's RSS exceeded its heap by about 0.86 GiB.

| Component (RSS) | 8 GiB tier (floor) | ≥16 GiB tier (recommended) |
|---|---|---|
| Connect heap | 3 GiB | 6 GiB |
| Connect non-heap, `-XX:MaxDirectMemorySize=512m` | 1 GiB | 1 GiB |
| Kafka heap + non-heap | 1 GiB + 0.5 GiB | 2 GiB + 0.5 GiB |
| Schema Registry | 0.75 GiB | 0.75 GiB |
| DBX with embedded H2 | 1 GiB | 1 GiB |
| OS and page cache headroom | 0.75 GiB | ≥4.75 GiB |

With the provisional constants, the 8 GiB tier admits about five narrow ordinary boxes, or one large-record box and three ordinary ones. The ≥16 GiB tier reaches ADR-0002's ten-box cap. The tier rows are provisional until #78 runs each tier. If the 8 GiB tier is still killed by the kernel there, the floor rises to 12 GiB.

## Observation

- DBX reads `java.lang:type=Memory` from Connect and Kafka over JMX. The port is open on the Compose network only and never published to the host.
- The environment check gains a memory-tier item. It concludes 不满足 (unsatisfied) when the host has less memory than its tier requires, or when an effective heap differs from the tier. ADR-0027's E5 minimum becomes this tier check. The item detects and explains; it changes nothing on the host.
- Live heap usage goes to 运行监控 (run monitoring) and the diagnostic package. It does not go to 运行状况 (runtime condition), which ADR-0021 keeps free of host memory, and it never feeds admission. If the bound fails anyway, ADR-0032 crashes the worker and fails its running boxes.

## Operator wording

When this gate is the binding limit, the reason reads **平台内存预算** (platform memory budget), beside the connection and disk budgets. Its explanation says only that this machine's memory decides how many tables can migrate at once, and that more host memory raises it. The interface never mentions heap, Connect or boxes (ADR-0030).

## Considered options

- **A larger heap and a higher memory floor alone.** Rejected: in-flight records stay unbounded, so a more compressible table breaks the larger heap too.
- **A lower default box limit alone.** Rejected: the right limit depends on table shape and host memory, and a fixed number is neither safe nor explainable.
- **Admitting on live heap usage.** Rejected for ADR-0002's reason against runtime-adaptive concurrency, and because ADR-0021's runtime condition observes and never adjudicates.
- **Lowering `max.block.ms` to fail Sources under pressure.** Rejected: Connect treats buffer exhaustion as retriable, so it trades memory pressure for churn without bounding the heap.

## Consequences

- Small hosts run fewer boxes at once instead of failing. The duration estimate (ADR-0019) sees that through the scheduling plan's concurrency.
- Row-width-dependent `max.poll.records` creates more execution-signature groups. We accept this, as ADR-0002 does for incrementing-column differences.
- The Compose file sets each tier's heaps, and DBX's memory-tier item, from one tier variable.
