# contract — v1 sub-spec

Assembles, renders, and proves the table write contract (表写入契约), generates its supplemental SQL (补建 SQL), and projects the abandonment list (废弃清单) from approved contracts; pure.

**Read first**: ADR-0036, ADR-0018 (enforcement, module context, session rule), ADR-0011, ADR-0008 §Contract and mapping boundary and §Registration, ADR-0026, ADR-0023, ADR-0029, ADR-0013, ADR-0010 §Schema contract, ADR-0022; technical plan §7, §15.1–15.2. CONTEXT.md terms: table write contract, structural proof (结构证明), supplemental SQL, mapping rule (映射规则), preflight finding (预检发现), preflight finding impact (预检发现影响), run snapshot (运行快照), abandonment list (projected: 预估废弃清单), routing snapshot (路由快照), table migration unit, source dialect, target dialect, database pair.

## Interface (`contract.api`)

- `assemble`: normalized source metadata, pair mapping and identifier decisions, mapping rules, preflight evidence, connector policy, approved target coordinates → a contract draft revision with fingerprint, plus that table's supplemental SQL; pure (ADR-0036; ADR-0011 §Contract assembly; ADR-0026 §Supplemental SQL Timing).
- `renderDdl`: approved contract → read-only DDL; pure (ADR-0036; ADR-0011).
- `prove`: contract plus target catalog facts → `PROVEN`, `INCONCLUSIVE`, or `REJECTED` with structured differences; pure (ADR-0036; ADR-0008 §Ownership).
- `projectedList`: approved draft contracts plus target schema facts → projected abandonment list; pure (ADR-0036; ADR-0023).
- `review`: the previous run's contract snapshot (absent on a first run) plus this run's draft → `ZeroDifference` or the changed fields; pure. Fingerprint equality is its fast path (ADR-0006 §Rerun semantics as amended by #92).
- `load`: a stored contract snapshot → the contract at its original version identity, or not-interpretable; pure (ADR-0008 §Registration; ADR-0018 §Dependency direction; added at reconciliation for recovery).
- `renderTaskSupplementalSql`: each table's supplemental SQL from its latest successful run plus the tables not yet 迁移完成 → one task-level script whose header lists them; pure (ADR-0026 §Task-level; #89 item 8; added at reconciliation under ADR-0036's "read-only rendering (ADR-0026)").

## Consumes

- `dialect.api` (names per the `dialect` sub-spec): `pair.map`, `pair.mapIdentifier`, `pair.descriptorCodec`, `target.ddlPlan`, `target.supplementalStatements`, and `target.normalizeCatalog` output as `prove` input.
- `preflight.api`: evidence values, plus the 预检发现 type, its 预检发现码 enum, and the impact table that `assemble` emits the two target-side findings through, as inputs and output types. No entry point is called (ADR-0029 §Who emits a finding).
- No other module. `orchestration` executes DDL and catalog reads through `gateway.execute` and passes the facts in (ADR-0036 §Dependencies; ADR-0018 §Dependency direction).

## Obligations

**Purity and shape**
1. No dependency on `JdbcTemplate`, an HTTP client, or a clock; only `contract.api` is referenced from outside (ADR-0018 §Enforcement; ADR-0036 §Dependencies and purity).
2. The contract is a closed typed value: a database-independent skeleton plus versioned typed dialect descriptors; no `Map<String, Object>`, arbitrary JSON, or extension bag (ADR-0008 §Contract and mapping boundary; technical plan §3.2).
3. `README.md` ≤ 40 lines, navigation only; `ContractContractTest` documents the `api` (ADR-0018 §Module context).

