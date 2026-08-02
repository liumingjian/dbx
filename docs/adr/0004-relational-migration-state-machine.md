# Relational migration state with orthogonal phase and outcome

DBX v1 stores ordinary relational current state as the recovery authority and an append-only timeline as audit evidence. It does not use event sourcing. A state transition and its timeline event commit in the same database transaction; the event explains a transition but is never replayed to reconstruct current state. High-frequency progress observations are the only asynchronous writes and may be coalesced.

## Aggregate ownership

The persistent model has the following ownership boundaries:

- A **data source connection** is a reusable endpoint definition. Credential representation and minimum privileges remain the responsibility of the connection-management decision; other records refer to it rather than copying connection strings.
- A **migration task** is the user-approved intent for one source MySQL database and one target PostgreSQL schema. It owns task-level conversion switches, selected tables, structured mapping rules, and the latest approved table write contracts. User mapping rules are retained across reruns; run-generated automatic rules are snapshotted with their origin.
- A **migration run** is an immutable execution attempt. It snapshots the approved task settings, source/target endpoint references, write-freeze confirmation, source baselines, table write contracts, scheduling assumptions, and scheduling plan. A rerun creates a new run and new table migration units; it never mutates or resumes an old run.
- A **table migration unit** belongs to exactly one run and owns one source-table-to-target-table result: source metadata including the original MySQL type, preflight result, baseline, contract snapshot, progress, validation conclusion, terminal outcome, and error references.
- A **box** belongs to one run and owns only disposable execution state: immutable membership and order, execution signature, connector names, normalized configuration fingerprints, lifecycle observations, resource occupancy, and connector stop facts. It never owns a table's durable result. A table migration unit may reference one box in a run; an exactly empty table references none.
- **Resource cleanup requests** are first-class records because their ownership and retry clocks differ. Connector cleanup belongs to a box; each data-topic cleanup and its corresponding per-table Schema Registry subject cleanup belong to the table migration unit. Subject deletion is requested only after that table's topic deletion is confirmed. Failed, blocked, cancelled, and accepted-risk tables preserve both topics and subjects until the separately confirmed discard policy says otherwise. If a serializer configuration creates a genuinely shared subject, it may be deleted only after no retained topic still references it.
- **Validation executions and items** belong to a table migration unit. Every execution is retained. Item states are `PASS`, `FAIL`, `INCONCLUSIVE`, `NOT_APPLICABLE`, or `NOT_RUN`; accepting risk records a separate disposition and never overwrites these technical results. An enabled check that the validation specification requires but DBX cannot prove is `INCONCLUSIVE`, never `NOT_APPLICABLE` or `NOT_RUN`. `SUCCEEDED` requires every enabled applicable check to be `PASS`; only a rule in the versioned validation plan may classify a check as disabled or not applicable.
- **Timeline events** and **error occurrences** are append-only facts. An event may refer to a run, table migration unit, box, validation execution, and error occurrence, but one table's timeline is readable without treating box history as that table's business state.

Mapping rules, source metadata, preflight findings, table write contracts, validation items, and errors use normalized columns for fields queried by the product. Bounded versioned JSON may retain rule-specific details and observed values; JSON is not used as an untyped substitute for identities, states, timestamps, reason codes, or relationships. Large diagnostic artifacts are referenced by checksum and location rather than copied repeatedly into timeline events.

## Table migration state

A table migration unit stores an orthogonal `phase` and terminal `outcome`, rather than one enum combining every stage, failure kind, and validation result.

Nonterminal phases are:

1. `DISCOVERED` — source metadata has been captured.
2. `PREFLIGHTING` — exact capability checks are running.
3. `AWAITING_APPROVAL` — preflight and generated contract are visible for review. The persisted preflight conclusion is `SUPPORTED`, `UNSUPPORTED`, or `INCONCLUSIVE`; only `SUPPORTED` may be approved. The other conclusions may return to `PREFLIGHTING` after correction or become `SKIPPED` when the table is excluded.
4. `READY` — the approved contract and run baseline are fixed.
5. `CREATING_TARGET` — DDL execution and structural introspection are in progress.
6. `WAITING_FOR_BOX` — the target contract is proven and the immutable box is awaiting admission. An exactly empty table bypasses this phase: after target creation and structural proof, DBX synchronously records unit-owned zero-row read-complete and write-complete evidence with no box reference, then proceeds to validation.
7. `TRANSFERRING` — its box has started. Read-complete and write-complete are persisted timestamps and evidence on the box and unit, not new user-controlled states.
8. `VALIDATING` — the table is write-complete and an immutable validation execution is active.
9. `TERMINAL` — no automatic execution may continue for this unit.

