# DBX Migration

DBX describes offline, one-time full migrations as independently observable table migrations while treating connector groupings as disposable scheduling artifacts. This glossary fixes the language used across the product and technical specifications. Each term also carries `_中文_`, the canonical Simplified Chinese wording for that concept: operator-facing Chinese text is domain language, not presentation, so the user interface must not invent synonyms for it.

## Language

**Migration task**:
A user-approved migration of one source MySQL database into one PostgreSQL schema, composed of table migration units.
_Avoid_: Job, connector
_中文_: 迁移任务

**Migration draft**:
An unapproved, discardable working set of wizard selections and per-table configuration that has not yet become a migration task. It produces no migration run, is never referenced as audit evidence, and may be deleted without trace.
_Avoid_: Unsaved task, pending task, unapproved migration task
_中文_: 迁移草稿

**Migration run**:
One immutable execution attempt over all or part of a migration task, with its own run identifier, source baseline, scheduling plan, connectors, and topics. A rerun is a new migration run.
_Avoid_: Retry in place, connector run, resumed run
_中文_: 迁移运行

**Table migration unit**:
The durable, independently observable migration record for one source table and its corresponding target table within one migration run, including phase, outcome, baseline, progress, validation result, and errors. A rerun creates a new table migration unit rather than changing the old unit's result.
_Avoid_: Table task, connector table
_中文_: 表迁移单元

**Database connection**:
A reusable database endpoint and identity whose structured access semantics are versioned independently from migration tasks. A migration run freezes the database, schema, effective connection semantics, database-instance identity, and credential versions it uses.
_Avoid_: JDBC URL, datasource
_中文_: 数据库连接

**Credential version**:
An immutable version of secret authentication material referenced by connections and runs. A run retains its initial version and any explicitly adopted recovery replacement as audited bindings; historical use remains auditable after the recoverable secret is destroyed.
_Avoid_: Password field, current password
_中文_: 凭据版本

**Data source management**:
The product surface on which database connections and their credential versions are registered, verified, and maintained. It is a navigation area, not an entity: the thing it manages is always a database connection. Connection creation and credential entry happen only here, never inline inside the migration wizard.
_Avoid_: Connection wizard, inline credentials
_中文_: 数据源

Its Chinese wording names the navigation area alone. An individual endpoint is a 数据库连接 and never a 数据源, which is why `Database connection` still lists `datasource` under `_Avoid_`.

**Mapping rule**:
A structured, reviewable exception to DBX's automatic table or column mapping. A rule names one source coordinate, one bounded action, its target value, and whether DBX or the user produced it; user rules override automatic rules. Rules never contain arbitrary SQL or regular expressions in v1.
_Avoid_: Mapping script, route expression
_中文_: 映射规则

**Preflight**:
A source-side proof required before a table write contract may be approved. It evaluates exact value-domain and transport facts and concludes `SUPPORTED`, `UNSUPPORTED`, or `INCONCLUSIVE`; only `SUPPORTED` may proceed, and a new source baseline is still required after the write freeze.
_Avoid_: Validation, estimate, warning acknowledgement
_中文_: 预检

**Table write contract**:
The immutable, single-table write intent that DBX must prove before starting a Sink, derived from approved source metadata, preflight findings, and mapping rules. DDL is one rendering of this contract, not an independent configuration.
_Avoid_: Editable DDL, sink schema
_中文_: 表写入契约

**Structural proof**:
The deterministic comparison of the actual PostgreSQL table, identifiers, types, nullability, defaults, primary key, identity or sequence, routing, and JDBC binding compatibility against the approved table write contract. Only zero difference permits the Sink to start.
_Avoid_: Probe insert, table exists check
_中文_: 结构证明

**Supplemental SQL**:
The executable post-migration script that preserves source metadata for target structures deliberately outside the v1 writable-table contract, such as unique constraints, ordinary indexes, foreign keys, comments, and collation-related work. DBX v1 delivers it but does not execute it as part of migration.
_Avoid_: Migrated constraints, automatic post-DDL
_中文_: 补建 SQL

**Source dialect**:
The versioned description of one supported source database family, responsible for interpreting its metadata, value and identifier semantics, exact source-side facts, and extraction requirements. It supplies facts and plans but never advances migration workflow.
_Avoid_: Source connector, source adapter
_中文_: 源方言

**Target dialect**:
The versioned description of one supported target database family, responsible for interpreting its types, identifiers and structures and for deriving target operations and structural proof from a table write contract. It supplies facts and plans but never advances migration workflow.
_Avoid_: Sink connector, target adapter
_中文_: 目标方言

**Database pair**:
An explicitly supported, directed and versioned conversion relationship from one source dialect to one target dialect. It owns cross-database mapping and compatibility decisions; the presence of two endpoint dialects alone does not imply a supported pair.
_Avoid_: Automatic dialect combination, bidirectional pair
_中文_: 数据库对

**Validation plan**:
The immutable, versioned set of enabled, disabled, and not-applicable validation checks for one table migration unit, with comparison semantics and evidence requirements fixed before execution.
_Avoid_: Validation options, best-effort checks
_中文_: 校验计划

**Validation execution**:
One retained attempt to execute a validation plan after write completion. Its items conclude `PASS`, `FAIL`, `INCONCLUSIVE`, `NOT_APPLICABLE`, or `NOT_RUN`; a later execution or disposition never rewrites the original result.
_Avoid_: Validation status, check retry
_中文_: 校验执行

