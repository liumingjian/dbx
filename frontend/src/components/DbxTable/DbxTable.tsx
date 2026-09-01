import { useCallback, useId, useMemo, useRef, useState, type ComponentProps } from 'react';
import {
  Datagrid,
  useDatagrid,
  useInfiniteScroll,
  useOnRowClick,
  useStickyColumn,
} from '@carbon/ibm-products';
import { Button, Checkbox, ContentSwitcher, Modal, Pagination, Switch } from '@carbon/react';
import { messages } from '@/messages';
import { EmptyState, ErrorState } from '@/components/ViewState';
import { useDbxTableDensity } from './density';
import { selectionSnapshot } from './selection';
import type { DbxBatchAction, DbxSelectionSnapshot, DbxTableColumn, DbxTableProps } from './types';

/**
 * The DBX table boundary (ADR-0015).
 *
 * This is the only module in DBX that knows the table substrate exists. It renders
 * `Datagrid` from `@carbon/ibm-products`, which is where sticky columns, virtualised
 * scrolling, column resizing and skeleton rows come from — none of which `@carbon/react`
 * has — while `react-table@7` is the unmaintained upstream that capability arrives with.
 *
 * What the boundary does **not** do is forward props. Carbon decides nothing about the
 * things DBX's tables actually argue over, so those are implemented here in DBX's own
 * terms and never delegated:
 *
 *  - **cross-page selection** — the substrate's select-all toggles the current page only,
 *    so 「已选 N 张」, 「选中符合当前筛选的全部」, per-row exclusions and undo are DBX's
 *    model (`./selection.ts`);
 *  - **paging** — DBX slices the rows itself, because 「当前页」 has to be a concept the
 *    selection model can talk about rather than a private substrate state;
 *  - **density** — two values only, because ADR-0014 rules 24px out; the preference is
 *    persisted, which Carbon does not do;
 *  - **column visibility** — a DBX dialog in DBX's words, rather than the substrate's
 *    tearsheet, so the copy stays inside `src/messages`;
 *  - **empty, no-match and error states** — including the distinction between 「没有数据」
 *    and 「没有匹配项」, which the substrate does not make.
 *
 * Column *resizing* is genuinely the substrate's (`useResizeColumns` + `useFlexResize`,
 * on by default), as are the sticky identifying column, the virtual row window and the
 * skeleton rows. That split is the answer to "is this a pass-through shell": swapping to
 * TanStack Table v8 would rewrite this file and leave `./types.ts` untouched.
 */

const defaultPageSize = 25;
const defaultPageSizes = [25, 50, 100] as const;
const defaultVirtualHeight = 480;
const selectionColumnId = 'dbx-selection';

/** Substrate row sizes are an implementation detail; only this line knows the translation. */
const rowSizeOfDensity = { condensed: 'sm', comfortable: 'md' } as const;

/** The substrate's own state type, named once so no other line has to mention it. */
type SubstrateGridState = ComponentProps<typeof Datagrid>['datagridState'];

interface SubstrateCellProps<TRow> {
  readonly row?: { readonly original?: TRow };
}

