# DBX Migration

DBX performs offline, one-time full migrations as independently observable table migrations. Each term's `_中文_` is its canonical Chinese; the interface invents no synonyms (ADR-0030). Rationale lives in `docs/adr/`.

## Language

### Tasks and runs

**Migration task**:
A user-approved migration of one source MySQL database into one PostgreSQL schema, made of table migration units.
_Avoid_: Job, connector
_中文_: 迁移任务

**Migration draft**:
An unapproved, discardable set of wizard selections; produces no run, never audit evidence.
_Avoid_: Pending task, 草稿 as a task status
_中文_: 迁移草稿

**Migration run**:
One immutable attempt over all or part of a task, with its own baseline, plan, connectors, and topics; a rerun is a new run.
_Avoid_: Retry in place, resumed run
_中文_: 迁移运行

**Table migration unit**:
One table's durable record within one run: phase, outcome, baseline, progress, validation, errors.
_Avoid_: Table task, connector table, 表进度
_中文_: 表迁移单元

**Run snapshot**:
What a run started with, read-only: scope, mapping rules, accepted preflight findings, connection and credential versions, environment check conclusions, write-freeze confirmation.
_Avoid_: Parameters, 参数, task settings
_中文_: 运行快照

**Migration task status**:
Whether the task is active or abandoned; never rewrites a run's results.
_Avoid_: Task state, run status
_中文_: 迁移任务状态

**Migration run status**:
The never-edited projection of a run's units and boxes onto one value (ADR-0004).
_Avoid_: Run state, progress state
_中文_: 迁移运行状态

**Task conclusion**:
A task's verdict from each table's latest unit result and the closing drift check (ADR-0024); green only if all are 迁移完成 without drift.
_Avoid_: Migration task status, overall success, 全部完成, 整库完成
_中文_: 整库结论

**Task closing**:
The operator's act of ending a task: it runs the closing drift check and produces the task conclusion, and only then may the task write freeze be released; DBX prompts but never closes (ADR-0040).
_Avoid_: Abandonment, 废弃, 完成, 结束运行
_中文_: 收口

**Table migration phase**:
Where a unit stands in execution (ADR-0004), never how it turned out.
_Avoid_: Status, step
_中文_: 阶段

**Table migration outcome**:
A terminal unit's single technical result (ADR-0004); never rewritten by a validation disposition.
_Avoid_: Status, validation result, 成功, 失败, 已跳过
_中文_: 技术结果

### Connections

**Database connection**:
A reusable, versioned database endpoint and identity; runs freeze its versions (ADR-0006).
_Avoid_: JDBC URL, datasource
_中文_: 数据库连接

**Credential version**:
An immutable version of secret authentication material, auditable after its destruction.
_Avoid_: Password field, current password
_中文_: 凭据版本

**Connection check**:
A recorded proof that a connection and its current credential version work; runs need the latest to pass.
_Avoid_: Connectivity test, ping, 测试连接
_中文_: 连接校验

**TLS mode**:
A connection's transport security toward its endpoint.
_Avoid_: SSL switch, secure checkbox
_中文_: TLS 模式

### Surfaces

**Data source management**:
The navigation area, not an entity, for database connections; one endpoint is a 数据库连接.
_Avoid_: Connection wizard, inline credentials
_中文_: 数据源

**System settings**:
The navigation area for product-wide configuration: in v1, preferences, product info, diagnostic package export.
_Avoid_: Preferences, admin console
_中文_: 系统设置

**Migration wizard**:
The five-stage gated journey that builds a draft and starts a run, never observing it (ADR-0020): 连接与数据库, 迁移范围, 预检, 映射规则, 执行确认.
_Avoid_: Creation flow, 创建迁移任务 as the surface's name, 逐表配置与预检
_中文_: 迁移向导

**Connections and databases**:
Wizard stage one: verified connections, source database, target schema.
_Avoid_: Connection setup, endpoint step
_中文_: 连接与数据库

**Migration scope**:
Wizard stage two and its answer: covered source tables and explicit exclusions; a run's may be narrower than its task's.
_Avoid_: Table selection, included tables
_中文_: 迁移范围

**Execution confirmation**:
Wizard stage five, the last review before any write: scope, contracts, findings, write freeze.
_Avoid_: Review step, summary page
_中文_: 执行确认

**Run monitoring**:
One run's standing view, organised by unit; never boxes, connectors, or topics.
_Avoid_: Progress page, 概览, 表进度
_中文_: 运行监控