**Assembly**
4. One contract per table migration unit, including an exactly empty table; it belongs to the table, not its box (ADR-0013; ADR-0011 §Contract assembly).
5. The contract records the fields listed in ADR-0011 §Contract assembly and the skeleton fields in ADR-0008 §Contract and mapping boundary, including ordered columns, exact names, Connect/Avro types, JDBC binder families, nullability, defaults, primary-key order, identity or sequence intent, routing, notices, version identities, and approval revision.
6. `assemble` is the only producer of a contract. It verifies completeness and evidence/version linkage, and rejects conflicts, blocking findings, and unsupported mapping decisions. A draft is never approvable while it carries a 阻塞 finding, whether that finding arrived in the preflight evidence or was emitted here by obligation 12a (ADR-0008 §Contract and mapping boundary; ADR-0029 §Who emits a finding).
7. A draft is approvable only when its preflight conclusion is `SUPPORTED`. A mapping change yields a new draft revision (ADR-0011 §Contract assembly; ADR-0004 `AWAITING_APPROVAL`).
8. A `USER` mapping rule overrides an `AUTO` rule. Column prune and rename take effect in the Source query projection, never DDL-only (technical plan §7.1; ADR-0011 §Contract assembly).
9. Approved coordinates, including a dialect-decided rename (`<prefix>_<hash12>`) with its rule, full coordinate, and algorithm version, are frozen in the contract. No later phase re-derives them (ADR-0008 §Contract and mapping boundary; technical plan §7.1).
10. The frozen `TypeMapper` decision is stored, never recomputed from defaults (ADR-0008 §Contract and mapping boundary; ADR-0010 §Schema contract).
11. The writable-table boundary is only: columns with exact types; `NOT NULL` except the approved per-column zero-date relaxation; primary key, or an operator-approved single candidate; identity or an owned sequence for `numeric(20,0)`; and whitelisted defaults obtained from the target dialect (ADR-0011 §DDL and structural proof; ADR-0026 §The switches are cut; technical plan §7.3).
12. Non-blocking preflight findings are carried in the contract, so approval is their acceptance (ADR-0029 §Approval is the acceptance).
12a. `assemble` emits the two target-side findings itself, because only it holds both the draft contract and `target.normalizeCatalog` facts: 目标端已存在同名表 on a first run, and 目标表结构与契约不一致 on a rerun. Both are 阻塞, both use `preflight.api`'s code and impact, and neither is graded here (ADR-0029 §Who emits a finding; TP §7.1 "A first-run target name collision is blocking").
12b. The rerun finding is computed by reusing `prove` against the existing table, and the whole difference set folds into **one** finding, never one per invariant. The `STRUCTURED` per-invariant differences stay available for the review drawer (obligation 21; ADR-0029 §Who emits a finding).
13. Contract, DDL, and fingerprint are deterministic for equal inputs (technical plan §15.1).
14. The contract exposes enough to count, per table, whether it has a primary key, which columns have relaxed `NOT NULL`, and which objects were deferred to supplemental SQL (ADR-0026 §Where the contract rendering appears, item 2).

**Rendering**
15. `renderDdl` derives DDL solely from the approved contract. DDL is never a contract fact or an editable input (ADR-0011; ADR-0008 §Contract and mapping boundary).
16. Identifiers are always quoted and preserved character-for-character (ADR-0011 §Contract assembly).

**Supplemental SQL**
17. Content follows ADR-0026 §Supplemental SQL: unique constraints, indexes, comments, and B-tier `SET DEFAULT` are executable; foreign keys come last; `ON UPDATE CURRENT_TIMESTAMP` and collation appear only as comments.
18. It uses approved target names. A statement touching a pruned column, or a foreign key referencing an out-of-scope table, is emitted commented out with its reason. Nothing is dropped (ADR-0026 §Supplemental SQL).
19. It is produced by the same pure call and from the same metadata snapshot as the contract (ADR-0026 §Supplemental SQL Timing).

