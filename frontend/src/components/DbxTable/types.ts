import type { ReactNode } from 'react';

/**
 * The public interface of the `DbxTable` boundary (ADR-0015).
 *
 * Every type below is expressed in DBX's own terms. Nothing here mentions `Datagrid`,
 * `react-table`, `Column`, `Row`, `TableInstance`, `rowSize` or any other substrate
 * vocabulary, and nothing here is imported from `@carbon/ibm-products`. That is the point
 * of the module: ADR-0015 states that if `DbxTable` merely re-exports `Datagrid` props the
 * boundary provides no insulation and the decision is void. `DbxTable.test-d.ts` asserts
 * it at the type level; `eslint.config.js` stops anyone importing the substrate elsewhere.
 *
 * Consequently the substrate swap the ADR reserves (TanStack Table v8) is a change in
 * `DbxTable.tsx` alone. Everything a view can say about a table it says with these types.
 */

/** Rows are identified by a stable domain identifier, never by array position. */
export type DbxRowId = string;

/**
 * One column of a DBX table.
 *
 * A column renders a cell rather than naming a field, because DBX cells are usually a
 * rendered domain value — an identifier in `Identifier`, a conclusion in
 * `ConclusionIndicator`, a UTC timestamp — and not a raw string.
 */
export interface DbxTableColumn<TRow> {
  /** Stable key. Also the key the column-visibility preference is stored under. */
  readonly id: string;
  /** Header wording, taken from `src/messages`. */
  readonly header: string;
  readonly renderCell: (row: TRow) => ReactNode;
  /**
   * Plain-text form of the cell, for the accessible row label and for the exported and
   * copied forms a view builds from the same columns. **Not** for sorting: DBX tables are
   * rendered in a deterministic domain order (story 31 — the same database opens the same
   * way twice) and `DbxTable` offers no column sort, so nothing here is compared.
   */
  readonly textValue?: (row: TRow) => string;
  /** Initial width in pixels. Operators may resize from there. */
  readonly width?: number;
  /**
   * The identifying column: it stays visible while the table is scrolled sideways, and it
   * cannot be hidden. At most one column may declare it.
   */
  readonly identifying?: boolean;
}

/**
 * Row height. Exactly two values: ADR-0014 fixes 32px as the smallest usable row height
 * for Chinese body text and treats 24px as unavailable, so there is no third option and
 * no way to ask for one.
 */
export type DbxTableDensity = 'condensed' | 'comfortable';

/**
 * How many rows are mounted at once.
 *
 * `paged` is the default and is what makes 「当前页」 a real thing an operator can select.
 * `virtual` mounts a bounded window of rows regardless of how many rows exist, which is
 * what the 1200-row production selector needs; it has no pages, so a table using it can
 * only select 「符合当前筛选的全部」 and exclude individually.
 */
export type DbxTableRowWindow =
  | { readonly kind: 'paged'; readonly pageSize?: number; readonly pageSizes?: readonly number[] }
  | { readonly kind: 'virtual'; readonly visibleHeight?: number };

/** What the table says when it has nothing to show. */
export interface DbxTableEmptyCopy {
  readonly title: string;
  readonly body: string;
  readonly action?: ReactNode;
}

export interface DbxTableErrorCopy {
  readonly title: string;
  readonly body: string;
  readonly onRetry: () => void;
}

/**
 * The cross-page selection model (ADR-0015).
 *
 * Carbon publishes no cross-page selection semantics, so DBX owns the model: a selection
 * is either a set of rows an operator picked, or 「符合当前筛选的全部」 minus the rows they
 * explicitly excluded. Keeping the second case as a scope rather than as a materialised
 * set is what lets 「已选 N 张」 stay true when the filter moves, and what makes an
 * exclusion a reviewable decision rather than a missing checkbox.
 */