A terminal unit has exactly one outcome:

- `SUCCEEDED`: all enabled v1 validations passed.
- `FAILED`: preparation, DDL, transfer, or validation failed; a stable reason code identifies the failing stage.
- `BLOCKED_BY_BOX_FAILURE`: another table or shared connector failed and this unit did not itself produce the initiating failure.
- `SKIPPED`: the user excluded the table before execution.
- `CANCELLED`: a confirmed run cancellation stopped this unit.
- `COMPLETED_WITH_ACCEPTED_RISK`: the write completed, a validation remained `FAIL` or `INCONCLUSIVE`, and an operator recorded a required reason. The original validation result remains unchanged.

`STUCK` is deliberately not a table outcome. It is a terminal box diagnosis. When evidence attributes a box failure to one table, that table becomes `FAILED` with the stable box reason and other unfinished members become `BLOCKED_BY_BOX_FAILURE`. When a shared connector failure, disk pressure, or lack of progress cannot be truthfully attributed to one table, DBX names no initiating table: every unfinished member becomes `BLOCKED_BY_BOX_FAILURE` and refers to the box-level error occurrence. DBX never invents per-table blame merely to populate an outcome.

Allowed transitions form a forward workflow, with only explicit retry loops:

- `PREFLIGHTING ↔ AWAITING_APPROVAL` for a corrected preflight;
- `VALIDATING → VALIDATING` by creating a new validation execution after a failed or inconclusive attempt;
- any nonterminal phase to `TERMINAL/CANCELLED` after confirmed cancellation;
- operational failure from any active phase to `TERMINAL/FAILED`;
- an affected shared-box member to `TERMINAL/BLOCKED_BY_BOX_FAILURE`;
- `TERMINAL/FAILED` or `TERMINAL/COMPLETED_WITH_ACCEPTED_RISK` never transitions back. A data rerun creates a new migration run and new units instead.

Every transition uses optimistic revision checking and records actor (`USER`, `PLATFORM`, or `RECONCILER`), timestamp, from/to phase and outcome, stable reason code, correlation ID, and optional error occurrence in the same transaction. User commands carry idempotency keys. This prevents duplicate button presses, restart reconciliation, or ambiguous REST retries from advancing a unit twice.

## Box execution state and restart recovery

A box stores a desired lifecycle checkpoint separately from observed external facts. Its checkpoints are `WAITING`, `STARTING_SINK`, `STARTING_SOURCE`, `TRANSFERRING`, `STOPPING_SOURCE`, `DRAINING_SINK`, `STOPPING_SINK`, and `TERMINAL`; terminal diagnoses are `WRITE_COMPLETE`, `FAILED`, `STUCK`, or `CANCELLED`. Connector existence, task health, configuration fingerprints, topic offsets, consumer lag, target counts, last-progress time, and cleanup state are observations with their own timestamps.

These checkpoints exist because each external mutation has an ambiguous failure window. DBX persists intent before the REST call, performs the idempotent operation, observes the external fact, and then advances the checkpoint. It never marks a connector started or stopped from the HTTP response alone.

At startup DBX:

1. acquires a single-instance database lease and pauses new box admission;
2. loads every nonterminal run, unit, box, unfinished cleanup request, and their revisions;
3. re-reads Connect, Kafka, and PostgreSQL facts and verifies connector fingerprints;
4. applies the lifecycle decision's rules for missing, matching, conflicting, and orphaned resources;
5. records every correction as a `RECONCILER` transition/event, rebuilds actual resource occupancy, and only then resumes the immutable waiting order.

A restart never rewinds a phase merely because its last progress sample was lost. Critical completion evidence and state transitions are synchronous. Unknown `dbx-` resources remain operational findings, not silently adopted or deleted.

## Derived migration-run and task status

Migration-run status is a deterministic projection, not a separately editable source of truth:

