# Spring JDBC metadata persistence without JPA

DBX v1 persists its control-plane state in one file-backed H2 database using Spring JDBC and Flyway. It does not use JPA, Hibernate, another ORM, or a repository abstraction that hides SQL and transaction boundaries. This implementation choice completes the relational state decision in ADR-0004: current rows are the recovery authority, timeline rows are audit evidence, and external facts are reconciled explicitly.

## Persistence boundary

Repositories expose narrow operations over the aggregates defined by ADR-0004: connections and credential versions, tasks, immutable runs, table migration units, boxes, contracts, validation executions, error occurrences, timeline events, cleanup requests, leases, and target generations. SQL is explicit and schema-shaped. Domain/application services own state transitions and external-operation protocols; repository methods do not call Connect, Kafka, Schema Registry, source databases, or target databases.

The single-threaded bounded FIFO metadata command queue is the only writer. Non-coalescible commands perform one business transition, its optimistic revision check, projections/counters, and its timeline event in one Spring-managed transaction. High-frequency progress observations may be coalesced by entity and observation time, but cannot overwrite completion evidence, a newer revision, terminal state, or audit facts.

Callers wait for a transition commit before performing its next external side effect. Intent is persisted before an ambiguous external mutation; observed completion is persisted after facts are reread. No H2 transaction or JDBC connection remains open across a Connect REST request, Kafka or Schema Registry operation, filesystem secret projection, or source/target SQL query.

A bounded Spring JDBC pool serves the command writer and read-only queries without implying concurrent writers. Every update that changes lifecycle state includes the expected revision and verifies exactly one affected row. Idempotency keys and database constraints reject duplicate user commands and ownership conflicts. Queries load explicit aggregate snapshots; no lazy relationship traversal may issue hidden reads after the transaction ends.

## Schema and startup

Flyway is the sole schema-evolution mechanism. Migrations are versioned, forward-only, checksum-verified, and complete before the command queue, workers, scheduler, or reconciler starts. Startup takes the backups required by ADR-0006 before a nonempty migration set and fails closed on checksum, migration, file ownership, or single-instance lease failure. Runtime code never performs `create-if-missing`, `update schema`, automatic destructive repair, or ORM-generated DDL.

H2 compatibility is the only metadata-database target in v1. SQL may use H2 capabilities deliberately rather than maintaining an unrequested cross-database subset. Downgrade means restoring a supported backup and compatible binary, not applying reverse schema migrations.

## Why not JPA

The control plane is dominated by explicit state transitions, compare-and-set revisions, immutable snapshots, append-only evidence, projections, queue ordering, and fact-reconciled external side effects. JPA entity lifecycle, cascading persistence, dirty checking, lazy loading, implicit flush, generated schema, and provider-specific locking would obscure precisely the read/write set and commit boundary that recovery correctness depends on.

Spring JDBC keeps SQL, affected-row expectations, locking, constraint failures, and transaction scope visible. Mapping boilerplate is accepted in exchange for deterministic persistence that an implementation agent and an operator can trace directly from state-machine command to tables and audit event.

## Consequences

The platform has one persistence model and one migration mechanism. Recovery code can distinguish committed intent, external observations, and committed state without reconstructing hidden ORM behavior. Tests can assert exact rows, revisions, constraints, and transaction rollback.

The cost is hand-written SQL, row mapping, and deliberate migration work. Repositories must remain small and aggregate-oriented so this explicitness does not turn into duplicated query logic.

## Rejected alternatives

- **JPA or Hibernate** was rejected because implicit flush, cascading, lazy reads, and provider locking hide recovery-critical boundaries.
- **Event sourcing** was rejected by ADR-0004 because replay cannot prove external Kafka, Connect, or PostgreSQL state.
- **Supporting H2 and SQLite together** was rejected because it creates a second locking, DDL, JDBC, and migration compatibility surface.
- **ORM-generated or runtime-updated schema** was rejected because released control-plane state requires reviewed, backed-up, checksum-verified Flyway migrations.
- **Concurrent direct writers bypassing the command queue** were rejected because command ordering, projections, and external intent protocols would no longer be deterministic.
