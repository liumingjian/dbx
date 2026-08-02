## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues for `liumingjian/dbx`. See `docs/agents/issue-tracker.md`.

### Domain docs

This is a single-context repo: read root `CONTEXT.md` and root `docs/adr/` when present. See `docs/agents/domain.md`.

### Wayfinder branches

Use one cumulative `wayfinder/<initiative>` branch per initiative. Create each `decision/<ticket-id>-<slug>` branch from the latest Wayfinder baseline and merge it back before starting dependent tickets. Process tickets sequentially by default; use separate worktrees only for independent tickets running concurrently. Never branch one decision ticket from another.

A Wayfinder decision ticket that changes domain language or records an architectural decision is not complete merely because its decision branch contains the documentation. Before posting the resolution, closing the ticket, or updating the map, merge the resolved `CONTEXT.md` and `docs/adr/` changes into `main` and push `main`; verify the files are visible from `origin/main`. Treat this as part of the ticket's completion criteria so every later ticket can rely on the canonical domain vocabulary and ADR history.
