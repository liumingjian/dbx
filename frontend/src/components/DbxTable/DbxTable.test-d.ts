import { describe, expectTypeOf, it } from 'vitest';
import type { ReactNode } from 'react';
import type { SourceTableSummary } from '@/contract';
import type {
  DbxBatchAction,
  DbxSelectionModel,
  DbxSelectionScope,
  DbxTableColumn,
  DbxTableDensity,
  DbxTableProps,
  DbxTableRowWindow,
} from './types';

/**
 * The type-level half of seam 2 (lead decision D4).
 *
 * ADR-0015 says that if `DbxTable` merely re-exports `Datagrid` props then the boundary
 * insulates nothing and the decision is void. That is a claim about types, and no runtime
 * assertion can make it — so it is made here, and `vitest.config.ts` turns typechecking on
 * so `npm test` actually runs this file.
 *
 * The claim is expressed positively: every part of the public interface is *constructible
 * from DBX's own types alone*. A prop that had picked up a `Column<T>`, a `Row<T>`, a
 * `TableInstance` or a `rowSize` string could not satisfy the objects written below, and
 * this file would stop compiling. The companion guard is the lint rule, which stops any
 * module outside `DbxTable.tsx` importing the substrate in the first place.
 */
describe('the DbxTable interface is expressible in DBX domain types alone', () => {
  it('builds a complete set of props without naming the substrate', () => {
    const column: DbxTableColumn<SourceTableSummary> = {
      id: 'name',
      header: '源表',
      identifying: true,
      width: 240,
      textValue: (table) => table.name,
      renderCell: (table) => table.name,
    };

    const scope: DbxSelectionScope = { kind: 'allMatchingFilter', excludedIds: ['orders'] };

    const model: DbxSelectionModel = {
      scope,
      selectedCount: 1,
      excludedIds: [],
      isSelected: () => true,
      toggleRow: () => {},
      selectPage: () => {},
      clearPage: () => {},
      selectAllMatchingFilter: () => {},
      exclude: () => {},
      clear: () => {},
      undo: () => {},
      canUndo: false,
    };

    const action: DbxBatchAction = {
      id: 'exclude',
      label: '排除所选',
      safety: { kind: 'undoable', undoLabel: '撤销' },
      perform: (selection) => {
        expectTypeOf(selection.selectedIds).toEqualTypeOf<readonly string[]>();
      },
    };

    const props: DbxTableProps<SourceTableSummary> = {
      label: '源表',
      columns: [column],
      rows: [],
      rowId: (table) => table.name,
      loading: false,
      error: null,
      empty: { title: '空', body: '空' },
      filterActive: false,
      rowWindow: { kind: 'virtual', visibleHeight: 480 },
      selection: { model, unitLabel: '张', batchActions: [action] },
      densityPreferenceKey: 'source-tables',
    };

    expectTypeOf(props).toMatchTypeOf<DbxTableProps<SourceTableSummary>>();
  });

  it('offers exactly two densities, so 24px cannot be asked for', () => {
    expectTypeOf<DbxTableDensity>().toEqualTypeOf<'condensed' | 'comfortable'>();
  });

  it('describes the row window in DBX terms rather than in substrate flags', () => {
    expectTypeOf<DbxTableRowWindow['kind']>().toEqualTypeOf<'paged' | 'virtual'>();
  });

  it('renders cells as React nodes rather than exposing a cell type', () => {
    expectTypeOf<DbxTableColumn<SourceTableSummary>['renderCell']>().toEqualTypeOf<
      (row: SourceTableSummary) => ReactNode
    >();
  });
});
