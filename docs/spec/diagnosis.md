# diagnosis — v1 sub-spec

Translates error occurrences into catalogued diagnoses and computes the content of the diagnostic package (诊断包); pure throughout.

**Read first**: ADR-0036, ADR-0018 (§Enforcement, §Module context, §Session rule), ADR-0005, ADR-0028, ADR-0027 §Diagnoses, ADR-0030, ADR-0021, ADR-0032, ADR-0033, ADR-0022, #89 items 1 and 6; CONTEXT.md terms: error occurrence (错误事件), diagnosis (诊断), diagnosis rule (诊断规则), root-cause domain (根因域), diagnosis classification phase (诊断分类阶段), routing snapshot (路由快照), diagnostic package, diagnosis source kind, box, table migration unit, runtime condition (运行状况), environment check (环境自检).

## Interface (`diagnosis.api`)

- `diagnose` — input: an error occurrence, the run's routing snapshot, the shipped catalog; output: one diagnosis (code, catalog version, source kind, primary phase, primary root-cause domain, trusted scope, message keys, evidence summary, normalized fingerprint for aggregating repeats); pure (ADR-0036, ADR-0005 §Decision, §polling paragraph).
- `package(inputs)` — input: scope (installation or run) and already-gathered evidence; output: the package's files, Chinese `README` manifest and checksums; `orchestration` zips, streams, and shows the manifest before export from this same output; pure (ADR-0036, ADR-0028, ADR-0005 "local ZIP").
- `validateCatalog` — input: the catalog resource; output: pass, or the rejections of obligation 14; pure. Added at reconciliation: `environment`'s E0 folds in ADR-0005's startup validation (ADR-0027 §Catalog).

## Consumes

`connector.api`'s routing-snapshot type (no call; `connector` derives it, ADR-0036). Every other input is a `diagnosis.api` record `orchestration` fills, so `environment` can call `validateCatalog` without a cycle (ADR-0036 §Dependencies and purity).

## Obligations

**Purity and boundary**
1. The module depends on no `JdbcTemplate`, HTTP client or clock; export time and all facts arrive as inputs (ADR-0018 §Enforcement, ADR-0036 §Dependencies and purity).
2. Only `diagnosis.api` is referenced from outside; README ≤ 40 lines naming `diagnose`, `package`, the contract test and ADRs (ADR-0018 §Module context).
3. A diagnosis never changes workflow state; outputs carry no command or transition (ADR-0005 §Classification).

**Classification**
4. Every diagnosis has exactly one primary phase from ADR-0005's ten plus `ENVIRONMENT_CHECK`, and exactly one of the seven root-cause domains (ADR-0005 §Classification, ADR-0027 §Diagnoses).
5. Kafka Connect and Kafka stay distinct domains in the diagnosis and its evidence; collapsing them into 迁移平台 is presentation only (ADR-0030, CONTEXT.md Root-cause domain).
6. Source kind is exactly one of `STRUCTURED`, `EXTERNAL_TRANSLATION`, `SYSTEM_FALLBACK` (ADR-0005 §Decision).
7. Table and field coordinates come only from the routing snapshot: structured context first, then topic, connector and exception coordinates as corroboration; a coordinate not matched uniquely leaves scope at box or connector, never a guessed table or field (ADR-0005 §Classification).

**Evidence and matching**
8. Evidence precedence is structured DBX evidence, then stable protocol/database/HTTP codes, then the deepest trustworthy cause, then anchored text patterns constrained by component, phase and version (ADR-0005 §Evidence and matching).
9. A broad wrapper (`Exiting WorkerSinkTask due to unrecoverable exception`) never defeats a more specific nested cause (ADR-0005 §Evidence and matching).
10. Same-strength disagreeing rules yield `DBX-RULE-CONFLICT`; no trustworthy rule yields `DBX-UNKNOWN`; neither names a cause (ADR-0005 §Decision, §Operator presentation).
11. Unknown and conflict copy says the cause is not identified and tells the operator to preserve topics and target data, download the diagnostic package and contact support (ADR-0005 §Operator presentation).
12. The redacted exception chain and every raw trace in a diagnosis's evidence pass the value scrubber of obligation 27 (ADR-0005 §Operator presentation, ADR-0028 §No data values).