**Validation report**:
One run's change-review view; keeps validation results, exclusions, and dispositions apart.
_Avoid_: Result summary, verification page
_中文_: 校验报告

### Contracts and dialects

**Mapping rule**:
A structured exception to automatic mapping: one source coordinate, one bounded action, a target value, an origin; no SQL or regex.
_Avoid_: Mapping script, route expression, 映射与规则, 模式映射
_中文_: 映射规则

**Preflight**:
The exact pre-approval proof a table write contract needs, from source facts and the target's current state; only `SUPPORTED` proceeds.
_Avoid_: Validation, estimate, 评估, 预检查
_中文_: 预检

**Preflight finding**:
One exact, coded fact a preflight established, with one 预检发现影响 (ADR-0029); non-blocking findings are accepted by approving the contract, never acknowledged.
_Avoid_: Warning, alert, warning acknowledgement, 我已知晓
_中文_: 预检发现

**Table write contract**:
The immutable single-table write intent DBX proves before a Sink starts; DDL is one rendering (ADR-0011).
_Avoid_: Editable DDL, sink schema, 建表选项
_中文_: 表写入契约

**Structural proof**:
In-run comparison of a just-created target table with its contract; zero difference starts the Sink, any difference is the unit's 迁移失败, never a finding.
_Avoid_: Probe insert, table exists check
_中文_: 结构证明

**Supplemental SQL**:
The task-level script for source structures outside the v1 contract; delivered, never executed (ADR-0026).
_Avoid_: Migrated constraints, automatic post-DDL
_中文_: 补建 SQL

**Source dialect**:
One source database family's versioned description; supplies facts and plans, never advances workflow (ADR-0008).
_Avoid_: Source connector, source adapter
_中文_: 源方言

**Target dialect**:
One target database family's versioned description; derives target operations and structural proof, never advances workflow.
_Avoid_: Sink connector, target adapter
_中文_: 目标方言

**Database pair**:
An explicitly supported, directed, versioned dialect conversion owning cross-database mapping.
_Avoid_: Automatic dialect combination, bidirectional pair
_中文_: 数据库对

### Environment

**Environment check**:
DBX's per-item proof, at startup and before admission, that this installation can migrate now (ADR-0027).
_Avoid_: 体检, 健康检查, 预检, 环境预检
_中文_: 环境自检

**Runtime condition**:
DBX's continuous account of whether it can keep migrating and which root-cause domain blocks it (ADR-0021).
_Avoid_: 监控, 健康检查, 仪表盘, 平台状态, 系统状态
_中文_: 运行状况

**Release version**:
The one version pinning every component DBX ships; operators never see component versions (ADR-0035).
_Avoid_: 版本号, 构建号, 组件版本
_中文_: 发行版本

**Rollback window**:
From an upgrade's end until the first run admitted under the new release; the only time the previous release may return (ADR-0035).
_Avoid_: 降级, 回滚
_中文_: 回退窗口

**Recovery mode**:
The posture DBX takes when H2 is unreachable or corrupt: it starts, offers only restore, 关于 and the diagnostic package, and refuses everything else (ADR-0006).
_Avoid_: 只读模式, 安全模式, 降级运行, 维护模式
_中文_: 恢复态

**Pre-upgrade backup**:
The one labelled backup an upgrade takes before stopping the stack; the only backup a rollback restores (ADR-0035).
_Avoid_: 快照, 最近备份, 自动备份
_中文_: 升级前备份

### Scheduling and completion

**Box**:
A run-local, disposable group of units sharing one Source and one Sink connector (ADR-0002); owns no result.
_Avoid_: Batch, task group, shard
_中文_: 箱
_Operator-facing_: Never.

**Awaiting scheduling**:
An admitted unit waiting for busy resources; a wait, not a fault.
_Avoid_: Waiting for box, queued, stalled
_中文_: 等待调度

**Blocked by an upstream failure**:
A unit stopped by its box's failure, not its own (ADR-0004); undetermined, not failed.
_Avoid_: Blocked by box failure, batch failure, collateral failure
_中文_: 因关联失败而阻塞

**Execution signature**:
The connector configuration identity tables must share to share a box.
_Avoid_: Table profile, connector type
_中文_: 执行签名

**Scheduling plan**:
A run's immutable box assignment and order; never repacked.
_Avoid_: Queue snapshot, live packing
_中文_: 调度计划

**Rolling admission**:
Starting the next eligible box whenever budgets permit; no wave barrier. A blocked head-of-line box reserves cumulative capacity so it cannot starve (ADR-0002).
_Avoid_: Batch execution, strict waves
_中文_: 滚动准入

