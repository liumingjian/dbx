# Verification ladder and explicit golden-file updates

DBX is built by agents, so "the change is safe" must be something an agent can prove by running a command, not a judgement it makes. Verification is a three-rung ladder with a time budget per rung, a fixed rule for which rung each actor runs, and golden files whose updates are explicit and auditable. The failure this guards against most is an agent regenerating every snapshot and silently flattening the regression net — the engineering-side twin of the "quiet failure" ADR-0011 exists to prevent.

## The ladder

| Rung | Gradle task | Contains | Needs Docker | Budget | Technical plan |
|---|---|---|---|---|---|
| L1 | `check` | Compilation, pure unit tests, ArchUnit rules (ADR-0018), `*ContractTest`, golden files, README-limit test | No | ≤ 2 min | §15.1 |
| L2 | `seamTest` | Testcontainers seam tests against real MySQL 8, PostgreSQL 15, and Kafka; filterable per module | Yes | ≤ 10 min | §15.2 |
| L3 | `e2eTest` | Full real stack: the five pinned services of the #9 test bed | Yes | Unbudgeted | §15.3 |

A rung over budget is a defect: move the slow test down a rung or make it faster. L1 stays fast because ADR-0018 keeps most of the platform pure.

## Where it runs

All execution — compilation included — runs on the mac through the `rexec` skill. The development server has too little memory to run anything, so no rung is "local"; the rungs differ by duration and by whether they need Docker, not by location.

## Who runs which rung

- **Agent session**: L1 green before the session ends. When the change touches a side-effect shell — `gateway`, the Connect REST client in `connector`, or the `workflow` repositories — or their `api`, also that module's L2 green.
- **CI, every PR**: L1 and L2.
- **CI, merge into `main`**: L3 green when the change touches the backend or `dialect`. `main` is the last automatic gate, so the gate does not depend on any agent's discipline.

## Golden files

Golden files pin the outputs where a wrong answer looks like success:

1. The type-mapping matrix (#11): one golden case per matrix row and source-metadata variant.
2. Table write contract → DDL rendering (ADR-0011).
3. The 20 error-translation rules (ADR-0005): fixed stack-trace samples → diagnosis code and explanation.
4. Box plans (ADR-0002): fixed table-size inputs → box assignment. Inputs are fixed examples, never random, so a change in tie-breaking shows up as a real diff rather than noise.

## Explicit updates

- Tests read golden files and never write them.
- `-Pgolden.update=<name>` rewrites exactly one named golden set. The task rejects wildcards and "all".
- A commit that changes a golden file carries a `Golden-Update: <name> — <why this output is expected to change>` trailer for each set it changes. L1 goes red on a commit range where a golden file changed without its trailer.

The trailer makes every regeneration auditable and forces the agent to state why the output *should* change. A convention an agent can quietly break is no boundary (ADR-0018).

## Frontend

This ADR fixes the rung names and their meaning; #53 hangs the frontend gates on them: typecheck, lint, and Vitest on L1; `pnpm smoke` and `pnpm overlap` on L2. Journey acceptance (§15.4) belongs to #53.

## Where the rules live

This ADR holds the rules and rationale. The root `CLAUDE.md` carries the three executable instructions an agent needs every session. The commands themselves live in Gradle tasks and are not restated in docs.

## Considered options

- **Only two ends (pure tests and Testcontainers)**: the two differ in cost by two orders of magnitude, and with no rung between them agents either skip seam tests or run them for every change.
- **Golden updates by convention only**: an agent under pressure regenerates everything and the diff looks like routine churn.
- **A general `-Pgolden.update` with no name**: one flag silently rewrites every set.
- **L3 only before release**: failures would surface weeks after the change that caused them, with many candidate commits.
- **Rules only in `CLAUDE.md`**: the rationale would load on every turn; rules only in an ADR would not be read at the moment they apply.
