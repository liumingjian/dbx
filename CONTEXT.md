# DBX Migration

DBX describes offline, one-time full migrations as independently observable table migrations while treating connector groupings as disposable scheduling artifacts. This glossary fixes the language used across the product and technical specifications. Each term also carries `_中文_`, the canonical Simplified Chinese wording for that concept: operator-facing Chinese text is domain language, not presentation, so the user interface must not invent synonyms for it.

## Language

**Migration task**:
A user-approved migration of one source MySQL database into one PostgreSQL schema, composed of table migration units.
_Avoid_: Job, connector
_中文_: 迁移任务

**Migration draft**:
An unapproved, discardable working set of wizard selections and per-table configuration that has not yet become a migration task. It produces no migration run, is never referenced as audit evidence, and may be deleted without trace.
_Avoid_: Unsaved task, pending task, unapproved migration task, 草稿 as a task status
_中文_: 迁移草稿

**Migration run**:
One immutable execution attempt over all or part of a migration task, with its own run identifier, source baseline, scheduling plan, connectors, and topics. A rerun is a new migration run.
_Avoid_: Retry in place, connector run, resumed run
_中文_: 迁移运行

**Table migration unit**:
The durable, independently observable migration record for one source table and its corresponding target table within one migration run, including phase, outcome, baseline, progress, validation result, and errors. A rerun creates a new table migration unit rather than changing the old unit's result.
_Avoid_: Table task, connector table, 表进度
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

**System settings**:
The navigation area reserved for product-wide configuration. In v1 it holds only operator preferences and product information, including the diagnostic package export; it owns no entity.
_Avoid_: Preferences, admin console
_中文_: 系统设置

**Migration wizard**:
The five-stage surface on which an operator builds a migration draft and starts a migration run; it ends at 执行 and never observes a run. Its stages are one ordered journey, each gated on the facts the preceding stages established. In order: 连接与数据库, 迁移范围, 预检, 映射规则, 执行确认 — stages three and four are named by the terms **Preflight** and **Mapping rule**.
_Avoid_: Creation flow, setup steps, 创建迁移任务 as the surface's name, 逐表配置与预检, 评估, 参数
_中文_: 迁移向导

**Connections and databases**:
Wizard stage one, on which the operator chooses a verified source and target database connection and the source MySQL database and target PostgreSQL schema. It selects existing connections; it never creates one.
_Avoid_: Connection setup, endpoint step
_中文_: 连接与数据库

**Migration scope**:
Wizard stage two, and the recorded answer it produces: which source tables this migration covers, including the tables the operator explicitly excluded. A migration run states its own scope, which may be narrower than its migration task's.
_Avoid_: Table selection, included tables
_中文_: 迁移范围

**Execution confirmation**:
Wizard stage five, the last review before anything is written: the whole scope, the generated table write contracts, the unresolved findings, and the write freeze that names a responsible party and a time limit.
_Avoid_: Review step, summary page
_中文_: 执行确认

**Run monitoring**:
The standing view of one migration run, organised around its table migration units; it never exposes boxes, connectors or topics. It is a view of a run, not a wizard stage.
_Avoid_: Progress page, job monitor, 概览, 表进度
_中文_: 运行监控

**Validation report**:
The view of one migration run that an operator submits to a change review. It keeps technical validation results, preflight exclusions, and validation dispositions separately presented.
_Avoid_: Result summary, verification page
_中文_: 校验报告

**Run snapshot**:
The read-only record of what one migration run was started with — its scope, mapping rules, connection and credential versions, and write-freeze confirmation. It never changes after execution.
_Avoid_: Parameters, 参数, task settings
_中文_: 运行快照

**Mapping rule**:
A structured, reviewable exception to DBX's automatic table or column mapping. A rule names one source coordinate, one bounded action, its target value, and whether DBX or the user produced it; user rules override automatic rules. Rules never contain arbitrary SQL or regular expressions in v1.
_Avoid_: Mapping script, route expression, 映射与规则, 模式映射
_中文_: 映射规则

