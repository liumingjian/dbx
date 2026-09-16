# scheduling — v1 sub-spec

Pure module: turns a run's preflighted units into an immutable scheduling plan (调度计划), decides the next box under rolling admission (滚动准入), and replays the plan to give the duration estimate (预估耗时) and remaining-time estimate (预计剩余).

**Read first**: ADR-0036 (module table, purity), ADR-0018 (enforcement, session rule), ADR-0002, ADR-0013, ADR-0031, ADR-0033 §read-ahead, ADR-0039, ADR-0021 §Values, ADR-0019, ADR-0034, ADR-0038, ADR-0024 §Planning, ADR-0022; #89 item 2. CONTEXT.md terms: Box (箱), Execution signature (执行签名), Admission paused (准入已暂停), Platform memory budget (平台内存预算), Large record table (大记录表), Minimum window (窗口下限), Reference throughput band (参考吞吐带), Estimate unavailable (无法预估), Low confidence (低置信), Reliable (可信).

## Interface (`scheduling.api`)

All pure: no `JdbcTemplate`, HTTP client, or clock; time and facts arrive as arguments (ADR-0018, ADR-0036).

- `plan`: in: the run's units (execution signature with its frozen connector settings, planned transfer bytes, baseline row count, M, large-record flag, exact-empty flag — the two counts are distinct: planned transfer bytes was computed at preflight from the **预估行数**, while the baseline row count is the exact 源基线 available by plan time; #92), connection budgets, Connect task count, effective Connect heap, Kafka disk budget → out: scheduling plan (boxes, order, R per box). Pure.
- `admit`: in: plan, running boxes, current occupancy on every gate, effective heap, the condition's admission stop signal, the run's open admission pause → out: next box, or a wait naming the binding gate. Pure.
- `proposeSplit`: remaining tables' plan inputs, rate source, write-freeze limit → table sets each fitting the limit, plus tables whose 窗口下限 exceeds it. Pure (ADR-0024 §Planning; added at reconciliation).
- `predict(run-history rates)`: in: plan (whole, or only its unfinished part), the admission inputs, a rate source (the reference table, per-source history samples, or observed in-run rates), preflight staleness, elapsed time → out: range (low, high), 窗口下限, confidence, source; or no number with a reason. Pure.

## Consumes

None. `orchestration` fills `scheduling.api`'s own records: the signature as an opaque key plus settings values (from `connector.executionSignature`), history from `workflow.api.query`, reference rates from `release.json`. No foreign type, so `connector` may use the box type without a cycle (ADR-0036).

## Obligations

### Plan

