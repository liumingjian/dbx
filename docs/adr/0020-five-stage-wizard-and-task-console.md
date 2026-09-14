# A five-stage wizard that ends at execution, and a task console organised around migration runs

Supersedes ADR-0007. Decided in [#48](https://github.com/liumingjian/dbx/issues/48), following #45's adoption of `dbx-prototype` as the frontend baseline and #46's scope-cut checklist.

ADR-0007 made run monitoring and the validation report the last two stages of one six-stage wizard. The prototype observes a run on the task detail page instead. DBX v1 follows the prototype, with the boundary drawn at the migration run: the **migration wizard** (迁移向导) edits a migration draft, and everything after 执行 (execute) happens on the task detail page, looking at an immutable migration run. ADR-0007's three safety floors survive unchanged: write freeze is confirmed before execution, monitoring and validation centre on the table migration unit, and `PASS` / `FAIL` / `INCONCLUSIVE` stay distinct.

## The wizard

Five stages, named only from `CONTEXT.md`:

1. **连接与数据库 (connections and databases)**: pick verified connections, source database, target schema. No inline credentials.
2. **迁移范围 (migration scope)**: tree select with search and explicit exclusion; no regex.
3. **预检 (preflight)**: run preflight and read the preflight findings. `UNSUPPORTED` and `INCONCLUSIVE` block; they are corrected, pruned, or excluded, never acknowledged.
4. **映射规则 (mapping rules)**: the default path is zero exceptions and straight on. Table and column exceptions are edited in a per-table drawer that also shows the table's read-only DDL (the rendering of its table write contract, ADR-0011) and its preflight findings. The editor's shape belongs to #53.
5. **执行确认 (execution confirmation)**: the whole scope, contracts, and unresolved findings, plus a required write-freeze block (responsible party, time limit, explicit confirmation). 执行 stays disabled until it is filled in. Clicking 执行 opens a light second confirmation that restates the responsible party and time limit, then creates the migration run.

Preflight comes before mapping, as in the prototype. A mapping rule added in stage four makes the preflight of the tables it touches stale. Stage five refuses 执行 until those tables pass preflight again.

**URLs and gates.** A migration draft is persisted server-side. Each stage has its own URL, `/tasks/new/:draftId/:stage`. Opening a stage whose preceding gates are unmet redirects to the first unmet stage. The prototype's `Steps` bar only renders position. The per-table drawer has its own URL beneath stage four.

## The task console

The shell is a flat sidebar with **迁移任务** (migration tasks, the landing page), **数据源** (data source management), and **系统设置** (system settings), plus a top bar with language and theme. v1 has no authentication, user menu, dashboard, or global search. With one database pair, a single user, and a handful of concurrent tasks, most dashboard cards would have no real data source. The prototype stays the B-stage reference for them (#46).

- **Task list**: migration tasks and migration drafts share one list. A draft row carries a 迁移草稿 tag and no run status. Opening it enters the wizard at its first unmet stage. Counters for running tasks and tasks needing attention sit above the list.
- **Task detail**: `/tasks/:taskId/runs/:runNo/:tab`. `/tasks/:taskId` redirects to the latest run's default tab. A run switcher reads 「第 N 次迁移运行」, so a URL quoted into a ticket always names one immutable run. There are five tabs:
  1. **运行监控 (run monitoring)**, the default: phase strip, remaining-time estimate and throughput (shape per ADR-0019 / #59), and the table migration unit list. It merges the prototype's overview, table progress, and monitor tabs.
  2. **校验报告 (validation report)**: technical conclusions, preflight exclusions, and validation dispositions shown separately. Enum per #52.
  3. **预检发现 (preflight findings)**: the run's frozen preflight results. Replaces 评估报告.
  4. **日志 (logs)**: translated errors with collapsible originals (ADR-0005).
  5. **运行快照 (run snapshot)**: the run's frozen scope, mapping rules, connection and credential versions, and write-freeze record. Replaces 参数.
- **Table evidence**: a drawer, not a page. Clicking a table in 运行监控 or 校验报告 opens the same drawer at `…/runs/:runNo/:tab/tables/:unitId`, which restores on refresh. It holds the unit's timeline, failures in plain language with the raw stack collapsed, validation detail, and the manual sampling action. The full-page `TableMigrationUnitPage` is removed.
- **重新迁移 (re-migration)** creates a migration draft pre-scoped to the failed and undetermined tables, with the earlier run's connections, scope, and mapping rules carried over. It enters the wizard at stage three. The new run must pass preflight again and receive a fresh write-freeze confirmation. No shortcut bypasses the wizard.

## Considered options

- **Keep six stages, pointing stages five and six at task detail tabs** was rejected. The wizard would claim two stages it does not own, and the draft and run boundary would blur.
- **Mapping before preflight** was rejected in favour of the prototype's order. Only the few tables whose rules change must be re-checked, so this costs less than moving the whole journey.
- **Write freeze in a standalone modal** was rejected. Habit clicks modals away, and the confirmation belongs on the screen where the operator reviews the whole scope.
- **A full-page per-table workspace** (ADR-0007's three panes) was rejected. Exceptions are rare, and a deep-linkable drawer carries the same evidence without a separate journey.
- **A v1 dashboard** was rejected. Its cards lacked real data in v1, and its remaining cards were license and notification panels.
- **The prototype's single-page wizard held in component state** was rejected. A refresh lost the work, and gates could not be enforced by URL.

## Consequences

`CONTEXT.md` drops 逐表配置与预检 as a stage name, moves 运行监控 and 校验报告 from wizard stages to views of a migration run, and gains 运行快照. The frontend on `main` (six stage routes, `/runs/:runId`, the table page) is reshaped to these routes during the prototype port. Nothing about the backend changes: drafts, runs, and table migration units keep their existing definitions.
