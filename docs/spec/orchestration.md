# orchestration — v1 sub-spec

The use cases `web` calls and their drivers; sole caller of `workflow.api.command` and sole sequencer of other modules' side effects (ADR-0018 §Dependency direction, ADR-0036).

**Read first**: ADR-0036, 0018, 0004, 0006, 0001, 0024, 0039, 0032, 0021, 0023, 0027, 0028, 0026, 0035, 0008 §Ownership; #89 items 5, 10. CONTEXT.md terms: migration run, table migration unit, box, run snapshot, write freeze, task write freeze, source baseline, target generation, cancellation, discard, abandonment, abandonment list, admission paused (准入已暂停), runtime condition (运行状况), environment check, connection check, re-migration, diagnostic package.

## Interface (`orchestration.api`)
All effectful; each command takes an idempotency key (ADR-0004). Names are final; each `web` endpoint maps to one. `*` = added at reconciliation, since `web` reaches pure modules only here (ADR-0018).
- `saveConnection`, `checkConnection`, `supportedPairs`*
- `saveDraft`, `discardDraft`, `runPreflight`; `contractRendering(draftOrRun, table)`* → read-only DDL and supplemental SQL
- `execute(draftId, freezeConfirmation)` → run id
- `cancelRun(runId, finishing)`, `extendFreeze`, `declareFreezeBroken`, `continueAdmission`, `adoptCredential`
- `recordDisposition`, `runSampling`
- `discardRun`, `remigrate`, `copyAsDraft` → draft id
- `projectedAbandonmentList(draftId)`*, `abandonmentList(taskId)`, `abandonTask`, `retryAbandon`
- `downloadSupplementalSql(taskId)`*, `packageManifest(scope)`*, `exportPackage(scope)`, `recheckEnvironment`

Internal: recovery, pollers, condition loop, cleanup-retry loop.

## Consumes
- `workflow.api.command`, `workflow.api.query`; `gateway.execute`
- `connection`: `encrypt`, `decrypt`, `fingerprint` (not `wrap`/`erase`)
- `dialect`: `catalog.select`/`list`; `source.metadataPlan`/`normalizeMetadata`/`capabilityPlans`/`connectionSemantics`; `target.catalogReadPlan`/`normalizeCatalog`/`capabilityProbePlans`/`maintenancePlans`
- `preflight`: `plan`, `evaluate`; `condition`: `fold`; `diagnosis`: `diagnose`, `package`; `environment`: `check`, `sampleReadings`
- `contract`: `assemble`, `renderDdl`, `prove`, `projectedList`, `load`, `renderTaskSupplementalSql`
- `scheduling`: `plan`, `admit`, `predict`, `proposeSplit`
- `connector`: every Interface entry
- `validation`: `plan`, `evaluate`, `baselinePlan`, `driftPlan`, `samplingPlan`

## Obligations
**A. Boundaries**
1. No deep module calls another's side effect; Kafka facts, key fingerprints, and condition items reach them only through here (ADR-0036).
2. Every external mutation: commit intent, act idempotently, reread, commit the observation; no H2 transaction across an external call (ADR-0004 §Box, ADR-0012).
3. The README lists every consumed entry (ADR-0018 §Module context).

**B. Connections and drafts**
4. `saveConnection` encrypts before the command. `checkConnection`: decrypt, probe via `gateway`, record; a run needs the latest check passed (ADR-0036 row).
5. `runPreflight`: run `preflight` and `dialect` plans via `gateway`, `evaluate`, `assemble`; only `SUPPORTED` proceeds (ADR-0008 §Ownership, ADR-0003).
6. After preflight, `predict` on this source's history; a stale table shows no number (ADR-0038).