**Admission paused**:
One run's admission stopped after a second Connect restart or two zero-output stuck boxes, until the DBA continues; the run shows 需要人工处理 (ADR-0039).
_Avoid_: 暂停, 运行已暂停
_中文_: 准入已暂停

**Memory tier**:
The deployment's memory bracket, fixed at install from the memory containers can see; it sets the platform's heaps and the ceiling on concurrent table migrations (ADR-0031).
_Avoid_: Memory level, 内存规格, deployment size
_中文_: 内存档位

**Platform memory budget**:
Memory the 迁移平台 grants running migrations (ADR-0031).
_Avoid_: 堆, heap limit
_中文_: 平台内存预算

**Large record table**:
A table with a source value or row over 1 MiB, run in an isolated box (ADR-0003).
_Avoid_: LOB table, big table
_中文_: 大记录表

**Large-record envelope**:
The v1 limit of 20 MiB per source value and per row.
_Avoid_: 20 MB message limit, Kafka limit
_中文_: 大记录包络

**Source baseline**:
A run's immutable boundary under write freeze: exact row counts and keyset column terminal values.
_Avoid_: 预估行数 as a synonym for it, snapshot
_中文_: 源基线

**Planned row count**:
A table's row count estimated from source statistics before any baseline exists; it sizes the bulk read and the transfer-byte estimate, and is never evidence (ADR-0002, ADR-0037).
_Avoid_: 行数 alone, baseline row count, 实际行数
_中文_: 预估行数

**Keyset column**:
A table's single integer, non-null, unique, non-negative column for chunked reads; tables without one are read in bulk (ADR-0037).
_Avoid_: Incrementing column, monotonic key
_中文_: 键集列

**Write freeze**:
An external, time-bounded, accountable commitment that a run's source data stays still.
_Avoid_: Maintenance mode, pause, permanent checkbox
_中文_: 写冻结

**Task write freeze**:
A write freeze over a task's whole scope and every run, reconfirmed per run; run freezes nest inside it (ADR-0024).
_Avoid_: Long freeze, batch freeze, 冻结期
_中文_: 整库冻结承诺

**Read complete**:
Every topic of a box stably holds the baseline row count (ADR-0001).
_Avoid_: Migration complete, connector complete
_中文_: 读取完成

**Write complete**:
Read complete, zero Sink lag, and every target stably at the baseline row count.
_Avoid_: Migration complete, validation complete
_中文_: 写入完成

**Migration complete**:
Write complete with every enabled validation check passed.
_Avoid_: Read complete, connector stopped
_中文_: 迁移完成

**Stuck**:
A terminal box diagnosis: no progress past the hard threshold while connectors look healthy.
_Avoid_: Slow, failed, timed out
_中文_: 卡死

**Over time limit**:
A terminal box diagnosis: the box was still progressing when it reached the hard run limit (ADR-0001).
_Avoid_: 卡死, 超时, 停滞
_中文_: 超出 24 小时上限

### Estimates

**Duration estimate**:
The pre-run range for a preflighted scope's duration, with confidence, source, and minimum window (ADR-0019, ADR-0038).
_Avoid_: ETA, SLA, 预计完成时间
_中文_: 预估耗时

**Remaining-time estimate**:
The in-run range of how much longer a run needs.
_Avoid_: ETA, countdown, progress percentage, 倒计时
_中文_: 预计剩余

**Minimum window**:
The largest table's single-stream time; no schedule beats it.
_Avoid_: Lower bound, fastest time, 最短所需时间
_中文_: 窗口下限

**Reference throughput band**:
The release's lab-measured per-shape stream rates and shared ceiling; the source of 低置信 estimates (ADR-0034).
_Avoid_: Benchmark, promised speed, SLA
_中文_: 参考吞吐带

**Estimate unavailable**:
DBX withholding a no-longer-credible estimate, with its reason.
_Avoid_: Unknown, stuck, timed out
_中文_: 无法预估

### Stopping and cleanup

**Cancellation**:
A user-requested terminal stop of a run, keeping data and evidence; 收尾取消 (finishing cancellation) lets transferring tables finish (ADR-0024).
_Avoid_: Discard, rollback, 停止, 收尾停止
_中文_: 取消

**Discard**:
Confirmed removal of a stopped run's provably owned resources, keeping its audit record (ADR-0006).
_Avoid_: Cancel, cleanup, rollback, Abandonment
_中文_: 丢弃