**Preflight**:
A source-side proof required before a table write contract may be approved. It evaluates exact value-domain and transport facts and concludes `SUPPORTED`, `UNSUPPORTED`, or `INCONCLUSIVE`; only `SUPPORTED` may proceed, and a new source baseline is still required after the write freeze.
_Avoid_: Validation, estimate, warning acknowledgement, 评估, 预检查
_中文_: 预检

**Environment check**:
DBX's proof that the environment it and its execution platform run in can carry a migration — drivers, execution-platform readiness, configuration against the release's expected snapshot, disk. It runs at platform startup and before a migration run is admitted, and it concludes per check item. Preflight asks whether a table's data can migrate; an environment check asks whether this installation can migrate anything now.
_Avoid_: 体检, 健康检查, 预检, 环境预检
_中文_: 环境自检

**Runtime condition**:
DBX's continuous, installation-wide account of whether it can keep migrating right now, and if not, which root-cause domain stands in the way. It observes a fixed set of items and presents the latest environment check's unmet items as reasons; it never re-runs or rewrites them, and it interrupts nothing that an existing gate does not. It is a bounded set of current conclusions, not a collection of logs or metrics, and it speaks to the operator without naming the execution platform's parts.
_Avoid_: 监控, 健康检查, 日志, 仪表盘, 平台状态, 系统状态
_中文_: 运行状况

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
_Operator-facing_: Never. A box is an internal scheduling detail, and the operator is not required to understand the execution platform in order to run the product. Phases named after it are presented as **awaiting scheduling** and **blocked by an upstream failure**.

**Awaiting scheduling**:
A table migration unit admitted to a migration run that DBX has not begun reading, because the resources it needs are not yet free. It is an ordinary waiting state, not a fault, and it carries no diagnosis.
_Avoid_: Waiting for box, queued, pending, stalled
_中文_: 等待调度

**Blocked by an upstream failure**:
A table migration unit that DBX has not started, or has stopped, without any fault of its own, because its box failed without the failure being attributable to this unit — whether another member failed or a shared cause such as disk pressure or an unreachable execution platform stopped it. Its own technical result is undetermined rather than failed, and it is a candidate for re-migration.
_Avoid_: Blocked by box failure, batch failure, collateral failure
_中文_: 因关联失败而阻塞

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

**Task write freeze**:
A migration task's write-freeze commitment over its whole scope, spanning every run until the task conclusion is reached, with an accountable operator and deadline reconfirmed before each run (ADR-0023). Each run's own write freeze still applies within it.
_Avoid_: Long freeze, batch freeze, 冻结期
_中文_: 整库冻结承诺

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

**Duration estimate**:
The range DBX gives before a migration run for how long its scope will take, carrying its estimate confidence, its source — this deployment's history or the shipped reference throughput band — and its minimum window. It is what an operator takes to a change board to request a downtime window, so it exists on the migration draft, before any write freeze.
_Avoid_: ETA, SLA, promised duration, 预计完成时间
_中文_: 预估耗时

**Remaining-time estimate**:
The range DBX gives during a migration run for how much longer it needs, projected from the unfinished part of its scheduling plan. Until the run has been observed long enough, the duration estimate stands in for it.
_Avoid_: ETA, countdown, progress percentage, 倒计时
_中文_: 预计剩余

**Minimum window**:
The time the largest table in scope needs through its single extraction stream; no scheduling finishes the run sooner. It accompanies every duration estimate.
_Avoid_: Lower bound, fastest time, 最短所需时间
_中文_: 窗口下限

**Estimate unavailable**:
The state in which DBX withholds a duration or remaining-time number because it is no longer credible, always paired with its reason and the facts that remain reliable. Like every estimate it is presentation only: it never changes a migration run status or a stuck diagnosis.
_Avoid_: Unknown, stuck, timed out
_中文_: 无法预估

**Cancellation**:
A user-requested terminal stop of a migration run that preserves topics, target data, and diagnostic evidence. Its gentler form, 收尾取消 (finishing cancellation), admits no new box and lets tables already transferring finish first.
_Avoid_: Discard, delete, rollback, 停止, 收尾停止
_中文_: 取消