**C. Run driver**
7. `execute` is refused if the task has a nonterminal run or is abandoned (ADR-0006 §Target concurrency, ADR-0023).
8. Pre-admission `check` with the largest table, both key fingerprints, and capability results; freeze its conclusions; any failure → `ATTENTION_REQUIRED` (ADR-0027).
9. Reconfirm the task write freeze, nesting the run freeze (ADR-0024). Baseline via `baselinePlan`; a keyset column disagreeing with preflight fails its unit before any box (ADR-0037).
10. Freeze the snapshot: contracts, validation plan, routing, scheduling `plan` (ADR-0018, ADR-0002).
11. Lease the whole target scope atomically before any DDL; a conflict → no destructive action, `ATTENTION_REQUIRED` (ADR-0006 §Target concurrency).
12. Lease + advisory-lock guard is one step: lock, reread structure, generation, rows before create, truncate, drop, generation change; any difference aborts (ADR-0006 §Rerun).
13. Back up before the first destructive target action (ADR-0006 §Recovery).
14. Create the schema when authorized, recording the schema-created fact (#89 item 10); run `renderDdl` output, recording the OID in the generation (ADR-0023).
15. `PROVEN` → Sink allowed; else the unit `FAILED` and the table dropped per #86 (ADR-0026).
16. An empty table skips its box: zero-row completion (ADR-0004 phase 6).
17. Admit only if `admit` permits, the condition is not 受阻/无法判定, and no pause is open; the first admission under a new release closes the rollback window (ADR-0031, 0021, 0039, #89 item 5).
18. Per box: decrypt and `projectSecret`, create topics, start Sink, prove healthy, start Source; delete Source at read complete, Sink at write complete, then validate (ADR-0001, 0010, 0006).
19. 5 s status poll: `diagnose`, persist status and trace before deletion, check the marker; 10 s progress (ADR-0005, ADR-0004 §Timeline).
20. Attribute failures via the routing snapshot: unit `FAILED`, or members `BLOCKED_BY_BOX_FAILURE`; a box failure stops that box only, never retried (ADR-0005, ADR-0002).
21. 卡死 or the 24 h limit: delete the connectors, keep topics and target (ADR-0001).
22. Validate only inside a valid freeze, ≤ 2 validation connections per endpoint; on success delete the topic, then the subject once it is absent (TP §9.1, ADR-0004).
23. `runSampling` and `recordDisposition` never rewrite an item (TP §9.3–9.4).
24. Remaining time is `predict` on observed rates; log each 无法预估 switch; store each transfer sample and ceiling observation in `workflow` (ADR-0019, ADR-0038).

**D. Platform faults and admission pause**
25. Kafka, Connect, or SR unreachable: no admission, no 卡死 accrual; this module's timer fails affected boxes after 10 continuous min; databases stay run-scoped (ADR-0039, ADR-0021).
26. Connect restarted → fail its boxes at once; replant the marker (ADR-0032).
27. Second restart or two consecutive zero-output 卡死 boxes → record the pause; after continue one more strike pauses again; counters come from persisted facts, never reset (ADR-0039, ADR-0032).
28. `continueAdmission` closes the pause (ADR-0039).
28a. At ≥ 90% Kafka disk or < 10 GB free, stop producing Sources (ADR-0002; ADR-0021 §Gates); the aftermath is D-17.

**E. Condition and environment inputs**
29. Every 10 s: `kafkaFacts`, `sampleReadings`, `workflow` facts → `fold` → record changes and pauses (ADR-0021).
30. The startup `check` blocks migrations until a recheck passes; UI and export stay up (ADR-0027).

**F. Stopping and write freeze**
31. Cancel: stop admission and validation, confirm Source stopped, then Sink without draining; unfinished → `CANCELLED`; `CANCELLING` until facts converge (ADR-0006 §Cancellation).
32. 收尾取消 (finishing cancellation): no new box; transferring tables finish only inside a valid freeze; never chosen for the operator (ADR-0024).
33. Freeze expiry or declared break: stop admission, Source before Sink, fail unfinished units with the freeze reason; never refresh the baseline (ADR-0006).
34. Whenever remaining time ends after expiry, state the choice: extend, or those tables fail; extending past the task deadline extends the task freeze (ADR-0024).

**G. Cross-window**
35. `remigrate`: pre-scope the draft to failed, undetermined, never-run tables, entering stage 3 (ADR-0020, ADR-0024).
36. `driftPlan` before each later run, plus the closing check; a lapse without attestation means drifted (ADR-0024).
37. At execution confirmation, if the upper bound exceeds the freeze limit, return `proposeSplit`'s proposal as a non-blocking warning and snapshot it (ADR-0024).

**H. Recovery**
38. Startup: lease, pause admission, load nonterminal state, check the marker, reread facts, apply `RECONCILER` corrections, rebuild occupancy, resume (ADR-0004 §Box, ADR-0032).
39. Continue only on proven continuity, reloading contracts with `load`; never repeat DDL, `TRUNCATE`, baseline capture, or a user decision blindly; retry read-only observations within 10 min, backoff and jitter (ADR-0006 §Recovery, ADR-0008).
40. Interrupted cancellation keeps stopping; unreadable H2 → fail closed; an older release's nonterminal run → not automatically recoverable (ADR-0006, 0035, 0008).
41. `adoptCredential`: secret-only, audited, then prove continuity (ADR-0006).

**I. Cleanup, discard, abandonment**
42. Retry cleanup in the background without changing results; delete only owned resources, never orphans (ADR-0001, ADR-0035).
43. Remove a secret projection only after every referencing connector stopped; tombstone and destroy an obsolete credential version through `workflow` once nothing needs it (ADR-0006).
44. Discard only a stopped run: back up, delete in dependency order; truncate only an owned generation no connector can write, structure matching, no active holder; no `CASCADE`; append facts only (ADR-0006).
45. Abandonment (no nonterminal run): list under the lease, back up, clean run-local resources, guarded `DROP` per table; absent = success; refused → 部分废弃; retry without reconfirming unless the set changed; drop the schema only by matching OID and empty (ADR-0023, #89 item 10).

**J. Package and downloads**
46. Inputs (queries, a fresh `check`, the condition record, `release.json`, orphans) → `package`; `packageManifest` shows its manifest; export zips, streams, audits (ADR-0028).
47. `downloadSupplementalSql`: each table's latest successful run → `renderTaskSupplementalSql`; streamed, never on disk (ADR-0026).

## Verification
L1 on stubbed `api`s: `OrchestrationContractTest`, `ArchitectureTest` (A, B, E, G, J); `RunDriverContractTest`, `StoppingContractTest` (C, F); `AdmissionPauseContractTest` (D); `RecoveryContractTest` (H); `CleanupContractTest`, `AbandonmentContractTest` (I). L3: success, stuck, cancel, 收尾取消, expiry, crash, DBX restart, abandonment.

## Slices
1. `api` + contract-test skeleton, every use-case signature, README (3). Needs slice 1 of each consumed module.
2. B, `supportedPairs`, `contractRendering`. Needs `connection` 2; `gateway` 2, 3; `dialect` 2, 5; `preflight` 2; `contract` 2, 3; `scheduling` 4; `workflow` 4, 5.
3. 7–13; after 2. Needs `environment` 1; `validation` 4; `scheduling` 2; `connector` 2; `gateway` 4; `workflow` 6, 7, 8.
4. 14–16; after 3. Needs `contract` 3, 5; `dialect` 8.
5. 17–24; after 4. Needs `scheduling` 3, 6; `connector` 3–7; `diagnosis` 3; `validation` 3, 5; `workflow` 9; D-8, D-20.
6. D, E; after 5. Needs `condition` 2, 3; `environment` 3; D-17, D-18.
7. F; after 5.
8. H; after 6, 7. Needs `contract` 7.
9. 42–44; after 5.
10. 45, `projectedAbandonmentList`; after 9. Needs `contract` 6.
11. G; after 7. Needs `validation` 4; `scheduling` 4, 5; D-13.
12. J; after 6. Needs `diagnosis` 8; `contract` 4.

## Conflicts resolved
- ADR-0036 "run stays running", ADR-0004 precedence → ADR-0039.
- ADR-0006 continue after infra restart → ADR-0032 for Connect.
- ADR-0021 grace (Kafka, Connect) → ADR-0039 adds SR; databases per ADR-0006.
- ADR-0006 run-scoped freeze → ADR-0024 task freeze; run expiry stays (#85).
- ADR-0006 "never drops" → ADR-0023 abandonment, ADR-0026 (#86) proof-failure drop.
- ADR-0019 estimate at stage 2 → ADR-0038.
- ADR-0001 orphans vs ADR-0035 leftover cleanup → ownership records only.
- Unowned in ADR-0036 → here, the only side-effect sequencer (ADR-0018): the unreachable timer, the 90% / 10 GB stop, validation slots, credential destruction, the freeze-limit warning; no fixed pre-expiry lead time (ADR-0024).

## Open items
- **D-13** (T3): what marks the last run for the closing check. Blocks slice 11.
- **D-17** (T4): after the 90% / 10 GB stop, does a box wait, fail, or resume? Blocks slice 6.
- **D-18** (T4): does `web`'s status channel read a persisted outcome or a use case here? Blocks slice 6.
- **D-8** (T2), **D-20** (T5): own connections vs budgets; the 24 h limit's code. Block slice 5.