**Validation disposition**:
An operator's audited decision about a failed or inconclusive validation result. Accepting risk may close the workflow but never changes the technical validation result to passed.
_Avoid_: Manual pass, overridden result
_中文_: 校验处置

**Box**:
A run-local, disposable scheduling group of table migration units that share one Source connector and one Sink connector. It is not user-selected, has no independent business lifecycle, and never owns a table's durable result.
_Avoid_: Batch, task group, shard
_中文_: 箱

**Execution signature**:
The connector-level configuration identity that determines whether tables may share a box. Only tables with identical execution signatures may be packed together.
_Avoid_: Table profile, connector type
_中文_: 执行签名

**Scheduling plan**:
The immutable assignment and ordering of boxes within a migration run. Resource availability may delay admission but never repacks an existing run.
_Avoid_: Queue snapshot, live packing
_中文_: 调度计划

**Rolling admission**:
Starting the next eligible waiting box whenever task, connection, box-count, and disk budgets permit, without a wave-level completion barrier.
_Avoid_: Batch execution, strict waves
_中文_: 滚动准入

**Large record table**:
A table whose preflight finds an individual source value or conservatively measured source row larger than 1 MiB (1,048,576 bytes), requiring an isolated box and large-record connector settings.
_Avoid_: LOB table, big table
_中文_: 大记录表

**Large-record envelope**:
The v1 support boundary requiring every individual source value and the total pre-serialization payload of every source row to be at most 20 MiB (20,971,520 bytes). Kafka transports that payload through a separate 25 MiB technical envelope for encoding and protocol overhead.
_Avoid_: 20 MB message limit, Kafka limit, field-size limit
_中文_: 大记录包络

**Source baseline**:
The immutable boundary of a migration run, captured while source writes are frozen. It includes an exact row count for every selected table and, where applicable, the terminal value of its monotonic primary key.
_Avoid_: Estimated row count, snapshot
_中文_: 源基线

**Write freeze**:
The externally enforced, time-bounded operational commitment that source data covered by a migration run does not change. It has an accountable operator and expiry; it must remain valid from source-baseline capture until every selected table reaches a validation terminal state or execution stops.
_Avoid_: Maintenance mode, pause, permanent checkbox
_中文_: 写冻结

**Read complete**:
The boundary at which every topic in a box contains the source baseline row count, production has remained stable for two polling intervals, and the healthy Source connector can be removed.
_Avoid_: Migration complete, connector complete
_中文_: 读取完成

**Write complete**:
The boundary after read completion at which every Sink consumer lag is zero, every target table has the source baseline row count, and those signals have remained stable for two polling intervals.
_Avoid_: Migration complete, validation complete
_中文_: 写入完成

**Migration complete**:
The boundary at which a table migration unit is write-complete and all enabled validation checks have passed.
_Avoid_: Read complete, write complete, connector stopped
_中文_: 迁移完成

**Stuck**:
A terminal diagnosis for a box that shows no observable progress for the configured hard threshold while its connectors still report healthy. DBX stops its connectors but preserves topics and target data for diagnosis.
_Avoid_: Slow, failed, timed out
_中文_: 卡死

**Cancellation**:
A user-requested terminal stop of a migration run that preserves topics, target data, and diagnostic evidence.
_Avoid_: Discard, delete, rollback
_中文_: 取消

**Discard**:
A separately confirmed destructive operation that removes only a stopped run's resources that the run can still prove it exclusively owns, while retaining its audit record and original technical outcomes. A later run's target data is never discardable by an earlier run.
_Avoid_: Cancel, retry, cleanup, rollback
_中文_: 丢弃

**Target generation**:
The exclusive write epoch created when DBX first creates or deliberately clears a target table for a migration run. It prevents an earlier run from discarding target data after a later run has taken ownership.
_Avoid_: Table version, run number
_中文_: 目标代际

**Error occurrence**:
An immutable fact that DBX observed at a phase and scope, retaining the evidence and correlation needed to explain what happened. It is not itself a workflow outcome.
_Avoid_: Translated error, failure message
_中文_: 错误事件

**Diagnosis**:
A versioned interpretation of an error occurrence, identified by a stable diagnosis code and classified by phase, root-cause domain, and trusted scope. An unknown or conflicting diagnosis must not invent a cause.
_Avoid_: Error status, blame
_中文_: 诊断

**Diagnosis rule**:
A maintained evidence pattern that maps one or more external failure signatures to one operator-facing diagnosis, description, and action. The v1 first-release set contains 20 external-translation rule families; platform-structured diagnoses do not count toward that set.
_Avoid_: Exception mapping, regex error
_中文_: 诊断规则

**Root-cause domain**:
The single primary domain assigned to a diagnosis: user input, source database, target database, Kafka Connect, Kafka, runtime environment, or platform. It describes diagnostic evidence, not a person to blame.
_Avoid_: Responsible party
_中文_: 根因域

**Routing snapshot**:
The immutable run-local mapping from topic and connector coordinates to boxes, table migration units, source/target objects, and approved field mappings. It is the authority for table and field location; topic names and exception text are only corroborating evidence.
_Avoid_: Topic parsing, inferred table
_中文_: 路由快照

**Diagnostic package**:
A local, bounded, redacted export of run context, timeline, rule evidence, configuration fingerprints, versions, and raw failure details for support. It never includes credentials, record values, or automatic external upload.
_Avoid_: Log bundle, data dump
_中文_: 诊断包
