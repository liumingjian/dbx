# Sub-spec conflicts resolved (provenance)

Where the corpus disagreed, the sub-spec writers recorded the losing text and the
winning pointer. This file is that record and nothing else: **every winning ruling
already appears as an obligation in its module's sub-spec**, so an implementing agent
never needs to read this to build a module. It is provenance, like
[`corpus-audit.md`](corpus-audit.md), and carries no character budget
(see [`subspec-brief.md`](subspec-brief.md) §Budget).

## condition

1. ADR-0021 §Values: "only the last two stop admission" (installation-wide) → ADR-0039 §A durable run fact: 受阻 caused by a pause stops that run only.
2. ADR-0021 §Execution platform unreachable covers "Kafka or Connect" → ADR-0039 §One ten-minute budget adds Schema Registry. ADR-0032 ends the grace once a Connect restart is seen.
3. ADR-0006 §Recovery of the same execution groups database unavailability with platform unavailability → ADR-0039 §One ten-minute budget: database unreachability never changes the condition.
4. ADR-0002's amendment note says the two-minute no-progress warning turns the condition to 需留意 → ADR-0021 §Table-scoped signals: it never enters the condition (the ADR the note cites).
5. ADR-0036 lists "the change record" in both the `condition` and `workflow` rows → `condition` computes the entry and `workflow` persists and bounds it (ADR-0036 §Dependencies and purity: `condition` is pure).
6. corpus-audit §5 proposed `record(change)` and `admissionAllowed()` → ADR-0036 Interface: `fold` only, and both are fields of its outcome.

7. Counter storage, unnamed in ADR-0036 → derived from run facts `workflow` persists (sole H2 writer; ADR-0039 "counters do not reset").
8. Owner of the ten-minute unreachable timer → `orchestration`, which alone fails boxes through `workflow.api.command` (ADR-0018 §Dependency direction; ADR-0039; `orchestration` obligation 25).
9. Disk-threshold denominator → the E6 reading: log-dir used ÷ total from `describeLogDirs` (ADR-0027 E6; ADR-0021 §Items and sources).
10. The change record's bound → `workflow`'s, at about 1,000 (ADR-0021 §History).

## connection

