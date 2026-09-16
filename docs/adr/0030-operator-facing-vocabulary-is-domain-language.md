# Operator-facing vocabulary is domain language

The operator is a DBA who does not develop software and is never required to understand the execution platform. So the Chinese an interface shows is domain language, fixed in `CONTEXT.md`, not presentation left to each screen. This ADR holds the reasons behind that wording; `CONTEXT.md` keeps only the terms.

## Values, not literals

DBX persists each value as an enum literal. A literal is an identifier that happens to be readable to the people who wrote it, not a word. Every value that reaches the interface therefore has its wording in `CONTEXT.md` "Value vocabularies", and the interface may no more invent a synonym for a value than for a term. A set marked `_Operator-facing_: Never` deliberately has no wording, because no operator should be asked to read it.

## What never reaches the operator

- **Box (箱).** A box is an internal scheduling detail. Unit states named after it are presented as 等待调度 and 因关联失败而阻塞.
- **Diagnosis classification phase (诊断分类阶段).** Its values (ADR-0005, plus `ENVIRONMENT_CHECK` from ADR-0027) are cut finer than the workflow and named after execution-platform work the operator does not run. `CONNECTOR_PROVISIONING` names connector work, which the interface never shows, and the rest would present a second, differently cut phase vocabulary beside 阶段 without telling the operator anything actionable. Where DBX must say when something happened, it shows the unit's own 阶段, which every value maps into. That 阶段 is a recorded fact, never a translation: `workflow` stores the unit's 阶段 as at the moment of the error occurrence, and the interface reads it. No screen and no pure module re-derives a value that already has a true one; a static table asked to stand in for it could only disagree with the timeline ([#96](https://github.com/liumingjian/dbx/issues/96)). `ENVIRONMENT_CHECK` belongs to no unit and is shown as the 环境自检 item it concerns. A diagnosis scoped to a box or to the run (准入已暂停) shows at its own scope and borrows no 阶段. The classification stays in the diagnostic evidence for support.
- **Kafka Connect and Kafka as separate root-cause domains.** They present as one 迁移平台 domain, because telling them apart is DBX's job, not the operator's, and surfacing the split would require understanding the platform. The specific domain stays in the diagnostic evidence, so the audit record loses nothing. 迁移平台 must stay distinct from DBX 自身: one names the machinery DBX drives, the other DBX's own logic (ADR-0021).

### Every classification phase lies inside one unit 阶段

This is an invariant the catalog must satisfy, checked once by contract test, not a function called at runtime. It is what makes the recorded 阶段 a faithful answer to "when did this happen".

| 诊断分类阶段 | 落在单元 阶段 |
| --- | --- |
| `CONNECTION`, `METADATA_READ` | 已读取源结构 |
| `PREFLIGHT` | 预检中 |
| `TARGET_PREPARATION`, `CONTRACT_CHECK` | 创建目标表中 |
| `CONNECTOR_PROVISIONING`, `TRANSFER`, `COMPLETION_DETECTION` | 传输中 |
| `VALIDATION` | 校验中 |
| `CLEANUP` | 已结束 |
| `ENVIRONMENT_CHECK` | belongs to no unit |

The mapping is one-way. 等待批准, 已批准待执行 and 等待调度 have no classification phase facing them, because no diagnosed failure happens while a unit sits in them.

## Wording choices that encode a distinction

- **数据源 (Data source management)** names a navigation area only. An individual endpoint is a 数据库连接, which is why `datasource` stays under that term's `_Avoid_`.
- **Succeeded** is worded 迁移完成 because it is exactly the boundary that term defines. A second word for the same fact would suggest a second fact.
- **Skipped** is worded 已排除未迁移, not 跳过. 「没迁」 (never migrated) and 「迁了但没过」 (migrated but failed) are different facts and never share a word. The same holds for every scope exclusion reason.
- **A unit stopped by cancellation** is worded 因运行取消而停止, not 已取消. The person cancelled the 迁移运行. Reusing 取消 for the unit would claim someone decided about this table.
- **Completed with accepted risk** (完成，已接受风险) carries the accepted risk into every list the run appears in, because that is the fact a later reader most needs. It never reads as a pass.
- **Inconclusive** (无法判定) states what DBX does not know. It is neither a mild failure nor a passable risk, so it is never shown as a warning to acknowledge.
- **Estimate confidence** has two values because there are two kinds of evidence (ADR-0019). A third value would draw a boundary no data supports.
