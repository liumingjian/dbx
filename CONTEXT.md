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
The durable, independently observable migration record for one source table and its corresponding target table within one migration run, including phase, outcome, baseline, progress, validation result, and errors. A rerun creates a new table migration unit rather than changing the old unit's result.
_Avoid_: Table task, connector table

**Database connection**:
A reusable database endpoint and identity whose structured access semantics are versioned independently from migration tasks. A migration run freezes the database, schema, effective connection semantics, database-instance identity, and credential versions it uses.
_Avoid_: JDBC URL, datasource

**Credential version**:
An immutable version of secret authentication material referenced by connections and runs. A run retains its initial version and any explicitly adopted recovery replacement as audited bindings; historical use remains auditable after the recoverable secret is destroyed.
_Avoid_: Password field, current password

**Table write contract**:
The immutable, single-table write intent that DBX must prove before starting a Sink, derived from approved source metadata, preflight findings, and mapping exceptions. DDL is one rendering of this contract, not an independent configuration.
_Avoid_: Editable DDL, sink schema

**Validation disposition**:
An operator's audited decision about a failed or inconclusive validation result. Accepting risk may close the workflow but never changes the technical validation result to passed.
_Avoid_: Manual pass, overridden result

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
The externally enforced, time-bounded operational commitment that source data covered by a migration run does not change. It has an accountable operator and expiry; it must remain valid from source-baseline capture until every selected table reaches a validation terminal state or execution stops.
_Avoid_: Maintenance mode, pause, permanent checkbox

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
A separately confirmed destructive operation that removes only a stopped run's resources that the run can still prove it exclusively owns, while retaining its audit record and original technical outcomes. A later run's target data is never discardable by an earlier run.
_Avoid_: Cancel, retry, cleanup, rollback

**Target generation**:
The exclusive write epoch created when DBX first creates or deliberately clears a target table for a migration run. It prevents an earlier run from discarding target data after a later run has taken ownership.
_Avoid_: Table version, run number

**Error occurrence**:
An immutable fact that DBX observed at a phase and scope, retaining the evidence and correlation needed to explain what happened. It is not itself a workflow outcome.
_Avoid_: Translated error, failure message

**Diagnosis**:
A versioned interpretation of an error occurrence, identified by a stable diagnosis code and classified by phase, root-cause domain, and trusted scope. An unknown or conflicting diagnosis must not invent a cause.
_Avoid_: Error status, blame

**Diagnosis rule**:
A maintained evidence pattern that maps one or more external failure signatures to one operator-facing diagnosis, description, and action. The v1 first-release set contains 20 external-translation rule families; platform-structured diagnoses do not count toward that set.
_Avoid_: Exception mapping, regex error

**Root-cause domain**:
The single primary domain assigned to a diagnosis: user input, source database, target database, Kafka Connect, Kafka, runtime environment, or platform. It describes diagnostic evidence, not a person to blame.
_Avoid_: Responsible party

**Routing snapshot**:
The immutable run-local mapping from topic and connector coordinates to boxes, table migration units, source/target objects, and approved field mappings. It is the authority for table and field location; topic names and exception text are only corroborating evidence.
_Avoid_: Topic parsing, inferred table

**Diagnostic package**:
A local, bounded, redacted export of run context, timeline, rule evidence, configuration fingerprints, versions, and raw failure details for support. It never includes credentials, record values, or automatic external upload.
_Avoid_: Log bundle, data dump