**Target generation**:
A target table's exclusive write epoch, so an earlier run never discards a later run's data.
_Avoid_: Table version, run number
_中文_: 目标代际

**Abandonment**:
A confirmed task-level stop for good: DBX drops its owned target tables and closes the task (ADR-0023).
_Avoid_: Rollback, 回滚, discard, 删除任务
_中文_: 废弃

**Abandonment list**:
What an abandonment would drop and refuse, with evidence; before any run only projected.
_Avoid_: Rollback plan, deletion preview
_中文_: 废弃清单; projected: 预估废弃清单

**Re-migration**:
A new run for tables lacking a successful result, reusing earlier approvals; never repairs or resumes one.
_Avoid_: Retry, resume, repair, 重跑, 重迁, 断点续跑
_中文_: 重新迁移

### Validation

**Validation plan**:
A unit's immutable set of enabled, disabled, and not-applicable checks.
_Avoid_: Validation options, best-effort checks
_中文_: 校验计划

**Validation check**:
One named comparison in a plan; never a table status.
_Avoid_: Validation rule, assertion
_中文_: 校验项

**Validation execution**:
One retained run of a plan after write complete; never rewritten.
_Avoid_: Validation status, check retry
_中文_: 校验执行

**Validation disposition**:
An operator's audited decision on a failed or inconclusive result; never a pass.
_Avoid_: Manual pass, overridden result
_中文_: 校验处置

**Drift check**:
A task-scoped, immutable reread of every already-migrated table against its original source baseline, before a later run or at closing; reports drift or "no drift observed", never a pass (ADR-0024, ADR-0040).
_Avoid_: Validation execution, 复校, 二次校验
_中文_: 漂移检查

### Diagnosis

**Error occurrence**:
An immutable observed fact at a phase and scope, with its evidence.
_Avoid_: Translated error, failure message
_中文_: 错误事件

**Diagnosis**:
A versioned interpretation of an error occurrence with a stable code (ADR-0005); never invents a cause.
_Avoid_: Error status, blame
_中文_: 诊断

**Diagnosis rule**:
A maintained pattern mapping external failure signatures to one diagnosis and action.
_Avoid_: Exception mapping, regex error
_中文_: 诊断规则

**Root-cause domain**:
A diagnosis's single primary domain: one of the values below, or Kafka Connect or Kafka.
_Avoid_: Responsible party
_中文_: 根因域
_Operator-facing_: `Kafka Connect` and `Kafka` show as one **迁移平台**.

**Diagnosis classification phase**:
The catalog coordinate a diagnosis is classified under (ADR-0005).
_Avoid_: Table phase, migration phase
_中文_: 诊断分类阶段
_Operator-facing_: Never; the unit's 阶段 is shown instead.

**Routing snapshot**:
A run's immutable map from topic and connector coordinates to units and fields; the locating authority.
_Avoid_: Topic parsing, inferred table
_中文_: 路由快照

**Diagnostic package**:
A local, bounded, redacted support export with a manifest; never credentials or values (ADR-0028).
_Avoid_: Log bundle, data dump
_中文_: 诊断包

## Value vocabularies

The Chinese for each value a term carries; the interface uses it, never the enum literal or a synonym (ADR-0030).

### Estimate confidence

**Low confidence**: rests on the shipped reference band or too little observation. _中文_: 低置信

**Reliable**: rests on this source 数据源's past runs or this run's stable observation. _中文_: 可信

### Preflight conclusion

**Supported**:
Every required fact evaluated and within DBX's boundaries; the only approvable conclusion.
_Avoid_: 通过, 已验证, 无风险
_中文_: 可迁移

**Unsupported**:
An exact fact places the table outside those boundaries; no retry changes it.
_Avoid_: 预检失败, 错误, 不支持
_中文_: 不可迁移

**Inconclusive**:
A preflight or validation execution could not establish its fact; never acknowledgeable.
_Avoid_: 有风险, 警告, 待确认, 基本可以
_中文_: 无法判定

### Validation item state

Each is a technical result; a 校验处置 never turns one into another.

**Pass**: ran and found no difference.
_Avoid_: 成功, 迁移完成
_中文_: 通过

**Fail**: ran and found a difference; evidence retained.
_Avoid_: 失败, 错误, 异常
_中文_: 未通过

**Not applicable**: nothing for this check to compare; never stands in for `INCONCLUSIVE`.
_Avoid_: 未执行, 跳过, 忽略
_中文_: 不适用

