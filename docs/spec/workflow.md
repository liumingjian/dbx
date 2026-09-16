# workflow — v1 sub-spec

Owns DBX's H2 control-plane state: state machine, single-writer queue, Flyway schema, backups, and every aggregate in ADR-0036's `workflow` row.

**Read first**: ADR-0036, ADR-0018, ADR-0004, ADR-0012, ADR-0039, ADR-0024, ADR-0040, ADR-0023, ADR-0006, ADR-0035; #89 items 5, 9, 10. CONTEXT.md terms: migration task (迁移任务), migration draft (迁移草稿), migration run (迁移运行), table migration unit (表迁移单元), run snapshot (运行快照), migration task status (迁移任务状态), task conclusion (整库结论), drift check (漂移检查), task closing (收口), credential version (凭据版本), admission paused (准入已暂停), task write freeze (整库冻结承诺), target generation (目标代际), abandonment list (废弃清单), rollback window (回退窗口).

## Interface (`workflow.api`)

- `workflow.api.command`: typed commands → commit receipt or typed rejection (stale revision, duplicate key, conflict). Effectful, via the single-writer queue. Only `orchestration` may call it (ADR-0018 §Enforcement).
- `workflow.api.query`: immutable aggregate snapshots and projections. Read-only. Callers: `web`, `orchestration` (ADR-0018 §Dependency direction).

## Consumes

- `connection.wrap` / `connection.unwrap`: wrap and unwrap each backup's data-encryption key (ADR-0036 §Dependencies; ADR-0006 §Connection as amended by #97).
- `connection.erase`: the erasure instruction for wrapped backup keys when a credential version is destroyed; `workflow` applies it (ADR-0006 §Connection).

## Obligations

**Persistence and queue**
1. Spring JDBC with explicit SQL: no JPA/ORM, no repository interface layer (ADR-0012; ADR-0018 §Abstractions).
2. One bounded single-threaded FIFO queue is the only H2 writer (ADR-0004 §Timeline; ADR-0012 §Persistence).
3. A non-coalescible command commits transition, revision check (exactly one row), projection/counters, and timeline event in one transaction; the caller waits (ADR-0004 §Table state; ADR-0012).
4. User commands carry idempotency keys, and duplicates are rejected by constraint (ADR-0004 §Table state).
5. Progress coalesces per table and box: ≤1 flush per 10 s, immediate at read/write complete, warning, failure, cancellation, shutdown. An older snapshot never overwrites completion evidence, terminal state, or counters (ADR-0004 §Timeline).
6. No H2 transaction or connection stays open across an external call; repositories make no external calls (ADR-0012 §Persistence).

**Schema and startup**
7. File H2 is the sole metadata DB; a second writer fails startup via the single-instance lease (ADR-0004 §Embedded).
8. Flyway migrations are versioned, forward-only, and checksum-verified, and finish before the queue, workers, or reconciler start. A nonempty migration set is preceded by a backup. Any failure fails closed: no create-if-missing, no repair (ADR-0004 §Embedded; ADR-0012 §Schema).

**State machine**
9. Unit phase and outcome are orthogonal enums with exactly the allowed transitions. Each transition records actor `USER|PLATFORM|RECONCILER`, from/to, reason code, correlation ID, and optional occurrence (ADR-0004 §Table state).
10. Box checkpoints `WAITING…TERMINAL` are kept apart from timestamped observations. Terminal diagnoses are `WRITE_COMPLETE|FAILED|STUCK|CANCELLED` (ADR-0004 §Box).
11. Run status is a projection rebuildable from units, boxes, and run facts. Precedence: cancellation in progress, open admission pause, active work, required attention, terminal severity (ADR-0004 §Derived; ADR-0039).
12. Task status is `ACTIVE→ABANDONING→ABANDONED|PARTIALLY_ABANDONED`, sits above the run projection, and never rewrites run facts. An abandoned task accepts no new run (ADR-0023 §Task lifecycle).
13. At most one nonterminal run per task (ADR-0006 §Target concurrency).
14. Task conclusion is a never-edited projection: each table's latest unit result overlaid with the closing drift check (`INCONCLUSIVE / SOURCE_CHANGED`), green only if every table is 迁移完成 and no drift was found (ADR-0024 §Task conclusion). It reads the latest `CLOSING` 漂移检查 (drift check), so a run after one leaves the conclusion without a check until the next closing (ADR-0040).