**Catalog**
13. The catalog is one versioned JSON resource shipped in the distribution and the sole rule source; no database, environment or UI override (ADR-0005 §Rule catalog).
14. Catalog validation rejects duplicate codes, duplicate message keys, unknown enum values, bad version constraints and non-compilable restricted Java regexes (ADR-0005 §Rule catalog, ADR-0027 E0).
15. Codes are stable and never reused; each diagnosis records its catalog version (ADR-0005 §Rule catalog).
16. Messages are zh-CN only, addressed by message keys; no second locale ships in v1 (#89 item 6).
17. Twenty-one `EXTERNAL_TRANSLATION` families ship: ADR-0005's twenty plus MySQL 1114 源库临时空间耗尽 (`SOURCE_DATABASE`, `TRANSFER`) (ADR-0005 §Rule catalog, #89 item 1).
18. Family 14 (oversize) names the transport boundary that rejected the record, the serialized size when available, and the keep-freeze / inspect-lengths / new-run action (ADR-0003, runtime-oversize paragraph).
19. Family 21 says DBX reads in chunks using up to 64 MiB of source temporary space per connection, and tells the DBA to check the InnoDB temporary tablespace and data-directory free disk; not folded into family 1 (ADR-0033, #89 item 1).
20. Structured codes in the catalog cover: box `STUCK`; box 超出 24 小时上限; preflight conclusions; the target-contract reverse-check difference (coordinate, expected, actual, violated invariant); validation results; execution platform unreachable (Kafka, Connect or Schema Registry); 迁移平台在传输中重启; 迁移平台无法启动新的读取; and one code per environment-check item E0–E8 under `ENVIRONMENT_CHECK` (ADR-0005 §Decision, ADR-0026, ADR-0021, ADR-0039, ADR-0032, ADR-0027, #89 item 5).
20b. 准入已暂停 has no code of its own: it is a state the run holds, not an occurrence, and a diagnosis carries exactly one primary phase while the pause belongs to no unit or box. Its two trigger codes 迁移平台在传输中重启 and 迁移平台无法启动新的读取 are its catalogued form (ADR-0039 §No catalog code, [#96](https://github.com/liumingjian/dbx/issues/96)).
20c. 超出 24 小时上限 is classified `TRANSFER` / `PLATFORM` at box scope: the rule that stopped the box is DBX's own and DBX cannot prove the source was slow. Its message states that transfer passed the 24 h limit and DBX stopped the box, names the box's tables as affected, and recommends keeping topics and target data and re-running with a smaller scope. It never reuses 卡死 or 超时, which name a different fact (ADR-0001, ADR-0030, [#96](https://github.com/liumingjian/dbx/issues/96)).
21. Structured and fallback codes do not count toward the 21 families (ADR-0005 §Decision, ADR-0027 §Diagnoses).
22. A diagnosis's message answers, in order: what happened, where, what is affected, one recommended action (ADR-0005 §Operator presentation).

22b. The classification phase is never rendered to the operator and `diagnose` output carries no unit 阶段: the 阶段 an error card shows is the fact `workflow` recorded on the occurrence. Every catalogued phase must lie inside the unit 阶段 ADR-0030 maps it to (ADR-0030 §Every classification phase, [#96](https://github.com/liumingjian/dbx/issues/96)).

**Diagnostic package content**
23. An installation package holds the latest startup environment-check conclusions, a fresh check taken at export, the runtime condition change record, orphan `dbx-` resources, and versions (ADR-0028 §Scopes, ADR-0021).
24. A run package is an installation package plus the run's snapshot, timeline, error occurrences, diagnoses and ADR-0005 evidence, and its frozen pre-admission environment conclusions beside the fresh check (ADR-0028 §Scopes, ADR-0027 §Evidence).
25. Run-package evidence includes each Connect restart's time and OOM evidence, live heap usage, and Schema Registry identifiers, fingerprints, converter versions, compatibility observations and redacted Registry responses (ADR-0032, ADR-0031, ADR-0010 §Cleanup and diagnostics).
26. No credential, token, private key, master key, record value, primary-key value, SQL parameter value or worker/broker log enters a package; there is no opt-in for values (ADR-0005 §Operator presentation, ADR-0028 §No data values, ADR-0006).
27. Raw Connect REST responses and traces are scrubbed of values (e.g. `(id)=(123)` in `Key (id)=(123) already exists`); a segment the scrubber cannot prove clean is replaced whole by a placeholder (ADR-0028 §No data values).
28. Host names or IPs, ports, database usernames and database, table and column names appear unaltered, and the manifest names these fields (ADR-0028 §Identifiers).
29. The Chinese `README` manifest lists every file, its content category, what is excluded and what was truncated; every other file is JSON with checksums (ADR-0028 §Manifest).
30. The package records release version, the whole `release.json`, catalog version, catalog file hash and expected-configuration snapshot version (ADR-0028 §Reproduction, ADR-0035).
31. Output is at most 50 MB: coalesced progress samples are dropped first, then older timeline entries; every terminal evidence item is kept and the cut is stated in the manifest (ADR-0028 §Bound).

## Verification

- 1–3: L1 ArchUnit rules and README-limit test; `DiagnosisContractTest` asserts `diagnose` output has no command type.
- 4–12: L1 `DiagnosisContractTest` (classification, scope-by-routing, precedence, wrapper, conflict, unknown copy, scrubbed evidence).
- 13–16: L1 `DiagnosisCatalogContractTest` with a malformed-catalog fixture per rejection, and a zh-CN-only key check.
- 17–22: L1 golden set `error-translation` (ADR-0022 golden 3): per family positive, negative, overlap and redaction fixtures → code and explanation; structured codes as fixed inputs. L3 `e2eTest` exercises all 21 families (technical plan §15.3).
- 20b, 20c, 22b: L1 `DiagnosisCatalogContractTest` asserts no code exists for 准入已暂停, that 超出 24 小时上限 is `TRANSFER` / `PLATFORM` at box scope and shares no wording with `STUCK`, and that every catalogued classification phase lies inside the unit 阶段 of ADR-0030's table; `DiagnosisContractTest` asserts `diagnose` output carries no 阶段.
- 23–31: L1 `DiagnosticPackageContractTest` per scope, planted-value scrubber fixtures, manifest completeness, a 50 MB truncation case, version fields.

## Slices

1. **api + contract skeleton** — `diagnosis.api` types and both entry points as stubs, README, empty `DiagnosisContractTest` and `DiagnosticPackageContractTest`, ArchUnit purity green (1–3). Blocks: none.
2. **Catalog and `validateCatalog`** — JSON resource schema, loader from the classpath, validation (13–16). Blocks on 1.
3. **Classification and matching engine** — precedence, wrapper rule, conflict and unknown fallbacks, routing-snapshot scope, normalized fingerprint (4–12). Blocks on 2; `connector` slice 1 (routing-snapshot type).
4. **Structured codes** — catalog entries and mapping for every item of obligation 20, `ENVIRONMENT_CHECK` codes included (20, 20b, 20c, 21, 22b). Blocks on 3. Inputs are this module's own records.
5. **External families 1–11** with golden fixtures (17, 22). Blocks on 3.
6. **External families 12–21** with golden fixtures, including oversize and 1114 text (17–19, 22). Blocks on 5.
7. **Value scrubber** — whole-segment placeholder, identifier pass-through (12, 27, 28). Blocks on 1.
8. **Package content** — scopes, exclusions, manifest, checksums, versions, 50 MB bound (23–26, 29–31). Blocks on 7; inputs are this module's own records.

## Conflicts resolved

- ADR-0005 and ADR-0022 golden 3 "20 families" → 21 families (#89 item 1; technical plan §9.5).
- ADR-0005 "reserves locale keys … falling back to Chinese" and ADR-0017/0020 two-locale layout → v1 zh-CN only, keys kept (#89 item 6).
- ADR-0018 row `diagnosis`: `diagnose` only → adds `package(inputs)` (ADR-0036).
- ADR-0005 package includes "raw REST responses" → included only after value scrubbing (ADR-0028 §No data values).
- ADR-0005 package "bounded" → 50 MB with a fixed truncation order (ADR-0028 §Bound).
- ADR-0021 unreachable platform = Kafka or Connect → Schema Registry joins (ADR-0039).
- corpus-audit §1 puts package assembly and export audit in `diagnosis` → `orchestration` assembles and audits; `diagnosis` computes content only (ADR-0036).

- Unassigned repeat-aggregation fingerprint (ADR-0005) → `diagnose` output; `workflow` aggregates occurrences (ADR-0036 `diagnosis` row).

## Implementer decides

- Code strings (beyond `DBX-UNKNOWN`, `DBX-RULE-CONFLICT`) and the catalog JSON schema: codes stable and never reused (ADR-0005 §Rule catalog).

## Open items

- None. D-19, D-20 and D-21 were settled by [#96](https://github.com/liumingjian/dbx/issues/96) and live in obligations 20, 20b, 20c and 22b.
