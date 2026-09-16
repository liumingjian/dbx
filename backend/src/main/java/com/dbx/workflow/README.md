# workflow

The state machine, the single-writer command queue, and the repositories over the H2 aggregates; exposes `workflow.api.command` and `workflow.api.query`.

- Sub-spec: [docs/spec/workflow.md](/docs/spec/workflow.md)
- ADRs: [ADR-0004](/docs/adr/0004-relational-migration-state-machine.md), [ADR-0006](/docs/adr/0006-versioned-connections-recovery-and-reruns.md), [ADR-0012](/docs/adr/0012-spring-jdbc-no-jpa.md), [ADR-0018](/docs/adr/0018-backend-module-boundaries-and-agent-working-surface.md), [ADR-0022](/docs/adr/0022-verification-ladder-and-explicit-golden-updates.md), [ADR-0023](/docs/adr/0023-task-abandonment-drops-owned-target-tables.md), [ADR-0024](/docs/adr/0024-cross-window-batches-as-runs-under-a-task-write-freeze.md), [ADR-0035](/docs/adr/0035-offline-release-package-one-release-version-and-gated-in-place-upgrade.md), [ADR-0036](/docs/adr/0036-module-table-owns-every-v1-obligation.md), [ADR-0039](/docs/adr/0039-admission-paused-is-a-run-fact-that-requires-attention.md), [ADR-0040](/docs/adr/0040-validation-check-definitions-and-task-scoped-drift-checks.md)
