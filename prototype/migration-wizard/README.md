# DBX migration wizard prototype

> Throwaway UI prototype for GitHub issue #21. This is not production code and stores all mutations in memory only.

## Run

From the repository root:

```bash
python3 -m http.server 4173 --directory prototype/migration-wizard
```

Open <http://localhost:4173/?variant=A>. Use the floating switcher to move between `?variant=A`, `?variant=B`, and `?variant=C` without changing the route.

## What this prototype tests

- **A · 线性向导** — six explicit stages create reassurance and a visible sense of progress: connection, table scope, preflight/contract, execution confirmation, monitoring, validation report.
- **B · 异常工作台** — an overview rail puts blockers and operator decisions first, keeping ordinary tables out of the way until exceptions are resolved.
- **C · 三个决定** — a goal-oriented landing view reduces the journey to scope, risk, and safe-start decisions; details remain available on demand.

The variants are structurally different layouts, not color variations. All three use the same illustrative in-memory data and vocabulary from the domain model.

## Clickable coverage

- Select an existing connection or open the add-connection stub.
- Search the 200-table list, select/clear all, toggle individual tables, and sort by row count/data size (numeric sort, no regex).
- Review safe/risky/blocked preflight counts and the explicit 20 MiB block.
- Edit a structured mapping exception; DDL is deliberately read-only.
- Confirm a time-bounded write freeze and a concrete migration scope.
- Observe truthful run and table states, compact accessible progress bars, live-update toggle, timeline, and rerun draft action.
- Read PASS/FAIL/INCONCLUSIVE technical results separately from accepted-risk disposition.
- Use empty/loading/live examples via the selection controls, paused live-update toggle, and waiting/running table rows.

Keyboard arrows move between stages in variant A and are ignored while focus is in inputs/selects. Responsive CSS collapses the side rail on narrow screens.

## Assumptions

- One source MySQL database and one PostgreSQL target schema.
- Illustrative 200-table metadata; no network, authentication, SQL execution, or persistence.
- The prototype intentionally does not represent backend APIs, permissions, or real-time transport.
