# preflight — v1 sub-spec

Plans the exact preflight (预检) probes for a table and evaluates their facts into one conclusion, preflight findings (预检发现), and the envelope and byte-estimate evidence later modules read.

**Read first**: ADR-0036 (row `preflight`, §Dependencies and purity), ADR-0018 (§Enforcement, §Session rule), ADR-0003, ADR-0029, ADR-0037, TP §6.6, ADR-0002 ¶4, ADR-0038 §When the estimate exists, ADR-0022. CONTEXT.md terms: Preflight, Preflight finding, Preflight finding impact, Preflight finding code, Preflight conclusion, Preflight inconclusive reason, Large record table, Large-record envelope, Keyset column, Source baseline, Planned row count, Table write contract, Mapping rule.

## Interface (`preflight.api`)

ADR-0036's Interface column reads "Plans and evaluates probes → evidence"; the names `plan`/`evaluate` are final (reconciliation). Pure: ADR-0036's effectful-shell list is exhaustive and omits `preflight`.

- `plan` — in: one table's approved selected columns with their extraction expressions (query-mode aliases included) and the pair's mapping decisions with their required preflights; out: a preflight plan made of typed SQL plans from `dialect`; no I/O.
`preflight.api` also hosts the 预检发现 type, its 预检发现码 enum, and ADR-0029's impact table, which `contract` emits through (ADR-0029 §Who emits a finding).

- `evaluate` — in: the preflight plan plus the executed facts, or the failure of each fact to arrive; out: preflight evidence, which holds the conclusion (`SUPPORTED`/`UNSUPPORTED`/`INCONCLUSIVE`), the findings (a code, one impact, and the observed values), the large-record flag, M (the largest exact row byte length), the per-value maxima, the keyset-column choice, and the planned transfer bytes; no I/O.

## Consumes

- `dialect.api` (names per the `dialect` sub-spec): `pair.map` (mapping decisions with required preflights), `source.preflightScanPlan(table, obligations)` (combined envelope and type-domain scan, keyset min/max), `source.keysetCandidates`, `source.metadataPlan` (statistics for the byte estimate).
- Nothing else. `orchestration` executes the plans through `gateway.execute` and passes the facts in (ADR-0036 §Dependencies and purity).

## Obligations

### Boundaries
1. `preflight` references only `dialect.api` and never calls `gateway`, `connector`, or `workflow` (ADR-0036 §Dependencies and purity; ADR-0018 §Enforcement).
2. No probe queries row counts; row counts belong to the source baseline, owned by `validation` (ADR-0036 row `preflight`, §Considered options).
3. No probe targets Kafka or Connect in v1, because external-cluster probes are suspended (ADR-0036 row `preflight`; ADR-0003 status note).
4. Returns evidence only and never writes workflow state or advances a phase (ADR-0018 §Dependency direction; ADR-0008 §Ownership).

### Envelope scan
5. Plans every per-value maximum and the row maximum of `COALESCE(OCTET_LENGTH(CAST(E AS BINARY)), 0)` as **one** aggregate query per table over the approved extraction expressions `E`, never one scan per column (ADR-0003 ¶2; TP §6.6).
6. After a column is excluded, the plan covers only the approved selected columns, and the row check still applies (ADR-0003 ¶3).
7. The envelope never passes on sampling or `information_schema`; a missing exact fact yields `INCONCLUSIVE` (ADR-0003 ¶2).
8. A value or row over 20,971,520 bytes produces a 阻塞 finding, code 大记录单值 or 大记录整行, that names the table, the column or "row", and the observed bytes (ADR-0003 ¶3; ADR-0029 table; CONTEXT Preflight finding code).
9. A value or row over 1 MiB but within the envelope sets the large-record flag and produces **no** finding impact (ADR-0003 ¶1; ADR-0029 §Impact).
10. M equals the exact row maximum from item 5 (ADR-0031 §Settings; ADR-0033 §Settings).

