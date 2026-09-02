import { useMemo } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SourceTableSummary } from '@/contract';
import { messages } from '@/messages';
import { generateSourceTables } from '@/mocks/fixtures/sourceTables';
import { ConclusionIndicator } from '@/conclusions';
import { DbxTable } from './DbxTable';
import { useDbxSelection } from './selection';
import type { DbxBatchAction, DbxTableColumn, DbxTableProps, DbxTableRowWindow } from './types';

/**
 * Seam 2 (#30): the public interface of `DbxTable`.
 *
 * This is the only unit-test seam in the frontend, and it exists for one reason — ADR-0015
 * promises that swapping the table substrate is a single-module change, and that promise
 * cannot be checked in a browser. Everything asserted here is asserted through the
 * interface in `./types.ts` and in domain language; nothing reaches for a Carbon class name
 * or for the shape of the substrate's markup.
 */

const columns: readonly DbxTableColumn<SourceTableSummary>[] = [
  {
    id: 'name',
    header: messages.densitySample.columns.sourceTable,
    identifying: true,
    textValue: (table) => table.name,
    renderCell: (table) => table.name,
  },
  {
    id: 'rowCount',
    header: messages.densitySample.columns.rowCount,
    textValue: (table) => String(table.estimatedRowCount),
    renderCell: (table) => String(table.estimatedRowCount),
  },
  {
    id: 'conclusion',
    header: messages.densitySample.columns.conclusion,
    textValue: (table) => table.preflightConclusion,
    renderCell: (table) => <ConclusionIndicator conclusion={table.preflightConclusion} />,
  },
];

const fixture = generateSourceTables({ seed: 20260901 });

interface HarnessProps {
  readonly rows: readonly SourceTableSummary[];
  readonly withSelection?: boolean;
  readonly batchActions?: readonly DbxBatchAction[];
  readonly rowWindow?: DbxTableRowWindow;
  readonly loading?: boolean;
  readonly loadingDescription?: string;
  readonly error?: DbxTableProps<SourceTableSummary>['error'];
  readonly filterActive?: boolean;
  readonly densityPreferenceKey?: string;
}

function Harness({
  rows,
  withSelection = false,
  batchActions,
  rowWindow = { kind: 'paged', pageSize: 2 },
  loading = false,
  loadingDescription,
  error = null,
  filterActive = false,
  densityPreferenceKey,
}: HarnessProps) {
  const ids = useMemo(() => rows.map((row) => row.name), [rows]);
  const model = useDbxSelection(ids);
  return (
    <DbxTable
      label="源表清单"
      columns={columns}
      rows={rows}
      rowId={(row) => row.name}
      rowWindow={rowWindow}
      loading={loading}
      loadingDescription={loadingDescription}
      error={error}
      filterActive={filterActive}
      densityPreferenceKey={densityPreferenceKey}
      empty={{ title: '这个源库里没有表', body: '换一个源数据库再看。' }}
      selection={withSelection ? { model, unitLabel: '张', batchActions } : undefined}
    />
  );
}

/** Rows the substrate has actually mounted, counted through the accessibility tree. */
function mountedRowCount(): number {
  return screen.queryAllByRole('row').length;
}