**Not run**: has a referent but was not enabled or reached; not a failure.
_Avoid_: 不适用, 失败, 跳过
_中文_: 未执行

**In flight**: under way; no stale earlier conclusion shown.
_Avoid_: 待定, 未知, 等待调度
_中文_: 执行中

### Migration task status

**Active**: not abandoned; shows its latest run's status.
_Avoid_: 正常, 运行中
_中文_: 进行中

**Abandoning**: confirmed; owned objects not all resolved yet.
_Avoid_: 回滚中, 删除中
_中文_: 废弃中

**Abandoned**: every covered owned object gone; the task closed.
_Avoid_: 已回滚, 已删除, 已丢弃
_中文_: 已废弃

**Partially abandoned**: some objects refused and named; retryable once unblocked.
_Avoid_: 废弃失败, 部分回滚
_中文_: 部分废弃

### Migration run status

**Preparing**: _Avoid_: 排队中, 等待调度 _中文_: 准备中

**Running**: _Avoid_: 正常, 健康 _中文_: 进行中

**Attention required**: a person must act for execution to advance; not a fault.
_Avoid_: 警告, 异常, 出错
_中文_: 需要人工处理

**Cancelling**: _Avoid_: 停止中, 中断中 _中文_: 取消中

**Completed**: every included unit succeeded; exclusions neither redeem nor spoil it.
_Avoid_: 成功, 完成
_中文_: 全部完成

**Completed with failures**: _Avoid_: 部分成功, 完成 _中文_: 完成，有失败

**Completed with accepted risk**: _Avoid_: 成功, 通过, 完成 _中文_: 完成，已接受风险

**Cancelled**: _Avoid_: 已停止, 已丢弃 _中文_: 已取消

### Table migration phase

**Discovered**: _Avoid_: 已发现, 已扫描 _中文_: 已读取源结构

**Preflighting**: _Avoid_: 检查中, 验证中 _中文_: 预检中

**Awaiting approval**: _Avoid_: 待处理, 暂停 _中文_: 等待批准

**Ready**: _Avoid_: 就绪, 等待中 _中文_: 已批准待执行

**Creating target**: _Avoid_: 建表中, 初始化中 _中文_: 创建目标表中

**Waiting for box**: see 等待调度. _中文_: 等待调度

**Transferring**: read and write completion are evidence within it, not phases.
_Avoid_: 同步中, 复制中, 导入中
_中文_: 传输中

**Validating**: _Avoid_: 检查中, 核对中 _中文_: 校验中

**Terminal**: DBX has stopped; the 技术结果 says how it turned out.
_Avoid_: 完成, 成功, 结束运行
_中文_: 已结束

### Table migration outcome

**Succeeded**: exactly 迁移完成.
_Avoid_: 成功, 通过
_中文_: 迁移完成

**Failed**: this unit's own failure, with a stable reason code.
_Avoid_: 错误, 异常, 未完成
_中文_: 迁移失败

**Blocked by box failure**: see 因关联失败而阻塞. _中文_: 因关联失败而阻塞

**Skipped**: excluded before execution; never migrated, no technical conclusion.
_Avoid_: 跳过, 忽略, 失败
_中文_: 已排除未迁移

**Cancelled**: stopped by a run 取消 without a result of its own; a re-migration candidate.
_Avoid_: 取消, 已取消, 中止, 失败
_中文_: 因运行取消而停止

**Completed with accepted risk**: a validation stayed 未通过 or 无法判定 under a recorded 校验处置; never a pass.
_Avoid_: 通过, 成功, 已确认
_中文_: 完成，已接受风险

### Root-cause domain value

**User input**: _Avoid_: 用户错误, 操作失误 _中文_: 用户输入

**Source database**: _Avoid_: 源端故障 _中文_: 源数据库

**Target database**: _Avoid_: 目标端故障 _中文_: 目标数据库

**Runtime environment**: disk, memory, network, host. _Avoid_: 服务器, 系统 _中文_: 运行环境

**Platform**: DBX's own logic, distinct from 迁移平台, the machinery it drives.
_Avoid_: 平台, 迁移平台, 系统
_中文_: DBX 自身

### Diagnosis source kind

Shown: it tells the operator how far to trust a 诊断.

**Structured**: a fact DBX produced directly. _Avoid_: 系统, 内部 _中文_: DBX 直接判定

**External translation**: a diagnosis rule interpreted an external signature. _Avoid_: 自动识别, 智能诊断 _中文_: 外部信号翻译