- `PREPARING` when no unit has reached execution and at least one remains before `WAITING_FOR_BOX`;
- `RUNNING` when any unit or box is actively creating, waiting, transferring, draining, or validating;
- `ATTENTION_REQUIRED` when execution cannot advance because review/preflight, insufficient disk, or another explicit operator action is required while nonterminal units remain;
- `CANCELLING` after cancellation is requested until all boxes are confirmed stopped;
- `COMPLETED_WITH_FAILURES` when all units are terminal and any outcome is `FAILED` or `BLOCKED_BY_BOX_FAILURE`, even if cancellation stopped the remaining units;
- `CANCELLED` when cancellation has converged, every selected unit is terminal, and no unit failed or was box-blocked before or during cancellation;
- `COMPLETED_WITH_ACCEPTED_RISK` when all units are terminal, none failed or are box-blocked, and at least one accepted-risk outcome exists;
- `COMPLETED` when all included units succeeded; excluded `SKIPPED` units are reported separately and do not turn the run green or red.

Precedence is cancellation in progress, active work, required attention, then terminal severity. A cancellation request and its converged fact remain separately visible even when a prior or concurrent failure makes the terminal projection `COMPLETED_WITH_FAILURES`. Stored counters and status may be materialized for query speed, but each update uses the same transaction as the unit transition and can be rebuilt from units. A migration task presents the projection of its latest run plus immutable history; it has no second independently mutable execution state.

## Timeline, timing, and progress writes

The timeline uses a stable event type and reason code, event time, recorded time, actor, correlation ID, entity references, stage attempt, optional error occurrence, and a versioned bounded detail payload. Events cover durable boundaries such as metadata captured, preflight started/completed, contract generated/approved, target creation and structural check, box admission, connector lifecycle, read/write completion, validation, cancellation, terminal outcome, and cleanup. Poll samples are not timeline events.

Each retryable stage creates a stage-attempt record with start/end instants, status, and error reference. Durations are derived from those instants. The product reports active duration per attempt and wall-clock run duration separately; it never sums overlapping table durations into a task duration. Waiting, transfer, validation, and cleanup durations remain distinct, and cleanup after success does not change migration completion time.

Offset, lag, row-count, and throughput observations first update an in-memory per-entity latest-value slot. A single-threaded FIFO metadata command queue with bounded capacity is the only writer to H2. It flushes a coalesced progress snapshot at most once per 10-second polling interval per table and box, plus immediately at read/write completion, warnings, failures, cancellation, and shutdown. State transitions and their events enter the same queue as non-coalescible transactional commands; callers wait for their commit before performing the next external side effect. Every progress command carries the entity revision and observation time, so an older coalesced snapshot cannot overwrite newer completion evidence, terminal state, materialized status, or counters. No metadata transaction remains open across a Connect REST call, Kafka operation, or source/target database query. A short rolling progress history may be retained for diagnosis; old samples are compacted into time buckets while terminal evidence is retained.

## Embedded database and schema evolution

DBX v1 supports file-backed H2 as its sole metadata database. Supporting SQLite simultaneously was rejected because its locking, DDL, JDBC behavior, and migration semantics would create a second compatibility surface without user value. A single platform instance owns the file and uses a bounded Spring JDBC pool; deployment startup must fail rather than allow two writers to share it.

Flyway owns schema evolution. Every released migration is versioned, forward-only, checksum-verified, and applied before workers or reconciliation start. Startup takes a backup before a nonempty migration set, fails closed on migration error, and never falls back to `create-if-missing` or automatic destructive repair. Downgrade is restore-from-backup, not reverse migration.

## Considered options

- Event sourcing was rejected because restart recovery ultimately depends on external Connect, Kafka, and PostgreSQL facts; replaying internal events cannot prove external state and adds a second failure model.
- One giant table-state enum was rejected because it creates a Cartesian product of phase, technical validation result, accepted-risk disposition, box diagnosis, and cleanup status.
- Making task state mutable was rejected because it can contradict its table migration units; a projection preserves the table as the independently observable unit.
- Persisting every 10-second poll as an audit event was rejected because it creates write contention and an unreadable timeline. Losing one progress sample may reduce graph fidelity but must never change recovery correctness.
- Supporting both H2 and SQLite was rejected for v1 because “embedded” is a deployment property, not a requirement for interchangeable SQL dialects.

## Consequences

The model has more explicit records than a single job table, but each record has one owner and one recovery purpose. Recovery can converge ambiguous external operations without replaying history; the UI can render a truthful table timeline without exposing connector grouping as a business lifecycle. Progress graphs are intentionally best-effort between durable boundaries. Changing mappings or rerunning data always creates a new immutable run snapshot, so storage grows with audit history and requires later retention policy rather than in-place mutation.