**Structural proof**
20. `prove` checks every invariant in ADR-0011 §DDL and structural proof and technical plan §7.4. Column order is recorded but is not a difference on its own.
21. Each difference carries a coordinate, the expected value, the actual value, and the violated invariant (ADR-0026 §Structural proof).
22. Only zero difference yields `PROVEN` (ADR-0008 §Ownership; ADR-0011).
23. A difference is `STRUCTURED` evidence for phase `CONTRACT_CHECK`, never a preflight finding (ADR-0005 §Classification; ADR-0029 #86 amendment).
24. Proof never inserts or deletes a row; no `contract` output is a data-writing plan (ADR-0011 §DDL and structural proof).

**Projected abandonment list**
25. The list is rendered only from approved draft contracts. It names the tables that would be created, states whether DBX would create the schema, and states that abandonment never cascades (ADR-0023, "Before any run exists").
26. The list is labelled projected and is never a confirmation input (ADR-0023).

**Versioned snapshots**
27. A snapshot records the contract schema version, the dialect and descriptor versions, the pair and mapping versions, the approval revision, and the fingerprint (ADR-0008 §Registration, persistence, and recovery).
28. It also records converter identity, the subject strategy, and the expected schema fingerprint. The converter configuration fingerprint is `connector`'s configuration fingerprint, and the observed schema id is a run fact in `workflow`, never a contract field (ADR-0010 §Subjects; ADR-0011; ADR-0036 `connector` row).
29. A codec loads any snapshot version needed by a nonterminal run and preserves its original version identity, or it returns not-interpretable. It never re-runs mapping (ADR-0008 §Registration).

## Verification

- `review`: L1 `ContractContractTest#review*`: equal fingerprint → `ZeroDifference`, one changed field per contract field, absent previous snapshot, and a previous snapshot at an older descriptor version.
- 1–3: L1 `check`. ArchUnit pure-module rule, `api`-only rule, README-limit test.
- 4–14: L1 `ContractContractTest`, plus property tests for determinism (13) and fixtures for completeness, rejection, and rule precedence (technical plan §15.1).
- 15–16: L1 golden set 2, "Table write contract → DDL rendering" (ADR-0022 §Golden files), updated only with `-Pgolden.update=<name>` plus a `Golden-Update` trailer.
- 17–19: L1 `ContractContractTest`, supplemental-SQL boundary cases (technical plan §15.1).
- 20–24: L1 `ContractContractTest`, one mutation per invariant against fixture catalog facts. L2 `seamTest`: technical plan §15.2 on PostgreSQL 15, which executes every representative rendered DDL, requires `PROVEN`, and requires `REJECTED` for each single mutation.
- 25–26 and the task-level script: L1 `ContractContractTest`.
- 27–29: L1 codec round-trip fixtures, one per snapshot version, plus a not-interpretable case.

## Slices

1. **`api` + `ContractContractTest` skeleton**: value types, all entry-point signatures, proof outcome and difference types, projected-list type, README, ArchUnit green. Blocked by `dialect` slice 1 and `preflight` slice 1.
2. **`assemble` and fingerprint**: obligations 4–13. Blocked by slice 1; `dialect` slices 3 and 4.
3. **`renderDdl` and golden set 2**: obligations 14–16. Blocked by slice 2; `dialect` slice 7.
4. **Supplemental SQL inside `assemble`, and `renderTaskSupplementalSql`**: obligations 17–19. Blocked by slice 2; `dialect` slice 7.
5. **`prove`, the target-side findings, and `review`**: obligations 12a–12b, 20–24 at L1. Blocked by slice 2; `dialect` slice 8; slice 7 for `review`'s snapshot codec.
6. **`projectedList`**: obligations 25–26. Blocked by slice 2.
7. **Versioned snapshot codec and `load`**: obligations 27–29. Blocked by slice 2; `dialect` slice 2.
8. **L2 §15.2 seam test**, hosting TP §15.2 for `dialect` and `contract`. Blocked by slices 3 and 5; `gateway` slice 4.

## Conflicts resolved

- ADR-0018's contract interface "`assemble`, `renderDdl`, `prove`" → ADR-0036 adds read-only rendering (ADR-0026) and the projected list (ADR-0023).
- ADR-0011's body puts the Sink settings, default whitelist, and identity rules under the contract → ADR-0011's status note (ADR-0018): they belong to the target dialect in `dialect`, and the contract reaches them only through the dialect interface.
- ADR-0011 leaves supplemental SQL timing and delivery to the product shell → ADR-0026 §Supplemental SQL decides both.
- ADR-0029's original 阻塞 row lists "structural-proof difference" → the #86 amendment and ADR-0026 §Structural proof: it is the unit's 迁移失败, never a finding.
- ADR-0008 ("the core alone produces the routing snapshot, configuration fingerprint, execution signature") and corpus-audit §5's proposed `contract.routingSnapshot` → ADR-0036: `connector` derives all of them in one place. `contract` only freezes the approved coordinates and routing decisions.
- ADR-0008 says the target dialect "reads and compares" the actual structure, while ADR-0011 puts proof in the contract → ADR-0036 gives `prove` and its verdict to `contract`. The dialect supplies target-specific normalization through its interface.
- ADR-0010's contract snapshot records the "observed schema identifier" → the contract is immutable at approval (ADR-0011): the observed id is a run fact in `workflow`; the converter configuration fingerprint is `connector`'s (ADR-0036).

## Implementer decides

- When `prove` returns `INCONCLUSIVE`: only when a required catalog fact is absent from its input, never on a difference; any non-`PROVEN` fails the unit (ADR-0008 §Ownership; ADR-0026 §Structural proof).
- The golden-set name for "contract → DDL": one stable name per set, used with `-Pgolden.update=<name>` (ADR-0022).

- ADR-0029's unowned target-side rows and ADR-0006's zero-difference review had no owner → `assemble` emits both findings, reusing `prove` for the rerun comparison, and `review` computes the zero-difference review (#92; ADR-0029 §Who emits a finding; ADR-0036 §Amended by #92).

## Open items

_None._
