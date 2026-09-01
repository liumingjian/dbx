# Table substrate and the DbxTable boundary

The table is not one control among many in DBX; it is the product surface. Selecting a production schema, reviewing per-table conclusions, and watching per-table progress all happen in a table that must hold a sticky first column, horizontal phase columns, per-row status, and roughly a thousand rows. DBX therefore uses the `Datagrid` from `@carbon/ibm-products`, but no business code imports it directly: every table is rendered through a DBX-owned `DbxTable` boundary.

## Why a boundary is required

`@carbon/react` provides row-height sizes, batch actions, expandable rows, pagination, and `stickyHeader`, but it has no sticky columns, no virtualisation, no column resizing, and no column visibility control. Those exist only in `@carbon/ibm-products`, whose `Datagrid` supplies `useStickyColumn`, `useInfiniteScroll` with `virtualHeight`, `useResizeTable`, `useCustomizeColumns`, `useColumnOrder`, and `useNestedRows`.

That capability arrives with a liability: `Datagrid` is built on `react-table@^7.8.0`. TanStack Table v7 is no longer maintained. Binding the product's central surface directly to a frozen upstream is not acceptable, and rebuilding sticky columns, virtualisation, batch bars, and skeleton rows from scratch is not affordable in the first delivery. The boundary buys both: current capability now, a contained migration later.

## Exit path

When any of the following becomes true, `DbxTable` switches internally to TanStack Table v8 over Carbon styles while its external interface stays fixed:

- `react-table` v7 conflicts with the React version DBX must run;
- virtualised scrolling fails to meet the performance bar at production scale;
- a required table capability is absent from `Datagrid`.

## What Carbon does not decide

Carbon publishes no row-count limit or performance threshold for data tables, and no cross-page selection semantics — `useSelectAllToggle` only toggles the current page. DBX therefore owns the model, wording, and undo path for "N selected across pages", "select all matching the current filter", and per-item exclusions. Carbon supplies row-height sizes but no density switcher, so the switcher and its persisted preference are DBX's as well.

## Consequences

Every table feature is specified against `DbxTable` rather than against Carbon or `@carbon/ibm-products`, which makes the substrate swap a single-module change but adds a layer that must not become a thin pass-through: if `DbxTable` merely re-exports `Datagrid` props, the boundary provides no insulation and the decision is void.

## Rejected alternatives

- **Use `Datagrid` directly** — fastest initially, permanently binds the product's core surface to an unmaintained upstream.
- **Build on TanStack Table v8 from the start** — maximum freedom, but requires rebuilding sticky columns, virtualisation, batch action bars, and skeleton rows before the first screen ships.