**System fallback**: no trustworthy agreeing rule; no cause invented. _Avoid_: 未知错误, 其他 _中文_: 兜底判定

### Preflight finding impact

Only blocking stops a table; the others are accepted with the contract (ADR-0029). A fact without a trade-off has none.

**Blocking**: _Avoid_: 错误, 失败 _中文_: 阻塞

**Data loss**: the target holds or can do less than the source. _Avoid_: 警告, 风险 _中文_: 数据有损

**Behaviour change only**: values intact; only later writes behave differently. _Avoid_: 警告, 提示 _中文_: 仅行为差异

### Preflight finding code

**Large record value**: _中文_: 大记录单值

**Large record row**: _中文_: 大记录整行

**Value domain out of range**: _中文_: 值域超出目标类型

**Zero date value rejected**: _中文_: 零日期值将被拒绝

**Envelope scan inconclusive**: _中文_: 包络扫描无法判定

**Auto-increment value beyond target ceiling**: _中文_: 自增当前值超出目标上限

**Large table without a unique integer key**: the bulk read of a table with no keyset column would exceed the source bound. _Avoid_: naming Kafka, Connect, cursors, or temporary tables _中文_: 大表缺少唯一整数键

**Same-name target table exists**: _中文_: 目标端已存在同名表

**Existing target table differs from the contract**: a rerun's target table does not match the approved 表写入契约. _中文_: 目标表结构与契约不一致

**Primary key too wide to build**: _中文_: 主键过宽无法建立

**`NOT NULL` relaxed per column**: _中文_: 非空约束按列放宽

**Sub-millisecond precision truncated**: _中文_: 亚毫秒精度将被截断

**B-tier `DEFAULT` not built**: _中文_: 默认值未建（B 档）

**Sequence ceiling narrowed**: _中文_: 序列上限收窄

**No primary key and no single candidate**: _中文_: 无主键且无唯一候选

### Environment check item conclusion

Only satisfied lets migrations start; the others cannot be acknowledged away.

**Satisfied**: _中文_: 满足

**Unsatisfied**: _Avoid_: 异常, 警告 _中文_: 不满足

**Inconclusive**: _Avoid_: 未知, 跳过 _中文_: 无法判定

### Runtime condition value

The condition takes its worst item's value; only the last two stop admission (ADR-0021). 受阻 from an admission pause clears when the DBA continues (ADR-0039).

**Clear**: _Avoid_: 正常, 就绪, 可迁移 _中文_: 畅通

**Caution**: _Avoid_: 警告, 需要人工处理 _中文_: 需留意

**Impeded**: _Avoid_: 故障, 不可迁移, 异常 _中文_: 受阻

**Inconclusive**: _中文_: 无法判定

### Preflight inconclusive reason

**Query timeout**: _中文_: 查询超时

**Permission denied**: _中文_: 权限不足

**Connection lost**: _中文_: 连接中断

### Validation check

**Row count**: _中文_: 行数比对

**Primary key terminal value**: _中文_: 主键终值比对

**Null constraint conformance**: every source `NOT NULL` contract column, not only key components (ADR-0040). _中文_: 非空约束符合性

**Value sample**: a sample, never full equality, and never a checksum (ADR-0040). _Avoid_: 全量比对, 校验和, checksum _中文_: 抽样值比对

**Large record value integrity**: source-byte length over a 大记录表's large columns; length equality, never value equality (ADR-0040). _Avoid_: 内容比对, 大记录抽样 _中文_: 大记录值完整性

### Scope exclusion reason

Never migrated, so none may read as a failure.

**Operator excluded**: _Avoid_: 跳过, 忽略 _中文_: 操作员显式排除

**Preflight unsupported**: _中文_: 预检判定不可迁移

**Preflight inconclusive**: _中文_: 预检无法判定

### Mapping rule origin

**Platform origin**: _Avoid_: 平台, 系统, 自动 _中文_: DBX 自动生成

**User origin**: _Avoid_: 手动, 自定义 _中文_: 用户指定

### Connection check outcome

**Check succeeded**: _Avoid_: 可用, 正常, 在线 _中文_: 校验通过

**Check failed**: _Avoid_: 不可用, 离线 _中文_: 校验失败

**Check not run**: an absence of evidence, never a problem. _Avoid_: 失败, 未知 _中文_: 尚未校验

### TLS mode

**TLS disabled**: _中文_: 不启用 TLS

**Server authenticated**: _中文_: 校验服务端证书

**Mutual**: _Avoid_: 双向认证登录 _中文_: 双向证书校验