export type DbxSelectionScope =
  | { readonly kind: 'rows'; readonly selectedIds: readonly DbxRowId[] }
  | {
      readonly kind: 'allMatchingFilter';
      /**
       * The filter this scope was stated under.
       *
       * 「符合当前筛选的全部」 names a filter, so the scope is only meaningful while that
       * filter is the one in force. Without the binding, clearing a search silently turned
       * 「符合 order 的全部」 into 「整库的全部」 — and what it turned into is the set of
       * tables a production migration would write.
       *
       * Opaque to this module: the caller decides what identifies its filter, and the empty
       * string means 「没有筛选」.
       */
      readonly filterKey: string;
      readonly excludedIds: readonly DbxRowId[];
    };

export interface DbxSelectionModel {
  readonly scope: DbxSelectionScope;
  /** How many rows are selected across every page, given the rows matching the filter. */
  readonly selectedCount: number;
  readonly excludedIds: readonly DbxRowId[];
  readonly isSelected: (id: DbxRowId) => boolean;
  readonly toggleRow: (id: DbxRowId) => void;
  /** 「当前页全选」. Only meaningful for a paged table. */
  readonly selectPage: (idsOnPage: readonly DbxRowId[]) => void;
  readonly clearPage: (idsOnPage: readonly DbxRowId[]) => void;
  /** 「选中符合当前筛选的全部」 — the scope, not a snapshot of today's rows. */
  readonly selectAllMatchingFilter: () => void;
  /** Removes one row from an all-matching selection, as a recorded exception. */
  readonly exclude: (id: DbxRowId) => void;
  readonly clear: () => void;
  /** Every selection change is undoable, so a mis-click never costs the whole selection. */
  readonly undo: () => void;
  readonly canUndo: boolean;
}

/** A snapshot of the selection handed to a batch action when it is performed. */
export interface DbxSelectionSnapshot {
  readonly scope: DbxSelectionScope;
  readonly selectedCount: number;
  /** The matching rows that are selected right now. */
  readonly selectedIds: readonly DbxRowId[];
}

/**
 * How a batch action protects the operator from itself. Every batch action declares one:
 * an action that can neither be undone nor confirmed is not offered.
 */
export type DbxBatchActionSafety =
  | { readonly kind: 'undoable'; readonly undoLabel: string }
  | {
      readonly kind: 'confirmed';
      readonly title: string;
      readonly body: string;
      readonly confirmLabel: string;
    };

export interface DbxBatchAction {
  readonly id: string;
  readonly label: string;
  readonly safety: DbxBatchActionSafety;
  readonly perform: (selection: DbxSelectionSnapshot) => void;
  /** Called when the operator takes back an `undoable` action. */
  readonly undo?: (selection: DbxSelectionSnapshot) => void;
}

export interface DbxTableSelectionProps {
  readonly model: DbxSelectionModel;
  /**
   * The noun the selection count is measured in — 张 for source tables, 项 for tasks.
   * Supplied by the caller from `src/messages` so the boundary never invents copy.
   */
  readonly unitLabel?: string;
  readonly batchActions?: readonly DbxBatchAction[];
}

export interface DbxTableProps<TRow> {
  /** Accessible name of the table, in domain language. */
  readonly label: string;
  readonly columns: readonly DbxTableColumn<TRow>[];
  readonly rows: readonly TRow[];
  readonly rowId: (row: TRow) => DbxRowId;
  /** Row-level navigation, e.g. opening a migration run. */
  readonly onRowActivate?: (row: TRow) => void;
  /** True while the first read is in flight: the table shows skeleton rows, not a blank. */
  readonly loading?: boolean;
  readonly error?: DbxTableErrorCopy | null;
  readonly empty: DbxTableEmptyCopy;
  /**
   * Whether a filter is currently narrowing `rows`. An empty filtered table says
   * 「没有匹配项」; an empty unfiltered one says what `empty` says. Conflating the two is
   * what sends a DBA looking for records that were only filtered away.
   */
  readonly filterActive?: boolean;
  readonly rowWindow?: DbxTableRowWindow;
  readonly selection?: DbxTableSelectionProps;
  /** Density is remembered per key, across sessions, per ADR-0015. */
  readonly densityPreferenceKey?: string;
  /** Toolbar content the view owns — filters, a create action. */
  readonly toolbar?: ReactNode;
}