describe('DbxTable — seam 2', () => {
  it('renders the columns it was given, in domain language', () => {
    render(<Harness rows={fixture.slice(0, 2)} />);
    const table = screen.getByRole('region', { name: '源表清单' });
    expect(within(table).getByText(messages.densitySample.columns.sourceTable)).toBeInTheDocument();
    expect(within(table).getByText(messages.densitySample.columns.rowCount)).toBeInTheDocument();
    const firstRow = fixture[0];
    expect(within(table).getByText(firstRow?.name ?? '')).toBeInTheDocument();
  });

  it('renders a conclusion with its own text, never colour alone', () => {
    const blocked = fixture.filter((table) => table.preflightConclusion === 'INCONCLUSIVE');
    render(<Harness rows={blocked.slice(0, 1)} />);
    expect(screen.getAllByText(messages.conclusion.labels.INCONCLUSIVE).length).toBeGreaterThan(0);
  });

  it('says 没有匹配项 when a filter emptied the table, and the caller’s words otherwise', () => {
    const { unmount } = render(<Harness rows={[]} filterActive />);
    expect(screen.getByText(messages.table.noMatches.title)).toBeInTheDocument();
    expect(screen.queryByText('这个源库里没有表')).toBeNull();
    unmount();

    render(<Harness rows={[]} />);
    expect(screen.getByText('这个源库里没有表')).toBeInTheDocument();
    expect(screen.queryByText(messages.table.noMatches.title)).toBeNull();
  });

  it('offers a retry rather than a blank page when the read failed', async () => {
    const onRetry = vi.fn();
    render(<Harness rows={[]} error={{ title: '读取失败', body: '稍后重试。', onRetry }} />);
    await userEvent.click(screen.getByRole('button', { name: messages.common.retry }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows skeleton rows while the first read is in flight, not the empty state', () => {
    render(<Harness rows={[]} loading />);
    expect(screen.queryByText('这个源库里没有表')).toBeNull();
    // Header row plus the substrate's skeleton rows.
    expect(mountedRowCount()).toBeGreaterThan(1);
  });

  it('says what it is reading while it reads, not only in skeleton rows', () => {
    // Skeleton rows are a loading state for a reader who can see them and an empty grid
    // for a reader who cannot — indistinguishable from 「没有数据」. #42 requires every key
    // view's loading state to be readable in domain language, so the sentence is announced.
    render(<Harness rows={[]} loading loadingDescription={messages.tasks.loading} />);
    const announcement = screen.getByRole('status');
    expect(announcement).toHaveTextContent(messages.tasks.loading);
  });

  it('stops announcing the read once the rows have arrived', () => {
    render(<Harness rows={fixture.slice(0, 2)} loadingDescription={messages.tasks.loading} />);
    expect(screen.queryByText(messages.tasks.loading)).toBeNull();
  });

  it('switches density between the two allowed row heights and remembers the choice', async () => {
    window.localStorage.clear();
    const { unmount } = render(
      <Harness rows={fixture.slice(0, 2)} densityPreferenceKey="seam-two" />,
    );
    await userEvent.click(screen.getByRole('tab', { name: messages.table.density.comfortable }));
    expect(screen.getByRole('tab', { name: messages.table.density.comfortable })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    unmount();

    render(<Harness rows={fixture.slice(0, 2)} densityPreferenceKey="seam-two" />);
    expect(screen.getByRole('tab', { name: messages.table.density.comfortable })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('hides a column on request and keeps the identifying column', async () => {
    render(<Harness rows={fixture.slice(0, 2)} />);
    await userEvent.click(screen.getByRole('button', { name: messages.table.columnsAction }));
    const dialog = screen.getByRole('dialog');
    expect(
      within(dialog).getByRole('checkbox', { name: messages.densitySample.columns.sourceTable }),
    ).toBeDisabled();
    await userEvent.click(
      within(dialog).getByRole('checkbox', { name: messages.densitySample.columns.rowCount }),
    );
    await userEvent.keyboard('{Escape}');
    const table = screen.getByRole('region', { name: '源表清单' });
    expect(within(table).queryByText(messages.densitySample.columns.rowCount)).toBeNull();
    expect(within(table).getByText(messages.densitySample.columns.sourceTable)).toBeInTheDocument();
  });

  it('distinguishes 当前页全选 from 符合当前筛选的全部, and keeps the count across pages', async () => {
    const rows = fixture.slice(0, 5);
    render(<Harness rows={rows} withSelection />);

    await userEvent.click(
      screen.getByRole('button', { name: messages.table.selection.selectPageAction }),
    );
    expect(screen.getByText(messages.table.selection.selectedCount(2, '张'))).toBeInTheDocument();

    // Paging away must not lose the selection: that is the whole point of the model.
    await userEvent.click(screen.getByRole('button', { name: messages.table.pagination.forward }));
    expect(screen.getByText(messages.table.selection.selectedCount(2, '张'))).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: messages.table.selection.selectAllMatchingAction }),
    );
    expect(screen.getByText(messages.table.selection.selectedCount(5, '张'))).toBeInTheDocument();
    expect(
      screen.getByText(messages.table.selection.allMatchingSelected(5, '张')),
    ).toBeInTheDocument();
  });

  it('records an exclusion and lets it be undone', async () => {
    const rows = fixture.slice(0, 5);
    render(<Harness rows={rows} withSelection />);
    await userEvent.click(
      screen.getByRole('button', { name: messages.table.selection.selectAllMatchingAction }),
    );

    const excluded = rows[0];
    await userEvent.click(
      screen.getByRole('checkbox', {
        name: messages.table.selection.rowLabel(excluded?.name ?? ''),
      }),
    );
    expect(screen.getByText(messages.table.selection.selectedCount(4, '张'))).toBeInTheDocument();
    expect(screen.getByText(messages.table.selection.excludedCount(1, '张'))).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: messages.table.selection.undoAction }),
    );
    expect(screen.getByText(messages.table.selection.selectedCount(5, '张'))).toBeInTheDocument();
  });

  it('shows the batch action bar only when something is selected, with the count on it', async () => {
    const perform = vi.fn();
    const undo = vi.fn();
    const actions: readonly DbxBatchAction[] = [
      {
        id: 'exclude',
        label: '排除所选',
        safety: { kind: 'undoable', undoLabel: '撤销排除' },
        perform,
        undo,
      },
    ];
    render(<Harness rows={fixture.slice(0, 5)} withSelection batchActions={actions} />);
    expect(screen.queryByRole('region', { name: messages.table.batch.label })).toBeNull();

    await userEvent.click(
      screen.getByRole('button', { name: messages.table.selection.selectPageAction }),
    );
    const bar = screen.getByRole('region', { name: messages.table.batch.label });
    expect(
      within(bar).getByText(messages.table.selection.selectedCount(2, '张')),
    ).toBeInTheDocument();

    await userEvent.click(within(bar).getByRole('button', { name: '排除所选' }));
    expect(perform).toHaveBeenCalledOnce();
    expect(perform.mock.calls[0]?.[0]).toMatchObject({ selectedCount: 2 });

    await userEvent.click(screen.getByRole('button', { name: '撤销排除' }));
    expect(undo).toHaveBeenCalledOnce();
  });

  it('asks a second time before an action that cannot be taken back', async () => {
    const perform = vi.fn();
    const actions: readonly DbxBatchAction[] = [
      {
        id: 'discard',
        label: '丢弃所选',
        safety: {
          kind: 'confirmed',
          title: '确认丢弃',
          body: '丢弃后不可恢复。',
          confirmLabel: '丢弃',
        },
        perform,
      },
    ];
    render(<Harness rows={fixture.slice(0, 5)} withSelection batchActions={actions} />);
    await userEvent.click(
      screen.getByRole('button', { name: messages.table.selection.selectPageAction }),
    );
    await userEvent.click(screen.getByRole('button', { name: '丢弃所选' }));
    expect(perform).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: '丢弃' }));
    expect(perform).toHaveBeenCalledOnce();
  });

  it('mounts a bounded number of rows at production scale', () => {
    // Lead decision D5: seam 2 asserts the mechanism, not milliseconds. jsdom measures no
    // layout, so a timing assertion here would be theatre; what matters is that the number
    // of mounted rows does not grow with the data.
    expect(fixture).toHaveLength(1200);
    const { unmount } = render(
      <Harness rows={fixture} rowWindow={{ kind: 'virtual', visibleHeight: 480 }} />,
    );
    const atTwelveHundred = mountedRowCount();
    expect(atTwelveHundred).toBeLessThan(100);
    unmount();

    render(
      <Harness
        rows={[...fixture, ...fixture]}
        rowWindow={{ kind: 'virtual', visibleHeight: 480 }}
      />,
    );
    expect(mountedRowCount()).toBe(atTwelveHundred);
  });

  it('mounts only one page of rows when it is paged', () => {
    render(<Harness rows={fixture} rowWindow={{ kind: 'paged', pageSize: 25 }} />);
    expect(mountedRowCount()).toBeLessThanOrEqual(26);
  });
});
