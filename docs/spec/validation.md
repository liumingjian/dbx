# validation — v1 sub-spec

Pure planning and evaluation of data validation, source-baseline capture, drift checks, and manual deterministic sampling; it never opens a connection or writes workflow state.

**Read first**: ADR-0036 (row and purity), ADR-0018 (enforcement, session rule), ADR-0004 (validation executions and items), ADR-0006 (write freeze), ADR-0024 (drift checks), ADR-0037 (keyset column), ADR-0040 (check definitions, drift checks), ADR-0029 §Validation vocabulary, ADR-0008 §Plans, ADR-0022; technical plan §9.1–9.4. CONTEXT.md terms: validation plan (校验计划), validation check (校验项), validation execution (校验执行), validation disposition (校验处置), validation item state values, validation check values, source baseline (源基线), keyset column (键集列), write freeze (写冻结), task write freeze (整库冻结承诺), write complete (写入完成), migration complete (迁移完成), table migration unit (表迁移单元), run snapshot (运行快照), drift check (漂移检查), large record table (大记录表).

## Interface (`validation.api`)

- `plan(approved table write contract, pair capabilities) → ValidationPlan`: immutable enabled/disabled/not-applicable checks plus typed source and target fact plans; pure.
- `evaluate(plan, facts) → result`: for a `ValidationPlan`, a validation execution's items and conclusion; for a baseline, drift, or sampling plan, the matching result; pure.
- `baselinePlan(contract, pair capabilities) → typed SQL plan` for exact `COUNT(*)` and keyset column terminal values; pure.
- `driftPlan(original source baseline) → typed SQL plan`; pure.
- `samplingPlan(contract, source baseline, target size) → typed SQL plans` (manual deterministic sampling); pure.

All five names are final (reconciliation).

## Consumes

- `dialect.api`: `pair.validationCapabilities`, `source.validationFactPlans`, `target.validationFactPlans`, `source.baselinePlan` (baseline and drift), `source.samplingPlan`, `target.samplingLookupPlan`; value semantics come from the contract's frozen `pair.map` decisions.
- `contract.api`: the approved table write contract as an input value only; no entry point is called.
- No other module. `gateway` execution, slot management, and workflow writes happen in `orchestration`, which passes facts in (ADR-0036 §Dependencies and purity).

## Obligations

### Purity and boundary
1. The whole module, including the baseline, drift, and sampling halves, depends on no `JdbcTemplate`, HTTP client, or clock (ADR-0036 §Dependencies and purity; ADR-0018 §Enforcement).
2. No reference into another module's non-`api` package and no call to another module's side effects (ADR-0018 §Enforcement; ADR-0036 §Dependencies and purity).
3. Every emitted plan is an immutable, typed, parameterized, fingerprintable SQL plan with an expected result schema, built through dialect capabilities, never raw strings in `validation` (ADR-0008 §Plans and execution requirements).

### Validation plan
4. `plan` is deterministic: the same contract and capabilities give an equal, fingerprint-equal plan, so it can be frozen into the run snapshot at run creation (ADR-0018 §Modules; ADR-0036).
5. Only a rule in the versioned plan marks a check disabled or not applicable; an enabled required check that cannot be proven evaluates `INCONCLUSIVE`, never `NOT_APPLICABLE` or `NOT_RUN` (ADR-0004 §Validation executions).
6. The plan contains the row-count check for every unit, including exactly empty tables (TP §9.1; ADR-0002; ADR-0013).
7. Exact numeric columns get `COUNT/SUM/MIN/MAX` aggregates in matched source/target batches of at most 300 columns; floating and Boolean columns get none (TP §9.2).
8. Primary key: non-null per component; uniqueness from a proven target PK/unique constraint, else a group-by-all-typed-components count; `MIN/MAX` only for single integer, exact decimal, date, or byte-ordered binary keys; string/collated and composite extrema `NOT_APPLICABLE` (TP §9.2).
9. A table without a primary key records `NOT_APPLICABLE / NO_PRIMARY_KEY` and reduced coverage (TP §9.2).
10. 非空约束符合性 covers every column the approved contract marks `NOT NULL` on the source side, not only key components, as a per-column null count on each side folded into the obligation 7 batches; no extra scan (ADR-0040).
11. 大记录值完整性 applies only to a large record table, and within it only to the columns whose value or row exceeded 1 MiB: non-null count, `SUM`, and `MAX` of `COALESCE(OCTET_LENGTH(CAST(E AS BINARY)), 0)` on each side, in the obligation 7 batches. A column whose byte length the pair cannot prove comparable is `NOT_APPLICABLE / BYTE_LENGTH_NOT_COMPARABLE`; a table that is not a large record table is `NOT_APPLICABLE` (ADR-0040; ADR-0003).

