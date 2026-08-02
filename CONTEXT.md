# DBX Migration

DBX describes offline, one-time full migrations as independently observable table migrations while treating connector groupings as disposable scheduling artifacts. This glossary fixes the language used across the product and technical specifications.

## Language

**Migration task**:
A user-approved migration of one source MySQL database into one PostgreSQL schema, composed of table migration units.
_Avoid_: Job, connector

**Migration run**:
One immutable execution attempt over all or part of a migration task, with its own run identifier, source baseline, scheduling plan, connectors, and topics. A rerun is a new migration run.
_Avoid_: Retry in place, connector run, resumed run

**Table migration unit**:
The durable, independently observable migration record for one source table and its corresponding target table, including state, baseline, progress, validation result, and errors.
_Avoid_: Table task, connector table

**Box**:
A run-local, disposable scheduling group of table migration units that share one Source connector and one Sink connector. It is not user-selected, has no independent business lifecycle, and never owns a table's durable result.
_Avoid_: Batch, task group, shard

**Execution signature**:
The connector-level configuration identity that determines whether tables may share a box. Only tables with identical execution signatures may be packed together.
_Avoid_: Table profile, connector type

**Scheduling plan**:
The immutable assignment and ordering of boxes within a migration run. Resource availability may delay admission but never repacks an existing run.
_Avoid_: Queue snapshot, live packing

**Rolling admission**:
Starting the next eligible waiting box whenever task, connection, box-count, and disk budgets permit, without a wave-level completion barrier.
_Avoid_: Batch execution, strict waves

**Large record table**:
A table whose preflight finds an individual source value or conservatively measured source row larger than 1 MiB (1,048,576 bytes), requiring an isolated box and large-record connector settings.
_Avoid_: LOB table, big table

**Large-record envelope**:
The v1 support boundary requiring every individual source value and the total pre-serialization payload of every source row to be at most 20 MiB (20,971,520 bytes). Kafka transports that payload through a separate 25 MiB technical envelope for encoding and protocol overhead.
_Avoid_: 20 MB message limit, Kafka limit, field-size limit

**Source baseline**:
The immutable boundary of a migration run, captured while source writes are frozen. It includes an exact row count for every selected table and, where applicable, the terminal value of its monotonic primary key.
_Avoid_: Estimated row count, snapshot

**Write freeze**:
The externally enforced period in which source data covered by a migration run does not change. A confirmed write freeze is required before DBX captures the source baseline and starts migration.
_Avoid_: Maintenance mode, pause

**Read complete**:
The boundary at which every topic in a box contains the source baseline row count, production has remained stable for two polling intervals, and the healthy Source connector can be removed.
_Avoid_: Migration complete, connector complete

**Write complete**:
The boundary after read completion at which every Sink consumer lag is zero, every target table has the source baseline row count, and those signals have remained stable for two polling intervals.
_Avoid_: Migration complete, validation complete

**Migration complete**:
The boundary at which a table migration unit is write-complete and all enabled validation checks have passed.
_Avoid_: Read complete, write complete, connector stopped

**Stuck**:
A terminal diagnosis for a box that shows no observable progress for the configured hard threshold while its connectors still report healthy. DBX stops its connectors but preserves topics and target data for diagnosis.
_Avoid_: Slow, failed, timed out

**Cancellation**:
A user-requested terminal stop of a migration run that preserves topics, target data, and diagnostic evidence.
_Avoid_: Discard, delete, rollback

**Discard**:
A separately confirmed destructive operation that removes a stopped run's target data and requests deletion of its topics while retaining its audit record.
_Avoid_: Cancel, retry, cleanup