1. Group tables by identical execution signature. Tables in different groups never share a box (ADR-0002).
2. Give each query-mode, naming-exception, large-record, and oversized ordinary table its own box (ADR-0002, ADR-0013).
3. Exactly empty tables get no box. They appear in the plan with no box reference (ADR-0013, ADR-0004 phase 6).
4. Within a group, pack the remaining tables by largest-processing-time-first on planned transfer bytes, with at most 50 tables per box (ADR-0002, TP §8).
5. Box target size = Kafka disk budget ÷ computed maximum concurrency, where computed maximum concurrency = `min(10, Connect tasks ÷ tasks per box, source connection budget, target connection budget, ⌊(effective heap − B) ÷ R_narrow⌋)`. It is static and tier-derived (#89 item 2).
6. Each connection budget = 10% of that database's `max_connections`, clamped to 4–20, with two connections reserved on each side. Connect tasks default to twice the logical CPU count (ADR-0002).
7. M for a box = the largest exact row byte length among its tables (ADR-0031 §Per-box bounds).
8. R per box = `buffer.memory × E + read-ahead + 2 × fetch.max.bytes + max.poll.records × M × X`. The settings come from the box's execution signature and are never re-derived here. Read-ahead is ADR-0033's term. E = 100 for ordinary boxes and 3 for large-record boxes; X = 3; B = 512 MiB, all as versioned constants (ADR-0031 §Platform memory budget, ADR-0033).
9. The same input always gives a byte-identical plan, including tie-breaks (ADR-0022 §Golden files item 4).
10. `plan` is called once, at run creation. Recovery reuses the stored plan, and only a rerun calls `plan` again (ADR-0002, ADR-0006). The module offers no repack operation.

### Admit

11. Admit a box only while every gate holds. Take the tightest of the six: Connect tasks, at most 10 connector-active boxes, source connections, target connections, Kafka disk, and platform memory budget (ADR-0002, ADR-0031, TP §8).
12. Kafka disk gate: admission uses at most 60% of currently available data-disk capacity, at the pre-admission recheck. Retained topics and reclaimable occupancy (待回收占用) count as occupied (ADR-0002, ADR-0021, TP §11.2).
13. Platform memory budget gate: ΣR over running boxes plus the candidate's R ≤ effective Connect heap − B. The heap is the one read at the pre-admission environment check, never live usage (ADR-0031 §Platform memory budget).
14. Large-box starvation protection applies to the cumulative gates, disk and memory (ADR-0002, ADR-0031).
15. Admission is rolling. Admitting a box never waits for another box to finish (ADR-0002, CONTEXT Rolling admission).
16. A wait names its binding gate. The memory gate is reported as 平台内存预算, beside the connection and disk budgets (ADR-0031 §Operator wording).
17. No box is admitted while the condition's stop signal is set (受阻 or 无法判定) or while the run holds an open 准入已暂停 (ADR-0021 §Values, ADR-0039).
18. No input to `admit` is a live heap figure, a duration or remaining-time estimate, or a failure rate (ADR-0031 §Observation, ADR-0019, ADR-0002).

### Predict: shape and replay

19. A large-record table streams at the large-record band. Every other table streams at `L ÷ (a + b × L)`, where L = planned bytes ÷ baseline row count (the exact count, not the 预估行数 that produced planned bytes; #92). L is clamped to the narrow and wide anchors and never reaches the large-record rate. a and b are fitted separately for the band's low and high ends (ADR-0034 §Shape rate per table).
20. The replay runs the plan through `admit` itself, largest first, with every gate including memory. Each box advances at the rate of the table it is transferring (ADR-0034 §Combination).
21. While the running rates sum above the shared ceiling (reference 145–151 MiB/s), scale them all down in proportion. The same ceiling serves every tier (ADR-0034 §Combination, §Memory tiers).
22. The low end is one replay with every parameter fast. The high end is one replay with every parameter slow. Ends are never mixed. There is no sampling, no jitter allowance, and no safety factor (ADR-0034 §Range ends, ADR-0038).
23. 窗口下限 is the largest table's solo time within the same replay (ADR-0034).
24. Drift-check scans of already-migrated tables are included in the estimate (ADR-0024 §Drift checks).
25. A draft estimate exists only after preflight completes. If any table's preflight is stale, return no number with the reason 「预检已过期，重新预检后更新」 (ADR-0038 §When the estimate exists).
26. An estimate from reference data carries source 参考吞吐带, the reference machine spec, and ADR-0034's fixed sentence. Its range is not widened (ADR-0034).

### Predict: history

27. Stream rates, a, b, and the large-record rate come only from the source data source's samples. The ceiling comes only from the target data source's observations (ADR-0038 §Scope).
28. A sample is one finished table transfer of at least 60 s, whatever its run's outcome. A ceiling observation is a stretch of at least 5 min with at least 4 boxes running. Samples whose estimate-basis fingerprint (ADR-0031 and ADR-0033 constants and settings, plus `DBX_MEMORY_TIER`) differs from the current basis are ignored (ADR-0038 §Sample, §Comparability).
29. Minimum samples per parameter: a and b need at least 5 samples with max L ≥ 4 × min L. The large-record rate needs at least 2. The ceiling needs at least 2 observations. A parameter below its minimum falls back to the reference band on its own (ADR-0038, ADR-0034 §History).
30. Fit a and b by least squares. The slow end = the fit × the smallest observed/fitted ratio; the fast end = the fit × the largest. The large-record rate and the ceiling take their observed minimum and maximum (ADR-0038 §Range ends).
31. The estimate is 可信 only when every parameter came from history. Otherwise it is 低置信 (ADR-0034, ADR-0019).

### Predict: in-run

32. The remaining-time estimate replays the unfinished part of the plan at each stream's observed rate (ADR-0019, ADR-0034).
33. It replaces the duration estimate only after 5 minutes and 1% of data (ADR-0019, ADR-0002).
34. Return 无法预估 with a reason, and with bytes transferred of total and current throughput, when elapsed time passes the duration estimate's upper bound or the live range stays unstable. Return to a range once throughput stabilises (ADR-0019).

## Verification

All rungs are L1 (`check`). The module has no shell, so it needs no L2 (ADR-0022, ADR-0036).

- Interface and purity: `SchedulingContractTest`, plus the existing ArchUnit pure-module rule.
- Obligations 1–10: `SchedulingContractTest#plan*` and the box-plan golden set (ADR-0022 item 4). Updates only via `-Pgolden.update=<name>` with a `Golden-Update` trailer.
- Obligations 11–18: `SchedulingContractTest#admit*`: each gate binding, the stop signal, the pause, and the tier examples (ADR-0031 §Deployment memory tiers).
- Obligations 19–26: `SchedulingContractTest#predict*`. Acceptance fixture: the replay of #78's reference dataset lands in 88–92 MiB/s at ≥16 GiB and 72–97 MiB/s at 8 GiB (ADR-0034 §Memory tiers). S9 re-runs it at L3.
- Obligations 27–31: `SchedulingContractTest#history*`.
- Obligations 32–34: `SchedulingContractTest#remaining*`.

## Slices

1. **api skeleton**: `plan`, `admit`, and `predict` signatures, input and output records, the constant table, `SchedulingContractTest` with pending cases, and the README. Blockers: none.
2. **plan** (1–10) with the box-plan golden set. Blocked by slice 1; D-5, D-6, D-8.
3. **admit** (11–18). Blocked by slice 2; D-5, D-7.
4. **predict, reference rates, and `proposeSplit`** (19–26), with the reference-dataset acceptance fixture. Blocked by slice 3.
5. **predict, history** (27–31). Blocked by slice 4.
6. **predict, in-run** (32–34). Blocked by slice 4; D-9.


## Conflicts resolved

- ADR-0018 module row "`plan`, `admit`" → ADR-0036 adds `predict(run-history rates)` and the estimate.
- ADR-0002 ¶4 "exact frozen row count" behind planned transfer bytes → the 预估行数 at preflight; the exact 源基线 count is a separate `plan` input (#92; ADR-0002 as amended).
- ADR-0002 "five gates" → ADR-0031: a sixth, cumulative memory gate.
- ADR-0002 "computed maximum concurrency" (undefined) → #89 item 2's formula.
- ADR-0002 "no first-run estimate" → ADR-0019: an estimate before every run. ADR-0002's gate governs only the handover to the remaining-time estimate.
- ADR-0019 "bytes ÷ a throughput range" → ADR-0034: plan replay on shape rates under a shared ceiling.
- ADR-0019 and TP §10 "once the migration scope is settled" → ADR-0038: only after preflight completes, with no stage-2 estimate.
- ADR-0019 and ADR-0034 "this deployment's past runs" → ADR-0038: per source data source, with the ceiling per target.
- ADR-0034 "history refits parameter by parameter" (no minimum) → ADR-0038's minimums and residual-ratio ends.
- corpus-audit §5 `estimate` → ADR-0036's `predict`; the split stays here as `proposeSplit` (ADR-0024 §Planning); the freeze-limit warning is `orchestration`'s.
- Unowned history storage → `workflow` persists it (sole H2 writer, ADR-0036 §Considered options).
- corpus-audit §5 signature derivation here → `connector` derives it (ADR-0036).

## Implementer decides

- The reference-table record layout: must hold ADR-0034's bands, anchors, ceiling, and machine spec, versioned together (`release` R3).

## Open items

- **D-5** (T2): tasks per box, per-box task and connection demand, logical CPU count source (#89 item 2). Blocks slices 2, 3.
- **D-6** (T2): the M that defines R_narrow (ADR-0031: "about 512 MiB"). Blocks slice 2.
- **D-7** (T2): the large-box starvation protection rule (ADR-0002, ADR-0031). Blocks slice 3.
- **D-8** (T2): whether DBX's own JDBC connections count against the budgets (ADR-0002 "two reserved"). Blocks slice 2.
- **D-9** (T2): the "unstable across consecutive samples" criterion (ADR-0019). Blocks slice 6.
