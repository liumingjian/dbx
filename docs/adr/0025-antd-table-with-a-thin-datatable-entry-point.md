---
status: accepted (supersedes ADR-0015)
---

# High-density tables on antd `Table` behind a thin `DataTable` entry point

Supersedes ADR-0015. Decided in [#49](https://github.com/liumingjian/dbx/issues/49), following #45's adoption of `dbx-prototype` and ADR-0017's Ant Design 5 substrate.

`@carbon/ibm-products` `Datagrid` has left the dependency tree, so ADR-0015's reason for `DbxTable` — isolating an untrusted, deprecated substrate — is gone. #44 asked whether upstream deprecation should become a fourth exit condition; that question no longer arises. The needs ADR-0015 served remain, and this ADR assigns each one.

## Capability handover

| ADR-0015 / #28 need | v1 answer |
|---|---|
| Sticky first column (表名) | antd `fixed: 'left'` |
| Virtualisation at ~1200 rows | antd `virtual` with numeric `scroll.y` |
| Per-row status | ordinary cell render; `conclusion.ts` stays the only indicator mapping (ADR-0017) |
| Column resizing | **Cut from v1.** antd 5 has none built in (#45's claim otherwise was wrong); the prototype has none |
| Column visibility | **Cut from v1.** Not built in, absent from the prototype, and a preference DBAs should not have to manage |
| Density switcher | **Cut from v1.** One density everywhere: ADR-0017's 32px floor. No persisted per-table preference |

Only surfaces that can reach 1200 rows use the scale mode (virtual + fixed first column): the table migration unit list, verification, and preflight findings in task detail. Every other table stays paginated.

## The `DataTable` entry point

The prototype's `src/components/DataTable.tsx` is the single table entry point. `no-restricted-imports` forbids importing `Table` from `antd` anywhere else; `ParamsTab.tsx`, the one current bypass, is brought under it. `DataTable` owns exactly three things: Chinese density, ellipsis with tooltip, and the scale mode. It passes antd props through; it is deliberately thin, with no type-level contract test. Its reason to exist is one landing place for those three concerns, not insulation.

## Selection model

The pure selection model from `feature/30-frontend-module` (`components/DbxTable/selection.ts` with its tests) is ported, independent of any substrate. It holds a selection as a scope — explicit rows, or all tables matching a filter minus exclusions — with undo, and freezes an all-matching scope into explicit rows when the filter changes, so a selection never silently widens. Exclusions are the frontend form of 操作员显式排除 (operator excluded) in `CONTEXT.md`. It backs the wizard's migration-scope tree and the table set for a rerun. antd's `rowSelection` is page-scoped and never the source of truth. Regex bulk select stays cut (#46 Q10); substring search plus "select all matching the current filter" replaces it.

## Scale gate

Evidence is a mechanism assertion, never milliseconds:

- a 1200-table fixture in the stateful mock;
- L1 (Vitest): at 1200 rows fewer than 100 rows mount, and at 2400 the mounted count is unchanged;
- L2 (Playwright, beside `pnpm smoke` and `pnpm overlap`): after wheel scrolling the mounted count stays bounded, the 表名 column stays in the viewport after horizontal scroll, and select-all-matching then one exclusion yields 1199.

All run on the mac through `rexec` (ADR-0022). The first green run is the evidence that antd `virtual`, `fixed: 'left'`, and `rowSelection` coexist; if it cannot go green, that opens a research ticket rather than a threshold change. `scripts/overlap.mjs` remains a layout audit, not a performance gate.

## Rejected alternatives

- **Delete the boundary and use antd `Table` directly** — density, ellipsis, and the scale mode would be re-decided on every page.
- **Rebuild `DbxTable` at its former thickness** — insulation against a trusted substrate costs a type-level contract layer for nothing.
- **Millisecond or frame-rate thresholds** — noisy on CI machines, and a noisy gate gets loosened until it guards nothing.
- **Plain id-array selection with antd page select-all** — loses scope semantics and reintroduces the "71 tables became 1200" widening.