**Discard**:
A separately confirmed destructive operation that removes only a stopped run's resources that the run can still prove it exclusively owns, while retaining its audit record and original technical outcomes. A later run's target data is never discardable by an earlier run.
_Avoid_: Cancel, retry, cleanup, rollback, Abandonment
_中文_: 丢弃

**Target generation**:
The exclusive write epoch created when DBX first creates or deliberately clears a target table for a migration run, bound to that table's database object identity. It prevents an earlier run from discarding target data after a later run has taken ownership.
_Avoid_: Table version, run number
_中文_: 目标代际

**Abandonment**:
A separately confirmed, task-level destructive decision that the migration will not go forward: DBX removes the target tables it still owns across all the task's runs, and the task is permanently closed while its records remain (ADR-0023).
_Avoid_: Rollback, 回滚, discard, delete task, 删除任务
_中文_: 废弃

**Abandonment list**:
The reviewable rendering of what an abandonment would drop and what it would refuse, with the evidence each decision rests on. Before any run exists it can only be projected from the table write contracts, and a projected list is never a confirmation.
_Avoid_: Rollback plan, deletion preview
_中文_: 废弃清单; projected: 预估废弃清单

**Migration task status**:
The task's own lifecycle above its latest run's projection: whether the migration is still active or has been abandoned. It never rewrites any run's status or outcomes.
_Avoid_: Task state, run status
_中文_: 迁移任务状态

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
_Operator-facing_: The `Kafka Connect` and `Kafka` domains are presented to the operator as a single **迁移平台** domain. The distinction between them is DBX's own to act on, not the operator's, and surfacing it would require understanding the execution platform. The specific domain is retained in the diagnostic evidence for support use, so nothing is lost from the audit record.

**Routing snapshot**:
The immutable run-local mapping from topic and connector coordinates to boxes, table migration units, source/target objects, and approved field mappings. It is the authority for table and field location; topic names and exception text are only corroborating evidence.
_Avoid_: Topic parsing, inferred table
_中文_: 路由快照

**Diagnostic package**:
A local, bounded, redacted export of run context, timeline, rule evidence, configuration fingerprints, versions, and raw failure details for support. It never includes credentials, record values, or automatic external upload.
_Avoid_: Log bundle, data dump
_中文_: 诊断包

**Re-migration**:
A new migration run created for the task's tables that have no successful result yet — left failed or undetermined, or never run in an earlier window — reusing the earlier run's approved decisions as its origin. It never repairs, resumes, or rewrites the earlier run: the earlier table migration units keep their results, and the new run produces new ones.
_Avoid_: Retry, resume, repair, rollback, 重跑, 重迁, 断点续跑
_中文_: 重新迁移

**Connection check**:
A verification that a database connection's endpoint and its current credential version actually work, recorded with its own time and outcome. A migration run may not be started from a connection whose latest check did not succeed.
_Avoid_: Connectivity test, ping, connection test, 测试连接
_中文_: 连接校验

**TLS mode**:
The transport security a database connection uses toward its endpoint, chosen when the connection is registered.
_Avoid_: SSL switch, secure checkbox
_中文_: TLS 模式

**Preflight finding**:
One exact, coded fact a preflight established about a table, marked blocking or non-blocking. A blocking finding cannot be acknowledged away; it is resolved, or the table leaves the migration scope.
_Avoid_: Warning, alert, issue
_中文_: 预检发现

**Validation check**:
One named comparison inside a validation plan, with its own comparison semantics, evidence requirement, and per-execution state. A check is a member of the plan, never a status of the table.
_Avoid_: Validation rule, assertion
_中文_: 校验项

**Migration run status**:
The deterministic projection of a migration run's units and boxes onto one status value (ADR-0004). It is never separately editable, so it can never disagree with the units it summarises.
_Avoid_: Run state, progress state
_中文_: 迁移运行状态