### Type-domain checks
11. Plans, and evaluates, TP §6.6 checks 2–7: Boolean `{0,1,NULL}`, `BIGINT UNSIGNED` ≤ 2^63−1, `TIME` domain, `ENUM` membership and sentinel, zero date, and primary-key width with auto-increment bounds, each only where a mapping decision requires it (TP §6.6; ADR-0008 §Contract and mapping boundary).
12. Out-of-domain values produce a 阻塞 finding coded 值域超出目标类型, and zero dates without the approved conversion produce one coded 零日期值将被拒绝 (ADR-0029 table; CONTEXT Preflight finding code).
13. A source `auto_increment` above 2^63−1 is 阻塞; a primary key too wide to build is 数据有损 (ADR-0029 table; TP §7.3).
14. When zero-date-to-NULL is approved and the scan observes affected rows, the evidence records them so the per-column `NOT NULL` relaxation (数据有损) can be decided (TP §7.3; ADR-0029 table).
15. Findings stay within ADR-0029's table, and every emitted code is a 预检发现码 defined in `CONTEXT.md`; an ungraded or unnamed code fails the contract test (ADR-0029 §Impact, §Who emits a finding).
15a. `preflight` emits every finding established from source-side facts **and** every finding derived from a mapping decision without a probe: 亚毫秒精度将被截断, 默认值未建（B 档）, 序列上限收窄, 无主键且无唯一候选, 非空约束按列放宽, 主键过宽无法建立. `pair.map`'s notices and contract effects are already `plan` inputs, so no probe is required to grade them (ADR-0029 §Who emits a finding).
15b. `preflight` never emits the two target-side findings, 目标端已存在同名表 and 目标表结构与契约不一致; `contract.assemble` emits them, because only it holds the draft contract and the target catalog facts. No probe here reads the target (ADR-0029 §Who emits a finding; ADR-0036 §Amended by #92).

### Keyset column and bulk cap
16. Evaluates the keyset column (键集列) conditions: integer type, `NOT NULL`, unique through the primary key or a unique index, minimum ≥ 0, maximum ≤ 2^63−1 (ADR-0037 §The keyset column).
17. When several columns qualify, it chooses the primary key, and otherwise the unique index with the lowest name in the source collation (ADR-0037 §Choice).
18. A table without a keyset column gets a 阻塞 finding, coded 大表缺少唯一整数键, when `1.5 × 预估行数 × M` exceeds 64 MiB. The 预估行数 comes from `source.metadataPlan`'s statistics (bounded sampling when they are unusable) and is an input, never a probe of this module; the exact check against the 源基线 happens at run start, where a violation is the unit's 迁移失败 (ADR-0037 §64 MiB cap; ADR-0036 row `preflight`). Its explanation names neither Kafka, Connect, cursors, nor temporary tables (ADR-0037 §64 MiB cap; ADR-0030; TP §6.6 item 8).

### Conclusion
19. `INCONCLUSIVE` carries exactly one reason: 查询超时, 权限不足, or 连接中断. Timeout, cancellation, permission failure, and a lost connection all map here, and no input overrides it (ADR-0003 ¶2; ADR-0029 §INCONCLUSIVE; CONTEXT Preflight inconclusive reason).
20. `SUPPORTED` only when every required fact was evaluated and no finding is 阻塞. A 阻塞 finding from an exact fact gives `UNSUPPORTED` even if another fact is unestablished, since no retry changes it (CONTEXT Supported, Unsupported; ADR-0004 `AWAITING_APPROVAL`).
20a. Each plan carries the preflight timeout class; `gateway` applies the configured value (ADR-0003 ¶2; `gateway` obligation 12).
21. 数据有损 and 仅行为差异 findings never block and carry no acknowledgement field (ADR-0029 §Approval is the acceptance).
22. Evidence is a pure function of its inputs, and the preflight time is an input (ADR-0038 §When the estimate exists; ADR-0018 §Pure core).

### Byte estimate
23. Planned transfer bytes = `1.5 × max(DATA_LENGTH, 预估行数 × average row length)`, using bounded sampling when statistics are unusable. The 预估行数 is the same statistics-derived input as obligation 18, not the 源基线 (ADR-0002 ¶4 as amended by #92; TP §8).
24. The byte estimate never feeds the conclusion or any finding (TP §8 "capacity planning, not correctness evidence").

## Verification

All at L1 `check` (ADR-0022). `preflight` is not a side-effect shell, so L2 is not required.

| Group | Test |
|---|---|
| 1–4 | ArchUnit pure-module and api-only rules (ADR-0018, ADR-0036) plus `PreflightContractTest#dependsOnlyOnDialectApi`, `#planHasNoRowCountOrKafkaProbe` |
| 5–10 | `PreflightContractTest#envelope*`: one aggregate per table, pruned-column rerun, boundary cases at exactly 20,971,520 bytes and one byte above, 1 MiB flag, M |
| 11–15b | `PreflightContractTest#typeDomain*`: one case per TP §6.6 check 2–7, an assertion that every emitted code is present in both the impact table and `CONTEXT.md`, one case per mapping-derived finding, and `#emitsNoTargetSideFinding` |
| 16–18 | `PreflightContractTest#keyset*`: choice order, min −1 / 0, max 2^63−1 / 2^63, `1.5 × 预估行数 × M` exactly at 64 MiB and one byte above |
| 19–22 | `PreflightContractTest#conclusion*`: each inconclusive reason, no override path, determinism |
| 23–24 | `PreflightContractTest#byteEstimate*`: stats vs row-length branch; estimate absent from the conclusion inputs |

## Slices

1. **API and contract-test skeleton.** Adds `preflight.api` types (plan, evidence, conclusion, finding code, impact, inconclusive reason) and `plan`/`evaluate` stubs; `PreflightContractTest` lists every obligation as a disabled case; README. Blocked by: `dialect` slice 1.
2. **Envelope scan, obligations 5–10, 19–20a.** Blocked by: slice 1; `dialect` slice 6 (`source.preflightScanPlan`).
3. **Type-domain checks and mapping-derived findings, obligations 11–15b.** Blocked by: slice 2; `dialect` slice 3 (`pair.map` required preflights).
4. **Keyset column and bulk cap, obligations 16–18.** Blocked by: slice 2; `dialect` slice 5 (`source.keysetCandidates`).
5. **Byte estimate and preflight time, obligations 22–24.** Blocked by: slice 1; `dialect` slice 5 (`source.metadataPlan` statistics).

Slices 3, 4 and 5 are independent of one another.

## Conflicts resolved

- ADR-0018 row `preflight` "row-count … and Kafka probes" → ADR-0036 row `preflight`: no row count, Kafka probes suspended.
- ADR-0003 ¶5 external-cluster active capability check → suspended in v1 (ADR-0003 status note; ADR-0036).
- ADR-0029 table 阻塞 "structural-proof difference" → a unit's 迁移失败, never a finding (ADR-0029 #86 note; ADR-0026).
- TP §6.6 older `MAX(LENGTH(column))` → ADR-0003 byte formula (TP §6.6 ¶2).
- ADR-0001 "usable monotonic incrementing column" → the keyset column (ADR-0037).
- ADR-0019 estimate once scope is settled → estimate after preflight completes, carrying the preflight time (ADR-0038).
- ADR-0007 stage "Per-table configuration and preflight" → stage 3 预检, before mapping rules (ADR-0020).

- ADR-0006 ¶2 "connection and preflight checks verify … instance identity" → not a preflight probe: `gateway` reports the effective session facts and identity on every `execute` (`gateway` obligation 7), and `orchestration` binds the identity observed at preflight (ADR-0036 rows `preflight`, `gateway`).

- ADR-0037 §64 MiB cap "baseline row count" and ADR-0002 ¶4 "exact frozen row count" → the 预估行数 before approval, the 源基线 at run start (#92; ADR-0037 and ADR-0002 as amended).
- ADR-0029's unowned target-side rows → `contract.assemble` emits them through `preflight.api`'s code and impact (#92; ADR-0029 §Who emits a finding).

## Open items

_None._
