# DBX Migration

DBX coordinates offline, full-table migrations as independently observable table migrations while treating connector groupings as disposable scheduling artifacts.

## Language

**Migration task**:
A user-approved migration of one source MySQL database into one PostgreSQL schema, composed of table migration units.
_Avoid_: Job, batch

**Table migration unit**:
The durable, independently observable migration record for one source table, including its baseline, progress, validation result, and errors.
_Avoid_: Table job, connector table

**Run**:
One immutable execution attempt over a selected set of table migration units. A rerun is a new run with a new plan and identity.
_Avoid_: Retry in place, resumed run

**Box**:
A run-local, disposable scheduling group containing tables that share one Source connector and one Sink connector. A box is not a user-selected migration unit and never owns a table's durable result.
_Avoid_: Task, batch, shard

**Execution signature**:
The connector-level configuration identity that determines whether tables may share a box. Only tables with identical execution signatures may be packed together.
_Avoid_: Table profile, connector type

**Scheduling plan**:
The immutable assignment and ordering of boxes within a run. Resource availability may delay admission but never repacks an existing run.
_Avoid_: Queue snapshot, live packing

**Rolling admission**:
Starting the next eligible waiting box whenever task, connection, box-count, and disk budgets permit, without a wave-level completion barrier.
_Avoid_: Batch execution, strict waves

**Large record table**:
A table whose preflight finds a field or conservatively estimated row larger than 1 MiB, requiring an isolated box and large-record connector settings. A field or row above 20 MB is unsupported in v1.
_Avoid_: LOB table, big table