**Task conclusion**:
The projection of each in-scope table's latest unit result, overlaid with the closing drift check, onto one verdict for the whole migration task (ADR-0023). It is green only when every table is 迁移完成 and no drift was found, and it never rewrites a run's results.
_Avoid_: Task status, overall success, 全部完成, 整库完成
_中文_: 整库结论

**Table migration phase**:
Where in the execution sequence a table migration unit currently stands (ADR-0004). A phase says what is happening, never how it turned out.
_Avoid_: Status, step
_中文_: 阶段

**Table migration outcome**:
The single result a terminal table migration unit carries. It is DBX's own technical finding and is never rewritten by a validation disposition.
_Avoid_: Status, final status, validation result, 成功, 失败, 已跳过
_中文_: 技术结果

**Diagnosis classification phase**:
The phase a diagnosis is classified under (ADR-0005): `CONNECTION`, `METADATA_READ`, `PREFLIGHT`, `TARGET_PREPARATION`, `CONTRACT_CHECK`, `CONNECTOR_PROVISIONING`, `TRANSFER`, `COMPLETION_DETECTION`, `VALIDATION`, `CLEANUP`, `ENVIRONMENT_CHECK`. It is a maintenance coordinate for the diagnosis catalog, cut finer than the workflow and named after execution-platform work the operator does not run.
_Avoid_: Table phase, migration phase
_中文_: 诊断分类阶段
_Operator-facing_: Never. `CONNECTOR_PROVISIONING` names connector work, which Gate 7 keeps off the interface, and the remaining values would present a second, differently-cut phase vocabulary beside 阶段 without telling the operator anything they could act on. Where DBX must say when something happened, it shows the 表迁移单元's own 阶段, which every value of this set maps into — except `ENVIRONMENT_CHECK`, which belongs to no unit and is shown as the 环境自检 item it concerns. The classification is retained in the diagnostic evidence for support use.

## Value vocabularies

The terms above name concepts; the sets below fix the Chinese for the *values* those concepts carry. DBX persists each value as an enum literal, and a literal is not a word: it is an identifier that happens to be readable to the people who wrote it. So every value that reaches the interface has its wording here, and the interface may no more invent a synonym for it than for a term. A set marked `_Operator-facing_: Never` deliberately has none, because no operator should be asked to read it.

### Estimate confidence

The confidence a duration or remaining-time estimate carries. There are two values because there are two kinds of evidence; a third would draw a boundary no data supports.

**Low confidence**:
The estimate rests on the shipped reference throughput band, or on too little observation of this run.
_中文_: 低置信

**Reliable**:
The estimate rests on this deployment's own past runs, or on stable observation of this run.
_中文_: 可信

### Preflight conclusion

**Supported**:
A preflight that evaluated every required exact source-side fact and found the table inside DBX's value-domain and transport boundaries. It is the only conclusion a table write contract may be approved from.
_Avoid_: 通过, 已验证, 无风险
_中文_: 可迁移

**Unsupported**:
A preflight that established an exact fact placing the table outside those boundaries. It is a proven property of the data, not a malfunction, and no retry changes it while the fact holds.
_Avoid_: 预检失败, 错误, 不支持
_中文_: 不可迁移

**Inconclusive**:
A preflight or validation execution that could not establish the fact it was required to establish, because a timeout, a permission, or a lost connection stopped it. It states what DBX does not know. It is neither a mild failure nor a passable risk, and it is never presented as a warning to be acknowledged.
_Avoid_: 有风险, 警告, 待确认, 基本可以
_中文_: 无法判定

### Validation item state

Every state below is a technical result of a 校验执行. A 校验处置 may close the workflow around one, but never turns one into another.

**Pass**:
The check ran, compared what its plan requires, and found no difference.
_Avoid_: 成功, 迁移完成
_中文_: 通过

**Fail**:
The check ran and found a difference. Its evidence is retained, and a later execution never rewrites it.
_Avoid_: 失败, 错误, 异常
_中文_: 未通过

**Not applicable**:
The validation plan determined before execution that this check has no referent for this table — there is nothing here for it to compare. It is not a check anyone needs to chase, and it is never recorded in place of `INCONCLUSIVE`.
_Avoid_: 未执行, 跳过, 忽略, 无
_中文_: 不适用