- ADR-0006: "the master key is supplied independently as a deployment secret" → ADR-0035 §Master key: a mounted file `secrets/master.key`, with only its fingerprint in H2. ADR-0006's own note defers to ADR-0035.
- ADR-0036 row `environment`: "E0 to E7 … and the master-key item" → #89 item 5: the master-key item is E8. That is `environment`'s concern, not this module's.
- corpus-audit.md §5 "proposed `connection`": connection CRUD and archive, the tombstone ledger, the master-key fingerprint, and the secret projection → ADR-0036 §Modules. CRUD, the ledger and the fingerprint belong to `workflow`; the ConfigProvider projection belongs to `connector`; `connection` "holds no H2 tables".
- ADR-0018 §Pure core: "side effects are confined to `gateway`, the Connect REST client, and the `workflow` repositories" → ADR-0036 §Dependencies and purity adds `connection` (it reads the master-key file) as an effectful shell.
- Who calls `encrypt` on a new credential version → `orchestration` before its `workflow.api.command` (it is the only command caller; ADR-0036 names only backups as `workflow`'s crypto use).
- How `connector` gets plaintext → from `orchestration`, like `gateway`; `connector` never references `connection` (ADR-0036 §Dependencies and purity).
- `decrypt` gated on tombstone reapply (ADR-0006) → the caller sequences it; `connection` holds no state to know (ADR-0036 "never persists").
- ADR-0006's ledger "tracks wrapped backup keys" vs obligation 19a's unrecoverability → the ledger tracks identity and fingerprint; the wrapped bytes live with the backup artifact, because an append-only file can never unsay what it holds (ADR-0006 as amended by #97).
- ADR-0036's four-name Interface column vs a `wrap` with no inverse → `unwrap` is a sixth entry point (#97).

## connector

- TP §7.1's prune/rename projection had no named renderer → `dialect.source.queryProjection` renders it; `deriveBox` places and fingerprints it (#92; obligation 6a).

- ADR-0018 `connector` row (Connect REST only) → ADR-0036 row adds AdminClient, SR, deletes, secrets, marker, derivation.
- ADR-0008 "the core alone produces" / audit's `contract.routingSnapshot` → ADR-0036: one derivation in `connector`.
- ADR-0003 large-record overrides on every connector → ADR-0031: large-record boxes only.
- ADR-0001 "usable monotonic incrementing column" → ADR-0037 keyset column; ADR-0033 `LIMIT N`/100 ms for all → ADR-0037 bulk: empty suffix, `2147483647`, 64 MiB cap.
- ADR-0021 ten-minute grace on any platform outage → ADR-0032: Connect restart fails boxes at once, grace for unreachable only; ADR-0039 adds SR.
- ADR-0001 stuck deletes connectors only → ADR-0032/0039: zero-output flag feeds the admission pause.
- ADR-0001 two-minute warning on the box → ADR-0021: shown on units only; `judge` only reports it.
- #79 "every poll" and `describeClassicGroups` fallback → ADR-0032 as amended by #90: 5 s status poll, no fallback.
- ADR-0010 "one tested mode" → #89 item 3: `BACKWARD`.
- ADR-0035 "startup removes leftover topics and subjects" vs ADR-0001 orphans → only ownership-recorded resources are deleted; orphans stay.
- ADR-0003 external-cluster probe, ADR-0006 shared-mount and ADR-0009 customer-deployment clauses → suspended in v1 (ADR-0009 status note).

- ADR-0036 lists only `connector.judge` as pure → the derivation is pure too: the effectful-shell list names only the data-plane clients and the ConfigProvider file (ADR-0036).

## contract

- ADR-0018's contract interface "`assemble`, `renderDdl`, `prove`" → ADR-0036 adds read-only rendering (ADR-0026) and the projected list (ADR-0023).
- ADR-0011's body puts the Sink settings, default whitelist, and identity rules under the contract → ADR-0011's status note (ADR-0018): they belong to the target dialect in `dialect`, and the contract reaches them only through the dialect interface.
- ADR-0011 leaves supplemental SQL timing and delivery to the product shell → ADR-0026 §Supplemental SQL decides both.
- ADR-0029's original 阻塞 row lists "structural-proof difference" → the #86 amendment and ADR-0026 §Structural proof: it is the unit's 迁移失败, never a finding.
- ADR-0008 ("the core alone produces the routing snapshot, configuration fingerprint, execution signature") and corpus-audit §5's proposed `contract.routingSnapshot` → ADR-0036: `connector` derives all of them in one place. `contract` only freezes the approved coordinates and routing decisions.
- ADR-0008 says the target dialect "reads and compares" the actual structure, while ADR-0011 puts proof in the contract → ADR-0036 gives `prove` and its verdict to `contract`. The dialect supplies target-specific normalization through its interface.
- ADR-0010's contract snapshot records the "observed schema identifier" → the contract is immutable at approval (ADR-0011): the observed id is a run fact in `workflow`; the converter configuration fingerprint is `connector`'s (ADR-0036).
- ADR-0029's unowned target-side rows and ADR-0006's zero-difference review had no owner → `assemble` emits both findings, reusing `prove` for the rerun comparison, and `review` computes the zero-difference review (#92; ADR-0029 §Who emits a finding; ADR-0036 §Amended by #92).

## diagnosis

- ADR-0005 and ADR-0022 golden 3 "20 families" → 21 families (#89 item 1; technical plan §9.5).
- ADR-0005 "reserves locale keys … falling back to Chinese" and ADR-0017/0020 two-locale layout → v1 zh-CN only, keys kept (#89 item 6).
- ADR-0018 row `diagnosis`: `diagnose` only → adds `package(inputs)` (ADR-0036).
- ADR-0005 package includes "raw REST responses" → included only after value scrubbing (ADR-0028 §No data values).
- ADR-0005 package "bounded" → 50 MB with a fixed truncation order (ADR-0028 §Bound).
- ADR-0021 unreachable platform = Kafka or Connect → Schema Registry joins (ADR-0039).
- corpus-audit §1 puts package assembly and export audit in `diagnosis` → `orchestration` assembles and audits; `diagnosis` computes content only (ADR-0036).

- Unassigned repeat-aggregation fingerprint (ADR-0005) → `diagnose` output; `workflow` aggregates occurrences (ADR-0036 `diagnosis` row).

## dialect

- ADR-0008 "core alone produces routing snapshot, fingerprint, signature" → `connector` derives them; the dialect only supplies requirements (ADR-0036 `connector` row).
- ADR-0008 "target dialect renders DDL … reads and compares" → `contract` owns `renderDdl` and `prove`, fed by `ddlPlan` and `normalizeCatalog` (ADR-0036 `contract` row; ADR-0018 §Dependency direction).
- ADR-0011 body puts the Sink settings under the contract → they belong to the target dialect (ADR-0011 status note; ADR-0018).
- ADR-0033's settings table read as covering all reads → keyset reads only; bulk reads take an empty suffix, `2147483647`, and the 64 MiB cap (ADR-0037).
- ADR-0001 "usable monotonic incrementing column" → keyset column, with no monotonicity requirement (ADR-0037).
- `MAX(LENGTH(column))` → ADR-0003's byte formula (TP §6.6).
- ADR-0018 purity list → ADR-0036's list (ADR-0036).
- ADR-0008 step 1 infrastructure probes → suspended in v1 (ADR-0008 v1 note; ADR-0003 status).
- Supplemental statements as a dialect entry (ADR-0026 "the same pure function … as the contract") → `target.supplementalStatements` renders; `contract.assemble` calls it from the same snapshot (ADR-0036 `contract` row).
- `batch.max.rows` and N (ADR-0033 "the core injects") → computed from M in `connector.deriveBox`, the one normalized-configuration derivation; the dialect declares only the requirement (ADR-0036 `connector` row; obligation 22).

- TP §7.1 leaves the prune/rename projection's renderer unnamed → `source.queryProjection` renders it, `connector.deriveBox` only places and fingerprints it, because the projection has two consumers and `preflight` reaches only `dialect.api` (#92; ADR-0036 §Amended by #92).

## environment

- ADR-0027 E5 "host memory at least 8 GB" → the memory-tier check against container-visible memory and the effective heaps (ADR-0031 §Observation; ADR-0035 amendment note).
- ADR-0031's ≥16 GiB tier against ADR-0035's "at least 18 GiB" → the threshold is 16 GiB of MemTotal; 18 GiB is guidance for the Docker Desktop allocation, which exceeds the MemTotal it exposes (#98).
- ADR-0036's row "E0 to E7, the memory tier, and the master-key item" read as a separate tier item → the tier item *is* E5 (ADR-0031 §Observation), and the master-key item is E8 (#89 item 5).
- ADR-0027's catalog E0–E7 has no key item, and ADR-0035 says "an environment check item" → E8 (#89 item 5; ADR-0027 status line).
- ADR-0027 "JVM heap unobservable" → heap is read over JMX (ADR-0031), while worker/JVM flags stay out of E4 (ADR-0032 §Consequences).
- ADR-0003's active capability check for external Kafka → suspended in v1 and replaced by this check (ADR-0003 status note; technical plan §4 step 2).
- corpus-audit §5, where `environment` consumes `connector` → `kafkaFacts` is passed in, and there is no such call (ADR-0036 §Dependencies and purity).
- ADR-0005, where the phase list lacks `ENVIRONMENT_CHECK` and there are "20" families → the phase is added (ADR-0027) and there are 21 families, with environment codes counted in none of them (#89 item 1).

- Undecided list-or-evaluate of capability-check results → lists them (ADR-0027 §Catalog "lists"); `orchestration`'s connection check concludes them.

## frontend

- #64 zh-CN + en-US, key parity, en overlap/smoke → zh-CN only, switch hidden, keys kept (#89 item 6).
- ADR-0017 two-locale layout; ADR-0020/#46 language in top bar and settings → v2; hidden (#89 item 6). Top bar gains 运行状况 (ADR-0021).
- ADR-0016 "declines to choose the mechanism" and `feature/30`'s `DEFAULT_POLL_INTERVAL_MS = 2_000` → 10 s plus refetch after a command (#89 item 7).
- #64 header set → #89 item 8's, drawer not a sixth tab (#89 item 8).
- #64 estimate "on the draft" from scope → first at stage 3 after preflight, stale-aware; 可信 means per-source history (ADR-0038).
- ADR-0020 re-migration "failed and undetermined" → adds never-run, 因运行取消而停止, and drifted tables (ADR-0024).
- ADR-0036 "the run stays running" on paused admission → 需要人工处理 with 「继续迁移」 (ADR-0039).
- ADR-0029 阻塞 including a structural-proof difference → structural proof is only the unit's 迁移失败 (#86; ADR-0026).
- Endpoints for new surfaces → slice 3 declares them in `src/api/*.ts` (ADR-0016 §Contract).
- The condition banner as the one global "something is wrong" surface vs an H2 that cannot answer → 恢复态 replaces the shell instead of banners inside it (#97).
- Mid-run 写冻结 extension and declared break → `orchestration`'s `extendFreeze`, `declareFreezeBroken`.

## gateway

1. ADR-0008 §Plans said the gateway "persists evidence and audit facts". ADR-0018 §Dependency direction wins, and ADR-0036 keeps it: deep modules return results, and only `orchestration` calls `workflow.api.command`. `gateway` returns the evidence and `orchestration` persists it.
2. `corpus-audit.md` §5 proposed the entry points `probeIdentity` and `withAdvisoryLock`. ADR-0036's Interface column wins: its only entry point is "Executes typed SQL plans". The probe and the lock are expressed as plans and modes of `execute`.
3. ADR-0008 had the "core database gateway" bind credential versions, which could mean it resolves them itself. ADR-0036 §Dependencies and purity wins: `gateway` receives decrypted material from `orchestration`.

## orchestration

- ADR-0036 "run stays running", ADR-0004 precedence → ADR-0039.
- ADR-0006 continue after infra restart → ADR-0032 for Connect.
- ADR-0021 grace (Kafka, Connect) → ADR-0039 adds SR; databases per ADR-0006.
- ADR-0006 run-scoped freeze → ADR-0024 task freeze; run expiry stays (#85).
- ADR-0024's unowned closing trigger → the operator's `closeTask` (ADR-0040).
- ADR-0006 "never drops" → ADR-0023 abandonment, ADR-0026 (#86) proof-failure drop.
- ADR-0019 estimate at stage 2 → ADR-0038.
- ADR-0001 orphans vs ADR-0035 leftover cleanup → ownership records only.
- Unowned in ADR-0036 → here, the only side-effect sequencer (ADR-0018): the unreachable timer, the 90% / 10 GB stop, validation slots, credential destruction, the freeze-limit warning; no fixed pre-expiry lead time (ADR-0024).

## preflight

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

## release

- ADR-0003's 4 GiB Connect heap on ≥ 8 GiB → tier heaps 3 / 6 GiB, thresholds read as container-visible memory (ADR-0031, ADR-0035 notes).
- ADR-0027's E5 "host memory ≥ 8 GB" → the memory-tier item, read against `docker info` MemTotal (ADR-0031, ADR-0035).
- ADR-0031's ≥16 GiB tier against ADR-0035's "at least 18 GiB" → thresholds are 16 and 8 GiB of MemTotal; 18 GiB (and 10 GiB at the floor) is Docker Desktop allocation guidance (#98).
- Below the 8 GiB floor, install-refusal against install-plus-E5 → I1 refuses; E5 covers only memory that falls below its tier after install (#98).
- ADR-0035's unnumbered master-key item → E8, never waivable (#89 item 5).
- ADR-0035/0036's "rollback-window fact and key fingerprint" → one installation record that also holds the release version (#89 item 5).
- ADR-0010's "one tested mode" → `BACKWARD` (#89 item 3).
- ADR-0027's "release allowlisted checksum" → `{version, SHA-256}` entries, 8.x only (#89 item 4).
- The corpus audit's proposed `release` home for OOM flags, UTC and key placement → a non-Java sub-spec, backend halves in `web`, `workflow`, `environment` (ADR-0036).

- ADR-0035's rollback order (restore, then repoint, then start) → repoint and start first, and the previous release restores itself; shell must never hold the master key (#97).
- Old-release nonterminal run at startup, ownerless here → `orchestration` recovery marks it not automatically recoverable (`orchestration` obligation 40; ADR-0035; ADR-0008).
- Upgrade and rollback proof before a second release → the first release runs only `build` and `freshInstall` (ADR-0035 §Verification).

## scheduling

- ADR-0018 module row "`plan`, `admit`" → ADR-0036 adds `predict(run-history rates)` and the estimate.
- ADR-0002 ¶4 "exact frozen row count" behind planned transfer bytes → the 预估行数 at preflight; the exact 源基线 count is a separate `plan` input (#92; ADR-0002 as amended).
- ADR-0002 "five gates" → ADR-0031: a sixth, cumulative memory gate.
- ADR-0002 "computed maximum concurrency" (undefined) → #89 item 2's formula, with its terms fixed by #93.
- ADR-0002 "no first-run estimate" → ADR-0019: an estimate before every run. ADR-0002's gate governs only the handover to the remaining-time estimate.
- ADR-0019 "bytes ÷ a throughput range" → ADR-0034: plan replay on shape rates under a shared ceiling.
- ADR-0019 and TP §10 "once the migration scope is settled" → ADR-0038: only after preflight completes, with no stage-2 estimate.
- ADR-0019 and ADR-0034 "this deployment's past runs" → ADR-0038: per source data source, with the ceiling per target.
- ADR-0034 "history refits parameter by parameter" (no minimum) → ADR-0038's minimums and residual-ratio ends.
- corpus-audit §5 `estimate` → ADR-0036's `predict`; the split stays here as `proposeSplit` (ADR-0024 §Planning); the freeze-limit warning is `orchestration`'s.
- Unowned history storage → `workflow` persists it (sole H2 writer, ADR-0036 §Considered options).
- corpus-audit §5 signature derivation here → `connector` derives it (ADR-0036).

## validation

- ADR-0018 row "Data validation; `plan`, `evaluate`" and pure "evaluation half" only → ADR-0036 row adds baseline, drift, and sampling plans, and makes the plan and evaluation halves pure (ADR-0036 §Modules, §Dependencies and purity).
- ADR-0018 `preflight` row "row-count probe" and corpus-audit's proposed baseline owner → baseline is `validation`'s; row count is not a preflight probe (ADR-0036 §Modules, §Considered options).
- ADR-0024 §Drift checks and ADR-0006 "primary key terminal value/drift" → keyset column terminal values (ADR-0037; CONTEXT.md Source baseline; TP §4 step 8).
- ADR-0029 prototype `VerifyResult` (`error`, no `INCONCLUSIVE`) → five item states, `error` becomes `INCONCLUSIVE` (ADR-0029 §Validation vocabulary).
- CONTEXT.md "Value checksum sample" → **Value sample**; 抽样值比对 is TP §9.3's manual deterministic sample and computes no checksum (ADR-0040).
- TP §9.2 non-nullness "for a primary key" only → every source `NOT NULL` contract column (ADR-0040).
- TP §9.3 sampling applied to every keyed table → a large record table is covered by 大记录值完整性 instead (ADR-0040).
- Validation plan "frozen at contract approval" (corpus-audit item 54) → frozen into the run snapshot at run creation (ADR-0018 §Modules as amended).

## web

- ADR-0016 "the default real implementation polls" with no cadence, and the `feature/30`/`impl/*` branches' `DEFAULT_POLL_INTERVAL_MS = 2_000` → 10 s plus a refetch after a command (#89 item 7; ADR-0016 amendment note).
- ADR-0018 web row, with only HTTP adapters → adds the status channel and the nonterminal-run query (ADR-0036 web row).
- ADR-0036 "Admission paused … the run stays running" → the run projects `ATTENTION_REQUIRED` while the pause is open (ADR-0039).
- ADR-0005 and ADR-0017/0020 two-locale text → zh-CN only in v1 (#89 item 6).
- Brief: "the contract lives in `frontend/` on main" → `origin/main` has no `frontend/src/contract/`. The contract exists on `origin/feature/30-frontend-module` and `origin/impl/*`, and ADR-0016 §On the prototype host ports it in `frontend` slice 3.

- The TS contract is missing on `main` → `frontend` slice 3 ports it and declares every path and payload, including the surfaces it predated (status channel, installation record, nonterminal runs, 继续迁移, abandonment, downloads, export) (ADR-0016 §Contract).
- `web` calling pure `contract` entries directly → only through `orchestration` use cases (ADR-0018 draws only `web → orchestration` and `workflow.api.query`).

## workflow

- ADR-0004 precedence and ADR-0036 "the run stays running" → open pause projects `ATTENTION_REQUIRED` ahead of active work (ADR-0039).
- ADR-0004's "no task state of its own" → task lifecycle above the projection (ADR-0023 §Task lifecycle).
- ADR-0006's freeze ending with its run → nested inside the task write freeze (ADR-0024).
- ADR-0024 leaving drift results unhomed → a task-scoped `drift_check`, closed by an operator command (ADR-0040).
- ADR-0004's "later retention policy" and progress compaction → no evidence retention in v1, thinning deferred (#89 item 9).
- ADR-0006's "retention removes backup artifacts on schedule" → keep the last 48 hourly backups (#89 item 9).
- ADR-0036 ledger as `workflow` aggregate vs outside H2 (ADR-0006) → a `workflow`-owned file in `secrets/` (ADR-0035 §Master key).
- ADR-0006's ledger "tracks wrapped backup keys" vs erasure being final → the ledger tracks identity and fingerprint; the bytes live with the artifact (#97).
- ADR-0035's rollback restoring H2 before starting the previous release → the script starts the previous release, which restores itself (#97).
- Change record in both `condition` and `workflow` rows → `condition` derives (pure), `workflow` persists (ADR-0036 §Dependencies).
- Counters, startup-check conclusions, estimate history (unowned) → here, the sole H2 writer (ADR-0036 §Considered options).