### Evaluation
12. Row count: changed final source count → `INCONCLUSIVE / SOURCE_CHANGED`; stable source with different target count → `FAIL / ROW_COUNT_MISMATCH`; all three equal → `PASS` (TP §9.1).
13. Aggregates compare as arbitrary-precision decimals by numeric equality; all-NULL is count zero with null aggregates (TP §9.2).
14. Timeout, connection failure, unconfirmed cancellation, or unavailable proof facts yield `INCONCLUSIVE`; never sampling, skipped, or pass (TP §9.1, §9.4).
15. Each item carries type, state, stable reason, values/evidence, start/end, duration, batches, and error (TP §9.4); states are exactly `PASS / FAIL / INCONCLUSIVE / NOT_APPLICABLE / NOT_RUN` and prototype `count_mismatch`/`content_mismatch` are `FAIL` reason codes (ADR-0029 §Validation vocabulary).
16. Conclusion: required failure dominates, then required inconclusive; pass only when every enabled applicable required check is `PASS` (TP §9.4; ADR-0004 §Validation executions).
17. Results state coverage (aggregate-only, aggregate plus sampled rows, or for a large record table's large columns byte length only) and never claim full row equality or, for obligation 11, value equality (TP §9.1, §9.4; ADR-0040).
18. `evaluate` never takes or returns a validation disposition; accepted risk never changes an item (ADR-0004; CONTEXT.md Validation item state).
19. 非空约束符合性: source null count zero with a non-zero target count → `FAIL / UNEXPECTED_NULL_IN_TARGET`; a non-zero source count → `INCONCLUSIVE / SOURCE_CHANGED`, because the source contradicts its own contract. A null primary-key component keeps `FAIL / NULL_PRIMARY_KEY_COMPONENT` (ADR-0040).
20. 大记录值完整性: a differing length aggregate → `FAIL / LARGE_VALUE_LENGTH_MISMATCH`; a changed final source count → `INCONCLUSIVE / SOURCE_CHANGED` (ADR-0040).

### Source baseline and drift
21. Baseline plan reads exact `COUNT(*)` and, when the table has a keyset column, its terminal values (ADR-0037 §The keyset column; CONTEXT.md Source baseline; TP §4 step 8).
22. Baseline evaluation re-verifies keyset minimum ≥ 0 and maximum ≤ 2⁶³−1; disagreement with preflight is a table failure before its box starts (ADR-0037 §The keyset column).
23. Drift plan rereads the same facts for every already-migrated table; drift evaluation compares them with that table's original baseline and reports a difference as `INCONCLUSIVE / SOURCE_CHANGED`; a table without a keyset column compares the count alone, because its baseline holds only the count (ADR-0024 §Drift checks, §Task conclusion; ADR-0037).
24. No difference is reported as "no drift observed", never as proof of no updates (ADR-0006 §write freeze; ADR-0024 §Drift checks).
25. Drift evaluation returns a drift check result: an occasion (运行前 or 收口) plus one item per already-migrated table, in the obligation 15 item shape, never a `PASS`. It is a value only; recording it on the migration task is `workflow`'s, writing its facts is `orchestration`'s (ADR-0040).

### Manual deterministic sampling
26. Requires a primary key; default target 1000 rows; numeric single keys use evenly spaced arbitrary-precision seek thresholds; other keys the first 500 and last 500 in typed source key order; keys normalized and deduplicated; actual count reported (TP §9.3).
27. Each target lookup is typed parameterized equality on every key component and must find exactly one row (TP §9.3).
28. Value comparison follows TP §9.3's semantic rules exactly (NULL, numeric, IEEE float, `CHAR` trailing-space, `Local*` ms, `TIMESTAMP` UTC ms, bytes, `json`/`jsonb`, approved Boolean and zero-date); no checksum, hash, or canonical serialization is computed on either side (TP §9.3; ADR-0040).
29. A sample never run is `NOT_RUN` and does not block green; a run sample's `FAIL`/`INCONCLUSIVE` joins the conclusion (TP §9.3).
30. A large record table is never value-sampled: 抽样值比对 records `NOT_APPLICABLE / LARGE_RECORD_TABLE`, and obligation 11 covers it instead (ADR-0040).

## Verification

- Obligations 1–2: L1 ArchUnit rules (ADR-0018 §Enforcement).
- 3–11: L1 `ValidationContractTest` (`plan` cases per column and key kind, `NOT NULL` and large-column batch membership, plan-fingerprint equality) (ADR-0022 L1).
- 12–20: L1 `ValidationContractTest` evaluate tables per state and reason code; TP §15.1 lists validation states and accepted-risk separation.
- 21–25: L1 `ValidationContractTest` baseline and drift cases (keyset bounds, no-keyset table, drift difference and no difference, both occasions).
- 26–30: L1 `ValidationContractTest` sampling-threshold and per-type comparison cases, plus the large-record not-applicable case.
- End to end over real databases: L3 `e2eTest` scenarios through `orchestration` (TP §15.3); `validation` has no side-effect shell, so no L2 of its own (ADR-0022 §Who runs which rung).

## Slices

1. **`api` + `ValidationContractTest` skeleton**: plan, result, item, state, reason-code types and the five entry-point signatures; ArchUnit purity green. Blocked by `dialect` slice 1, `contract` slice 1.
2. **`plan`**: obligations 4–11. After slice 1; blocked by `dialect` slices 6, 8, 9, and the byte-length-comparability capability of obligation 11.
3. **`evaluate` for validation executions**: obligations 12–20. After slice 2.
4. **Baseline and drift**: obligations 21–25. After slice 1; blocked by `dialect` slice 6.
5. **Manual deterministic sampling**: obligations 26–30. After slice 3; blocked by `dialect` slices 6, 8.

## Conflicts resolved

- ADR-0018 row "Data validation; `plan`, `evaluate`" and pure "evaluation half" only → ADR-0036 row adds baseline, drift, and sampling plans, and makes the plan and evaluation halves pure (ADR-0036 §Modules, §Dependencies and purity).
- ADR-0018 `preflight` row "row-count probe" and corpus-audit's proposed baseline owner → baseline is `validation`'s; row count is not a preflight probe (ADR-0036 §Modules, §Considered options).
- ADR-0024 §Drift checks and ADR-0006 "primary key terminal value/drift" → keyset column terminal values (ADR-0037; CONTEXT.md Source baseline; TP §4 step 8).
- ADR-0029 prototype `VerifyResult` (`error`, no `INCONCLUSIVE`) → five item states, `error` becomes `INCONCLUSIVE` (ADR-0029 §Validation vocabulary).
- CONTEXT.md "Value checksum sample" → **Value sample**; 抽样值比对 is TP §9.3's manual deterministic sample and computes no checksum (ADR-0040).
- TP §9.2 non-nullness "for a primary key" only → every source `NOT NULL` contract column (ADR-0040).
- TP §9.3 sampling applied to every keyed table → a large record table is covered by 大记录值完整性 instead (ADR-0040).
- Validation plan "frozen at contract approval" (corpus-audit item 54) → frozen into the run snapshot at run creation (ADR-0018 §Modules as amended).