**Not run**:
The check was not enabled by the validation plan, or the validation execution did not reach it. Unlike 不适用 the check does have a referent here; it simply did not happen. Neither is a failure.
_Avoid_: 不适用, 失败, 跳过
_中文_: 未执行

**In flight**:
Work that is under way, so no conclusion exists yet. DBX shows the absence of a conclusion rather than an optimistic one, and a stale earlier conclusion is never shown in its place.
_Avoid_: 待定, 未知, 等待调度
_中文_: 执行中

### Migration task status

**Active**:
The migration has not been abandoned; the task presents its latest run's status.
_Avoid_: 正常, 运行中
_中文_: 进行中

**Abandoning**:
An abandonment is confirmed and not every owned object is resolved yet; the task still holds its target leases.
_Avoid_: 回滚中, 删除中
_中文_: 废弃中

**Abandoned**:
Every owned object the abandonment covered is gone, and the task is permanently closed.
_Avoid_: 已回滚, 已删除, 已丢弃
_中文_: 已废弃

**Partially abandoned**:
The abandonment finished what it could, but some objects were refused and are named; it can be retried once the blocker is removed.
_Avoid_: 废弃失败, 部分回滚
_中文_: 部分废弃

### Migration run status

**Preparing**:
No unit of the run has reached execution, and at least one still stands before admission.
_Avoid_: 排队中, 等待调度
_中文_: 准备中

**Running**:
At least one unit or box is actively creating, waiting, transferring, draining, or validating.
_Avoid_: 正常, 健康
_中文_: 进行中

**Attention required**:
Execution cannot advance until a person acts — review, preflight correction, freed disk, or an unsatisfied environment check — while nonterminal units remain. It names a required action, not a fault.
_Avoid_: 警告, 异常, 出错
_中文_: 需要人工处理

**Cancelling**:
A 取消 has been requested and not every box is confirmed stopped yet.
_Avoid_: 停止中, 中断中
_中文_: 取消中

**Completed**:
Every included unit succeeded. Tables excluded before execution are reported separately and neither redeem nor spoil this status.
_Avoid_: 成功, 完成
_中文_: 全部完成

**Completed with failures**:
Every unit is terminal and at least one failed or was blocked by an upstream failure — including when a cancellation stopped the remaining units.
_Avoid_: 部分成功, 完成
_中文_: 完成，有失败

**Completed with accepted risk**:
Every unit is terminal, none failed or was blocked, and at least one unit was closed by a 校验处置 over a result that never passed. The wording carries the accepted risk into every list the run appears in, because that is the fact a later reader most needs.
_Avoid_: 成功, 通过, 完成
_中文_: 完成，已接受风险

**Cancelled**:
Cancellation converged, every selected unit is terminal, and no unit failed or was blocked before or during it. This is the run-level 取消 that `Cancellation` defines: a person asked for it.
_Avoid_: 已停止, 已丢弃
_中文_: 已取消

### Table migration phase

**Discovered**:
Source metadata for the table has been captured.
_Avoid_: 已发现, 已扫描
_中文_: 已读取源结构

**Preflighting**:
Exact capability checks are running against the source.
_Avoid_: 检查中, 验证中
_中文_: 预检中

**Awaiting approval**:
The preflight conclusion and the generated table write contract are available for review, and nothing proceeds until a person approves.
_Avoid_: 待处理, 暂停
_中文_: 等待批准

**Ready**:
The approved contract and the run baseline are fixed, and nothing further is required of the operator.
_Avoid_: 就绪, 等待中
_中文_: 已批准待执行

**Creating target**:
DDL execution and structural introspection are in progress on the target.
_Avoid_: 建表中, 初始化中
_中文_: 创建目标表中

**Transferring**:
DBX is reading the source table and writing the target table. Read completion and write completion are evidence recorded during this phase, not phases of their own.
_Avoid_: 同步中, 复制中, 推送中, 导入中
_中文_: 传输中

