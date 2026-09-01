# Single-table execution granularity without sharding

DBX v1 keeps one source table and its corresponding target table as one table migration unit throughout discovery, preflight, contract approval, extraction, transport, writing, completion detection, validation, rerun, and cleanup. A box may group compatible table migration units under shared connectors, but v1 never divides one table into independently scheduled shards or later recombines shard results.

## Execution invariant

A nonempty table produces one approved table write contract, one source baseline, one routing entry, one data topic, and one logical Source-to-Sink execution within a migration run. Its topic may have only the partitioning established by DBX's fixed topic policy; DBX does not assign primary-key ranges, predicates, or connector instances to parallel fragments of the table. The table owns its progress, read/write completion evidence, validation executions, technical outcome, topic and subject cleanup, and target generation.

Boxes are capacity and connector-configuration groupings. Packing several tables into one box does not merge their baselines or results. Isolating a query-mode, naming-exception, large-record, or oversized ordinary table in a box does not turn that box into a business shard. A failed shared connector is attributed according to diagnostic evidence; it never creates synthetic partial-table success.

An exactly empty table bypasses Kafka and still follows one table contract and validation result. A rerun selects one or more whole tables, creates a new migration run and table migration units, acquires target leases, clears the selected target generations under ADR-0006, and copies each selected table in full. V1 offers no data-level checkpoint continuation or partial-range rerun after continuity is lost.

## Throughput boundary

DBX does not promise to exceed one JDBC Source extraction stream for a single table. Whole-task throughput scales through multiple boxes and multiple tables subject to CPU, connection, Connect-task, and Kafka-disk gates in ADR-0002. A single large table remains bounded by its source query/JDBC read rate, Connect task, Kafka path, Sink writes, target primary-key maintenance, and, for a large-record table, single-record consumer polling.

Planning estimates and progress ranges must state this limit honestly. A table that cannot meet a future time objective without partitioned extraction is outside v1 rather than silently receiving an experimental split.

## Future decision boundary

Single-table sharding changes the consistency model, not merely the scheduler. A future decision must define a stable source snapshot across ranges, shard predicates and skew, range ownership, ordering, topic/partition routing, concurrent target writes and key conflicts, shard-level recovery, cancellation, cleanup, whole-table completion, validation aggregation, rerun scope, and the relationship between shard execution and the durable table migration unit.

The source dialect or database pair cannot add sharding through a capability, arbitrary query, or connector property. ADR-0008 requires a separate architecture decision because partitioned execution changes lifecycle and correctness gates. V1 therefore reserves no speculative public SPI for shards.

## Consequences

The durable model stays table-centered and every completion, validation, error, and rerun statement has one unambiguous scope. Scheduling can optimize whole-task makespan without exposing connector or partition internals to the DBA.

Very large single tables may underutilize available infrastructure and can dominate migration duration. This is an explicit v1 limitation accepted in exchange for avoiding inconsistent range snapshots, duplicate or missing rows, and partial-table recovery semantics.

## Rejected alternatives

- **Primary-key range splitting across Source connectors** was rejected because independently timed queries do not provide a consistent whole-table snapshot under the v1 external write-freeze model without additional proof and lifecycle rules.
- **Multiple Sink connectors writing one target table as implicit shards** was rejected because progress, duplicate attribution, cancellation, and recovery would become ambiguous.
- **Kafka partition count as the table-sharding model** was rejected because transport partitions do not define source extraction ranges or whole-table correctness.
- **A placeholder shard SPI in v1** was rejected because its types would guess at unresolved consistency and recovery decisions.