export function DbxTable<TRow>({
  label,
  columns,
  rows,
  rowId,
  onRowActivate,
  loading = false,
  error = null,
  empty,
  filterActive = false,
  rowWindow = { kind: 'paged' },
  selection,
  densityPreferenceKey,
  toolbar,
}: DbxTableProps<TRow>) {
  const tableId = `dbx-table-${useId().replace(/:/g, '')}`;
  const [density, setDensity] = useDbxTableDensity(densityPreferenceKey);
  const [hiddenColumnIds, setHiddenColumnIds] = useState<readonly string[]>([]);
  const [columnsDialogOpen, setColumnsDialogOpen] = useState(false);
  const [pendingAction, setPendingAction] = useState<DbxBatchAction | null>(null);
  const [performed, setPerformed] = useState<{
    readonly action: DbxBatchAction;
    readonly snapshot: DbxSelectionSnapshot;
  } | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(
    rowWindow.kind === 'paged' ? (rowWindow.pageSize ?? defaultPageSize) : defaultPageSize,
  );

  const model = selection?.model;
  const unitLabel = selection?.unitLabel ?? messages.table.unitLabel;

  const matchingIds = useMemo(() => rows.map(rowId), [rows, rowId]);

  const visibleColumns = useMemo(
    () =>
      columns.filter(
        (column) => column.identifying === true || !hiddenColumnIds.includes(column.id),
      ),
    [columns, hiddenColumnIds],
  );

  const paged = rowWindow.kind === 'paged';
  const pageCount = paged ? Math.max(1, Math.ceil(rows.length / pageSize)) : 1;
  const currentPage = Math.min(page, pageCount);
  const pageRows = useMemo(
    () => (paged ? rows.slice((currentPage - 1) * pageSize, currentPage * pageSize) : [...rows]),
    [paged, rows, currentPage, pageSize],
  );
  const pageIds = useMemo(() => pageRows.map(rowId), [pageRows, rowId]);

  const allOnPageSelected =
    model !== undefined && pageIds.length > 0 && pageIds.every((id) => model.isSelected(id));

  const togglePage = useCallback(() => {
    if (model === undefined) return;
    if (allOnPageSelected) {
      model.clearPage(pageIds);
    } else {
      model.selectPage(pageIds);
    }
  }, [model, allOnPageSelected, pageIds]);

  // The substrate registers its plugins with react-table on mount, so the plugin list has
  // to be stable for the life of the component; capturing it once is deliberate.
  const initialShape = useRef({
    sticky: columns.some((column) => column.identifying === true),
    virtual: rowWindow.kind === 'virtual',
    // Row activation is a capability of the table, not a per-render decision: a caller
    // either navigates from rows or it does not.
    activatable: onRowActivate !== undefined,
  });
  const plugins = useMemo(() => {
    const chosen = [];
    if (initialShape.current.sticky) chosen.push(useStickyColumn);
    if (initialShape.current.virtual) chosen.push(useInfiniteScroll);
    // Without this plugin the substrate accepts an `onRowClick` option and never calls it,
    // which is how row activation can look wired up and do nothing. It is also what makes
    // an activatable row reachable by keyboard: the plugin gives the row a tab stop and
    // activates it on Enter.
    if (initialShape.current.activatable) chosen.push(useOnRowClick);
    return chosen;
  }, []);

  const substrateColumns = useMemo(() => {
    const rendered = visibleColumns.map((column: DbxTableColumn<TRow>) => ({
      id: column.id,
      Header: column.header,
      accessor: (row: TRow) => column.textValue?.(row) ?? '',
      width: column.width ?? 180,
      disableSortBy: true,
      ...(column.identifying === true ? { sticky: 'left' as const } : {}),
      Cell: ({ row }: SubstrateCellProps<TRow>) =>
        row?.original === undefined ? null : column.renderCell(row.original),
    }));

    if (model === undefined) {
      return rendered;
    }

    // DBX renders its own selection column: the checkbox has to speak for a model the
    // substrate has no concept of (an exclusion inside 「符合当前筛选的全部」 is not the
    // same fact as an unticked row).
    return [
      {
        id: selectionColumnId,
        width: 48,
        disableSortBy: true,
        disableResizing: true,
        // The two selection scopes are named in words in the toolbar below rather than
        // hidden behind a header tick: 「当前页全选」 and 「符合当前筛选的全部」 mean
        // different things, and a single unlabelled checkbox cannot say which one it did.
        Header: '',
        accessor: () => '',
        Cell: ({ row }: SubstrateCellProps<TRow>) => {
          if (row?.original === undefined) return null;
          const id = rowId(row.original);
          const identifying = columns.find((column) => column.identifying === true);
          const rowLabel = identifying?.textValue?.(row.original) ?? id;
          return (
            <Checkbox
              id={`${tableId}-select-${id}`}
              labelText={messages.table.selection.rowLabel(rowLabel)}
              hideLabel
              checked={model.isSelected(id)}
              onChange={() => model.toggleRow(id)}
            />
          );
        },
      },
      ...rendered,
    ];
  }, [visibleColumns, model, tableId, rowId, columns]);

  const datagridState = useDatagrid(
    {
      columns: substrateColumns,
      data: pageRows,
      rowSize: rowSizeOfDensity[density],
      isFetching: loading,
      tableId,
      ariaToolbarLabel: label,
      ...(initialShape.current.virtual
        ? {
            virtualHeight:
              rowWindow.kind === 'virtual'
                ? (rowWindow.visibleHeight ?? defaultVirtualHeight)
                : defaultVirtualHeight,
          }
        : {}),
      ...(onRowActivate === undefined
        ? {}
        : { onRowClick: (row: { original: TRow }) => onRowActivate(row.original) }),
    },
    ...plugins,
  );

  if (error !== null) {
    return (
      <section className="dbx-table" aria-label={label}>
        <ErrorState title={error.title} body={error.body} onRetry={error.onRetry} />
      </section>
    );
  }

  const showEmpty = !loading && rows.length === 0;
  const emptyCopy = filterActive ? messages.table.noMatches : empty;

  const performAction = (action: DbxBatchAction) => {
    if (model === undefined) return;
    const snapshot = selectionSnapshot(model.scope, matchingIds);
    action.perform(snapshot);
    if (action.safety.kind === 'undoable') {
      setPerformed({ action, snapshot });
    }
  };

  return (
    <section className="dbx-table" aria-label={label}>
      <div className="dbx-table__toolbar">
        {toolbar}
        <ContentSwitcher
          aria-label={messages.table.density.label}
          size="sm"
          selectedIndex={density === 'condensed' ? 0 : 1}
          onChange={({ index }) => setDensity(index === 0 ? 'condensed' : 'comfortable')}
        >
          <Switch name="condensed" text={messages.table.density.condensed} />
          <Switch name="comfortable" text={messages.table.density.comfortable} />
        </ContentSwitcher>
        <Button kind="ghost" size="sm" onClick={() => setColumnsDialogOpen(true)}>
          {messages.table.columnsAction}
        </Button>
      </div>

      {model !== undefined && model.selectedCount > 0 ? (
        <div className="dbx-table__batch" role="region" aria-label={messages.table.batch.label}>
          <p className="dbx-table__batch-count">
            {messages.table.selection.selectedCount(model.selectedCount, unitLabel)}
          </p>
          {model.scope.kind === 'allMatchingFilter' ? (
            <p className="dbx-table__batch-scope">
              {messages.table.selection.allMatchingSelected(model.selectedCount, unitLabel)}
            </p>
          ) : null}
          {model.excludedIds.length > 0 ? (
            <p className="dbx-table__batch-excluded">
              {messages.table.selection.excludedCount(model.excludedIds.length, unitLabel)}
            </p>
          ) : null}
          {(selection?.batchActions ?? []).map((action) => (
            <Button
              key={action.id}
              kind="ghost"
              size="sm"
              onClick={() =>
                action.safety.kind === 'confirmed'
                  ? setPendingAction(action)
                  : performAction(action)
              }
            >
              {action.label}
            </Button>
          ))}
          <Button kind="ghost" size="sm" onClick={() => model.clear()}>
            {messages.table.selection.clearAction}
          </Button>
          {model.canUndo ? (
            <Button kind="ghost" size="sm" onClick={() => model.undo()}>
              {messages.table.selection.undoAction}
            </Button>
          ) : null}
        </div>
      ) : null}

      {model !== undefined ? (
        <div className="dbx-table__selection-scope">
          {paged ? (
            <Button kind="ghost" size="sm" onClick={togglePage}>
              {allOnPageSelected
                ? messages.table.selection.clearPageAction
                : messages.table.selection.selectPageAction}
            </Button>
          ) : null}
          <Button kind="ghost" size="sm" onClick={() => model.selectAllMatchingFilter()}>
            {messages.table.selection.selectAllMatchingAction}
          </Button>
        </div>
      ) : null}

      {performed !== null && performed.action.safety.kind === 'undoable' ? (
        <div className="dbx-table__undo" role="status">
          <Button
            kind="ghost"
            size="sm"
            onClick={() => {
              performed.action.undo?.(performed.snapshot);
              setPerformed(null);
            }}
          >
            {performed.action.safety.undoLabel}
          </Button>
        </div>
      ) : null}

      {showEmpty ? (
        <EmptyState title={emptyCopy.title} body={emptyCopy.body} action={empty.action} />
      ) : (
        <Datagrid datagridState={datagridState as unknown as SubstrateGridState} />
      )}

      {paged && !showEmpty ? (
        <Pagination
          page={currentPage}
          pageSize={pageSize}
          pageSizes={[
            ...(rowWindow.kind === 'paged'
              ? (rowWindow.pageSizes ?? defaultPageSizes)
              : defaultPageSizes),
          ]}
          totalItems={rows.length}
          backwardText={messages.table.pagination.backward}
          forwardText={messages.table.pagination.forward}
          itemsPerPageText={messages.table.pagination.itemsPerPage}
          itemRangeText={messages.table.pagination.itemRange}
          pageRangeText={messages.table.pagination.pageRange}
          onChange={({ page: nextPage, pageSize: nextPageSize }) => {
            setPage(nextPage);
            setPageSize(nextPageSize);
          }}
        />
      ) : null}

      {columnsDialogOpen ? (
        <Modal
          open
          modalHeading={messages.table.columnsTitle}
          passiveModal
          onRequestClose={() => setColumnsDialogOpen(false)}
          aria-label={messages.table.columnsTitle}
        >
          <p>{messages.table.columnsDescription}</p>
          {columns.map((column) => (
            <Checkbox
              key={column.id}
              id={`${tableId}-column-${column.id}`}
              labelText={column.header}
              disabled={column.identifying === true}
              checked={column.identifying === true || !hiddenColumnIds.includes(column.id)}
              onChange={() =>
                setHiddenColumnIds((hidden) =>
                  hidden.includes(column.id)
                    ? hidden.filter((entry) => entry !== column.id)
                    : [...hidden, column.id],
                )
              }
            />
          ))}
        </Modal>
      ) : null}

      {pendingAction !== null && pendingAction.safety.kind === 'confirmed' ? (
        <Modal
          open
          modalHeading={
            pendingAction?.safety.kind === 'confirmed' ? pendingAction.safety.title : ''
          }
          primaryButtonText={
            pendingAction?.safety.kind === 'confirmed'
              ? pendingAction.safety.confirmLabel
              : messages.table.batch.confirmAction
          }
          secondaryButtonText={messages.table.batch.cancelAction}
          onRequestClose={() => setPendingAction(null)}
          onSecondarySubmit={() => setPendingAction(null)}
          onRequestSubmit={() => {
            if (pendingAction !== null) performAction(pendingAction);
            setPendingAction(null);
          }}
        >
          <p>{pendingAction?.safety.kind === 'confirmed' ? pendingAction.safety.body : ''}</p>
        </Modal>
      ) : null}
    </section>
  );
}