**Validating**:
The table is write-complete and an immutable validation execution is active.
_Avoid_: 检查中, 核对中
_中文_: 校验中

**Terminal**:
No automatic execution may continue for this unit. It says that DBX has stopped, and the 技术结果 beside it says how it turned out.
_Avoid_: 完成, 成功, 结束运行
_中文_: 已结束

### Table migration outcome

**Succeeded**:
The unit is write-complete and every enabled validation check passed — exactly the boundary `Migration complete` defines, so it carries that term's wording rather than a second word for the same fact.
_Avoid_: 成功, 通过
_中文_: 迁移完成

**Failed**:
Preparation, DDL, transfer, or validation failed for this table, with a stable reason code identifying the failing stage. It is this unit's own failure, not one it inherited.
_Avoid_: 错误, 异常, 未完成
_中文_: 迁移失败

**Skipped**:
The operator excluded the table before execution, so it was never migrated and has no technical conclusion of its own. 「没迁」 and 「迁了但没过」 are different facts and never share a word.
_Avoid_: 跳过, 忽略, 失败
_中文_: 已排除未迁移

**Cancelled**:
The unit stopped because a confirmed 取消 stopped the run, without reaching a result of its own. The person cancelled the 迁移运行; this unit merely stopped, and reusing 取消 here would claim someone made a decision about this table. Its technical result is undetermined rather than failed, and it is a candidate for 重新迁移.
_Avoid_: 取消, 已取消, 中止, 失败
_中文_: 因运行取消而停止

**Completed with accepted risk**:
The write completed, a validation remained 未通过 or 无法判定, and an operator recorded a 校验处置 with a required reason. The original validation result is unchanged, and this outcome never reads as a pass.
_Avoid_: 通过, 成功, 已确认
_中文_: 完成，已接受风险

### Root-cause domain value

**User input**:
The evidence points at what the operator supplied — a name, a credential, a selection.
_Avoid_: 用户错误, 操作失误
_中文_: 用户输入

**Source database**:
The evidence points at the source database: its data, permissions, or availability.
_Avoid_: 源端故障
_中文_: 源数据库

**Target database**:
The evidence points at the target database: its structures, permissions, or availability.
_Avoid_: 目标端故障
_中文_: 目标数据库

**Runtime environment**:
The evidence points at the environment DBX and its execution platform run in: disk, memory, network, host.
_Avoid_: 服务器, 系统
_中文_: 运行环境

**Platform**:
The evidence points at DBX itself rather than at anything it talks to. It must stay distinguishable from 迁移平台, which is where the `Kafka Connect` and `Kafka` domains present: one says DBX's own logic, the other says the machinery it drives.
_Avoid_: 平台, 迁移平台, 系统
_中文_: DBX 自身

### Diagnosis source kind

This set tells the operator how much a 诊断 is worth trusting, which is why it is shown rather than hidden.

**Structured**:
A fact DBX produced directly — a preflight conclusion, a structural-proof difference, a 卡死 diagnosis, a validation result.
_Avoid_: 系统, 内部
_中文_: DBX 直接判定

**External translation**:
A diagnosis rule matched an external failure signature and interpreted it. The interpretation is versioned, and the raw evidence is retained beside it.
_Avoid_: 自动识别, 智能诊断
_中文_: 外部信号翻译

**System fallback**:
No rule was trustworthy, or same-strength rules disagreed. DBX says it did not establish a cause rather than inventing one.
_Avoid_: 未知错误, 其他
_中文_: 兜底判定

### Preflight finding code

**Large record value**:
The exact byte size of one source value that makes this a 大记录表, and blocking once it exceeds the 大记录包络.
_中文_: 大记录单值

**Large record row**:
The exact pre-serialization byte size of one source row. Pruning one column does not exempt the row.
_中文_: 大记录整行

**Value domain out of range**:
The source value domain exceeds what the target type in the 表写入契约 can hold.
_中文_: 值域超出目标类型

**Zero date value rejected**:
Under the current mapping rules the column stays `NOT NULL`, and the source's zero dates would be rejected at the target.
_中文_: 零日期值将被拒绝

