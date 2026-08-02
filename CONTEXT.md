# DBX Migration

DBX describes an offline, one-time full migration of a source database into a target database. This glossary fixes the language used across the product and technical specifications.

## Language

**Migration task**:
A user-defined migration of one source database into one target schema, comprising a set of table migration units.
_Avoid_: Job, connector

**Migration run**:
One execution attempt of all or part of a migration task, with its own immutable run identifier, source baseline, connectors, and topics.
_Avoid_: Retry, connector run

**Table migration unit**:
The smallest independently tracked migration unit: one source table and its corresponding target table, state, progress, validation result, and errors.
_Avoid_: Table task, connector task

**Box**:
A disposable scheduling group of table migration units that share one Source connector and one Sink connector during a migration run. It has no independent business lifecycle.
_Avoid_: Batch, task group

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