**Aggregates (Flyway tables; semantics at pointer)**
15. `installation`: exactly one row — release version, master-key fingerprint, rollback-window state (`opened_at`, `closing_run_id`, `closed_at`), and the three General preferences (时区, 危险操作二次确认, 每页条数), each defaulted so the row is complete from the Flyway baseline. The window opens at upgrade end and closes at first admission under the new release; preferences are read and written independently of it (#89 item 5; ADR-0035 §Rollback window; ADR-0016 §State split as amended by #99).
16. `database_connection`: structured endpoint, TLS mode and material (ciphertext), username, semantic/operational settings; archived, never deleted, while referenced (ADR-0006 §Connection).
17. `credential_version`: immutable AES-256-GCM ciphertext. Destruction nulls ciphertext and keeps version, actor, usage, destruction metadata (ADR-0006 §Connection).
18. `connection_check`: connection, credential version, time, result, observed identity facts (ADR-0006 §Capability; CONTEXT connection check).
19. Tombstone ledger: an append-only file in `secrets/`, outside H2 and its backups, tracking each wrapped backup key by **identity and fingerprint only**, never by its bytes (ADR-0006 §Connection as amended by #97; ADR-0035 §Master key).
20. `migration_draft`: server-side wizard selections per stage, stage-gate state, per-table preflight staleness, and the estimate with its preflight time. Never audit evidence (ADR-0020 §URLs; ADR-0038 §When; CONTEXT).
21. `migration_task`: endpoints, schema, conversion switches, user mapping rules, latest approved contracts (ADR-0004 §Aggregate); task status, the confirmed abandonment list with per-table drop evidence and refusals (ADR-0023); the write-once schema-created fact (creating run ID, time, `pg_namespace` OID) (#89 item 10).
22. `task_write_freeze` plus append-only confirmations: accountable operator, deadline, extension, gap attestation. A run freeze cannot pass the task deadline unless the task freeze is extended in the same command (ADR-0024 §Task write freeze).
23. `split_snapshot`: the proposed split exactly as shown to the change board (ADR-0024 §Planning).
23a. `drift_check`: task-scoped and immutable, not a run record — occasion (`BEFORE_RUN` or `CLOSING`), time, one item per already-migrated table in the validation-item shape; never a `PASS`. `orchestration` supplies the evaluated facts (ADR-0040; ADR-0024 §Drift checks).
23b. Task closing (收口) is an operator command, never automatic: it runs the closing drift check, writes the `CLOSING` `drift_check`, and only then may the task write freeze be released. DBX surfaces that closing is available once every in-scope table holds a terminal result. Closing is not terminal: a later run is allowed and requires a new closing check (ADR-0040).
24. `migration_run`: run number, release version, and an immutable snapshot of scope, mapping rules (AUTO origin), accepted findings, connection/credential versions, redacted endpoint and instance identity, pre-admission check conclusions, write freeze, baseline, contracts, validation plan, routing, scheduling plan, supplemental SQL (ADR-0004 §Aggregate; ADR-0006; ADR-0027 §Evidence; ADR-0026 §Timing). Credential bindings are append-only (ADR-0006 §Connection). It also holds the cancellation request (with finishing flag) and its converged fact (ADR-0004 §Derived; ADR-0024), and the admission-pause record (reason, trigger, time, `continued_at`), surviving restart (ADR-0039).
25. `table_migration_unit`, `box`, `validation_execution` and `validation_item`, `error_occurrence` and `diagnosis`, `timeline_event`, `stage_attempt`, `cleanup_request`: fields per ADR-0004 §Aggregate and ADR-0005 §Occurrence. A unit-scoped `error_occurrence` also records the unit's 阶段 as at the occurrence, read as a fact rather than derived from the diagnosis classification phase (ADR-0030, #96). A zero-row unit has no box (ADR-0004 §Table state).
26. `target_lease`: key is actual server identity + database + case-sensitive schema.table, atomic over the whole scope, never expiring by time (ADR-0006 §Target concurrency). An abandoning task keeps holding it (ADR-0023).
27. `target_generation`: target key, owning run, `pg_class` OID (ADR-0006 §Cancellation; ADR-0023).
28. `condition_change`: time, from, to, reason, bounded to about the last 1,000 (ADR-0021 §History). There is no latest-condition row; the outcome is an observation of now, held in memory by `orchestration` (ADR-0021 §Consequences; #95). Installation-package export audit (time, scope, checksum) sits beside it; run-package exports go on the run timeline (ADR-0028 §Audit).
29. No evidence retention and no task deletion (#89 item 9; ADR-0023 §Records).
29a. `startup_check`: only the latest startup environment-check conclusions (ADR-0027 §Evidence).
29b. Estimate history: finished-transfer samples per source and ceiling observations per target, each with its estimate-basis fingerprint (ADR-0038).
29c. Each Connect restart is a run timeline event and a `STUCK` box records zero output, so counters rebuild after restart (ADR-0032; ADR-0039).
29d. Observed schema id per unit: a run fact (ADR-0010).

**Backups**
30. Consistent, checksummed backups to `backups/`: hourly, before a nonempty Flyway set, a run's first destructive target action, discard, and abandonment. Keep the last 48 hourly (ADR-0006 §Recovery; ADR-0023; #89 item 9).
31. Each backup has a distinct data-encryption key, wrapped with `connection.wrap`. The wrapped form is stored **with its backup artifact** in `backups/`; only its identity and fingerprint reach the ledger (obligation 19).
31a. Destroying a credential version erases the backup keys of every retained backup that could expose it: `workflow` takes the instruction from `connection.erase`, shreds the wrapped bytes beside the artifact, and appends the erasure tombstone. Erasing an already-erased key succeeds (ADR-0006 §Connection; `connection` obligations 19–19b).
31b. A **pre-upgrade backup** is a labelled backup taken on request through the local API while the release is still running. `dbx upgrade` takes one before stopping the stack and rollback restores exactly that one; it is exempt from the 48-hourly retention until the rollback window closes (ADR-0035 §In-place upgrade, as amended by #97).

**Restore and recovery mode**
31c. Restore is `workflow`'s, never a script's: prove tombstone-ledger continuity, `connection.unwrap` the backup's key, restore, erase any backup keys revoked after that backup, reapply credential destruction — in that order, before the queue, workers, or reconciler start. Any step failing stops in recovery mode and mutates no external resource (ADR-0006 §Recovery).
31d. An unreachable or corrupt H2 is an explicit startup conclusion, not a crash: it puts DBX in **recovery mode**, which `web` renders (ADR-0006 §Recovery; `web` obligation for the restore page).
31e. A **restore request** file left by `dbx rollback` is consumed at startup, before Flyway: verify the named backup against the checksum in the request, restore per 31c, delete the request on success. On failure, rename the request aside and never retry. A request whose checksum does not match is refused (ADR-0035 §Failed upgrade).
31f. The rollback-window state in `installation` (obligation 15) is mirrored to a script-readable file outside `secrets/`, written **closed before** the first run is admitted under the new release, so the file may only ever be more conservative than H2 (ADR-0035 §Failed upgrade).

**Queries for other modules**
32. Any-nonterminal-run: every nonterminal run with its release version, for `web`'s upgrade query and the startup refusal (ADR-0035 §In-place upgrade; ADR-0036 `web` row).
33. Startup load: every nonterminal run, unit, box, and unfinished cleanup, with revisions (ADR-0004 §Box).

## Verification

- Obligations 1, 9–14 and the ArchUnit rules: L1 `check`, through `WorkflowContractTest`, `UnitTransitionTest`, `RunStatusProjectionTest` (ADR-0039 cases), `TaskStatusTest`, `TaskConclusionProjectionTest`.
- Obligations 2–8 and 15–33: L2 `seamTest` (workflow filter), through `CommandQueueSeamTest` (ordering, coalescing, stale rejection, rollback), `FlywaySeamTest`, `WorkflowRepositorySeamTest` (one case per aggregate), `BackupSeamTest` (triggers, 48 kept, wrap/unwrap/erase, the pre-upgrade retention exemption, erased bytes gone while the ledger keeps the tombstone), `RestoreSeamTest` (31c–31f: continuity proof before unwrap, request consumed once and deleted, checksum mismatch refused, failed request renamed and not retried, window file closed before the first admission).
- The upgrade step of L4 `packageTest` asserts that H2 migrated, the key fingerprint matches, and history survived (ADR-0035 §Verification).

## Slices

1. **api skeleton**: command/query types, ADR-0004/0023/0039 enums, `WorkflowContractTest`, README, ArchUnit command-caller rule. Blocks everything.
2. **Pure projections and transition table**: obligations 9–14 at L1. Needs 1.
3. **H2 foundation**: Flyway baseline, single-instance lease, command queue, `installation` row. Obligations 2–4, 6–8, 15 (row). Needs 1.
4. **Connections**: `database_connection`, `credential_version`, `connection_check`, tombstone ledger. Obligations 16–19. Needs 3.
5. **Drafts and tasks**: draft, task, task write freeze, split snapshot, schema-created fact, abandonment records. Obligations 20–23b. Needs 2, 3.
6. **Runs and execution records**: run snapshot, units, boxes, timeline, stage attempts, occurrences, validation, admission pause. Obligations 24, 25, 29b–29d, 33. Needs 2, 5.
7. **Target safety, cleanup, condition**: leases, generations, cleanup requests, `condition_change`, export audit, progress coalescing. Obligations 5, 26–28, 29a. Needs 6.
8. **Backups and restore**: obligations 30–31f. Needs 3; blocked by `connection` slice 3.
9. **Release facts**: rollback-window open/close, any-nonterminal-run query, release version on the run. Obligations 15 (window), 32. Needs 6.

## Conflicts resolved

See [`conflicts.md`](conflicts.md#workflow) — provenance only; every winning ruling is already an obligation above.

## Implementer decides

- How backups that "could expose" a credential version are recorded: may over-approximate, never miss one (ADR-0006).
- The exact `condition_change` bound, about 1,000 (ADR-0021 §History).

## Open items

None. D-18 is settled in #95 (the latest outcome is not persisted); D-22 and D-23 in #97.