**Envelope scan inconclusive**:
DBX could not complete the exact 大记录包络 scan, so this table's conclusion is 无法判定.
_中文_: 包络扫描无法判定

### Environment check item conclusion

Only satisfied lets migrations start; the other two block alike and cannot be acknowledged away.

**Satisfied**:
_中文_: 满足

**Unsatisfied**:
The item established a fact that falls short of what the release expects.
_Avoid_: 异常, 警告
_中文_: 不满足

**Inconclusive**:
The item could not establish its fact, usually because what it inspects is unreachable.
_Avoid_: 未知, 跳过
_中文_: 无法判定

### Runtime condition value

The whole condition takes its worst item's value. Only the last two stop admission, and neither can be acknowledged away.

**Clear**:
Every item is within its limits.
_Avoid_: 正常, 就绪, 可迁移
_中文_: 畅通

**Caution**:
An item has crossed its early-warning line; migration continues unchanged.
_Avoid_: 警告, 需要人工处理
_中文_: 需留意

**Impeded**:
DBX cannot admit or advance migration now, and it names the item that stops it.
_Avoid_: 故障, 不可迁移, 异常
_中文_: 受阻

**Inconclusive**:
DBX cannot establish an item's fact, and admits nothing it cannot see.
_中文_: 无法判定

### Preflight inconclusive reason

Why an exact scan could not conclude. Each names a condition an operator can go and fix.

**Query timeout**:
_中文_: 查询超时

**Permission denied**:
_中文_: 权限不足

**Connection lost**:
_中文_: 连接中断

### Validation check

**Row count**:
The target table holds the 源基线's exact row count.
_中文_: 行数比对

**Primary key terminal value**:
The terminal value of the monotonic primary key matches the 源基线.
_中文_: 主键终值比对

**Null constraint conformance**:
No column the 表写入契约 declares `NOT NULL` holds a null at the target.
_中文_: 非空约束符合性

**Value checksum sample**:
A sampled value-level comparison between source and target. Being a sample, it never claims full value equality.
_Avoid_: 全量比对
_中文_: 抽样值比对

**Large record value integrity**:
Byte-level integrity of the values that made this a 大记录表.
_中文_: 大记录值完整性

### Scope exclusion reason

Why a table selected during the wizard is not in a run's executed scope. These tables were never migrated and have no technical conclusion, so none of these words may read as a failure.

**Operator excluded**:
A person removed the table from the 迁移范围. An explicit exclusion is a reviewable exception.
_Avoid_: 跳过, 忽略
_中文_: 操作员显式排除

**Preflight unsupported**:
The table's preflight concluded 不可迁移, and only 可迁移 may proceed.
_中文_: 预检判定不可迁移

**Preflight inconclusive**:
The table's preflight concluded 无法判定, which cannot be acknowledged away.
_中文_: 预检无法判定

### Mapping rule origin

**Platform origin**:
DBX produced the rule automatically from the source metadata and the database pair. A user rule overrides it.
_Avoid_: 平台, 系统, 自动
_中文_: DBX 自动生成

**User origin**:
A person decided this rule, and DBX records whose decision it was.
_Avoid_: 手动, 自定义
_中文_: 用户指定

### Connection check outcome

**Check succeeded**:
The endpoint answered and the credential version was accepted at the recorded time.
_Avoid_: 可用, 正常, 在线
_中文_: 校验通过

**Check failed**:
The endpoint or the credential version was rejected at the recorded time.
_Avoid_: 不可用, 离线
_中文_: 校验失败

**Check not run**:
No connection check has been recorded for this connection yet. It is an absence of evidence, never evidence of a problem.
_Avoid_: 失败, 未知, 异常
_中文_: 尚未校验

### TLS mode

**TLS disabled**:
No transport security toward the endpoint.
_中文_: 不启用 TLS

**Server authenticated**:
DBX verifies the endpoint's certificate.
_中文_: 校验服务端证书

**Mutual**:
DBX verifies the endpoint's certificate and presents its own.
_Avoid_: 双向认证登录
_中文_: 双向证书校验
