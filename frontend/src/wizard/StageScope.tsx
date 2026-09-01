import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Search, Tag } from '@carbon/react';
import { useSourceTables } from '@/api/migrationTasks';
import {
  DbxTable,
  selectedIdsWithin,
  useDbxSelection,
  type DbxTableColumn,
} from '@/components/DbxTable';
import { ConclusionIndicator } from '@/conclusions';
import type { MigrationDraftPatch, SourceTableSummary } from '@/contract';
import { formatBytes, formatCount } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import {
  draftPatchOfSelection,
  matchesSearch,
  selectionScopeOfDraft,
  sortedSourceTables,
} from './scopeSelection';
import type { WizardGateContext } from './stageGates';

/**
 * Stage two — 迁移范围.
 *
 * The stage exists to answer one question honestly: is choosing tables in a 1200-table
 * production database still workable? That is why the table is virtualised rather than
 * paged. A bounded window of rows is mounted no matter how many tables the database has,
 * which is what keeps scrolling smooth; the price is that there are no pages, so the
 * selection is carried by `DbxTable`'s scope model — 「逐张勾选」 or 「符合当前筛选的全部，
 * 减去显式排除的」 — and the count stays true while the filter and the viewport move.
 *
 * Row facts are discovery estimates and are labelled as such. `CONTEXT.md` lists
 * 「estimated row count」 under 源基线's `_Avoid_`: a baseline is exact and is captured under
 * a write freeze, and nothing on this screen is one.
 */
interface StageScopeProps {
  readonly context: WizardGateContext;
  readonly onPatch: (patch: MigrationDraftPatch) => void;
}

export function StageScope({ context, onPatch }: StageScopeProps) {
  const { draft } = context;
  const sourceDatabase = draft.sourceDatabase ?? '';
  const query = useSourceTables(sourceDatabase);
  const [search, setSearch] = useState('');

  const allTables = useMemo(() => sortedSourceTables(query.data ?? []), [query.data]);
  const rows = useMemo(
    () => allTables.filter((table) => matchesSearch(table, search)),
    [allTables, search],
  );
  const allNames = useMemo(() => allTables.map((table) => table.name), [allTables]);
  const matchingIds = useMemo(() => rows.map((table) => table.name), [rows]);

  // The selection starts where the draft left off, so a refresh — or a walk back from a
  // later stage — restores the decision the operator actually made rather than an empty one.
  const initialScope = useRef(selectionScopeOfDraft(draft)).current;
  // The search *is* the filter, so it is what an 「符合当前筛选的全部」 scope is bound to.
  // Clearing it then keeps the 迁移范围 the operator chose instead of widening it to the
  // whole database (D19: the count survives clearing the search).
  const filterKey = search.trim().toLowerCase();
  const model = useDbxSelection(matchingIds, initialScope, filterKey);

  // What the draft would say about the selection as it stands.
  //
  // 「符合当前筛选的全部」 is a live scope, not a snapshot (ADR-0015, and #33's model says
  // so explicitly), so it is materialised against the rows the filter is currently letting
  // through. A row-by-row selection is materialised against the whole database instead:
  // ticking a table and then searching for another one must not drop the first.
  const patch = useMemo(
    () =>
      draftPatchOfSelection(
        model.scope,
        selectedIdsWithin(model.scope, model.scope.kind === 'rows' ? allNames : matchingIds),
      ),
    [model.scope, allNames, matchingIds],
  );

  const written = useRef<string | null>(null);
  useEffect(() => {
    if (allTables.length === 0) {
      return;
    }
    const fingerprint = `${patch.scopeKind}|${patch.selectedTables.join(' ')}|${patch.excludedTables.join(' ')}`;
    const draftFingerprint = `${draft.scopeKind}|${draft.selectedTables.join(' ')}|${draft.excludedTables.join(' ')}`;
    if (fingerprint === draftFingerprint || written.current === fingerprint) {
      return;
    }
    // Written through on every change rather than saved at the end of the stage: half a
    // day spent choosing out of 1200 tables must not depend on the operator reaching a
    // button before their browser closes.
    written.current = fingerprint;
    onPatch(patch);
  }, [patch, draft, allTables.length, onPatch]);

  const excluded = model.excludedIds;

  const columns = useMemo<readonly DbxTableColumn<SourceTableSummary>[]>(
    () => [
      {
        id: 'name',
        header: messages.wizard.scope.columns.name,
        // The identifying column stays visible while the table is scrolled sideways: when
        // the right-hand facts are what you are reading, you still need to know whose.
        identifying: true,
        width: 280,
        textValue: (table) => table.name,
        renderCell: (table) => <Identifier>{table.name}</Identifier>,
      },
      {
        id: 'sourceDatabase',
        header: messages.wizard.scope.columns.sourceDatabase,
        width: 180,
        textValue: (table) => table.sourceDatabase,
        renderCell: (table) => <Identifier>{table.sourceDatabase}</Identifier>,
      },
      {
        id: 'columnCount',
        header: messages.wizard.scope.columns.columnCount,
        width: 100,
        textValue: (table) => String(table.columnCount),
        renderCell: (table) => <Identifier>{table.columnCount}</Identifier>,
      },
      {
        id: 'estimatedRowCount',
        header: messages.wizard.scope.columns.estimatedRowCount,
        width: 160,
        textValue: (table) => formatCount(table.estimatedRowCount),
        renderCell: (table) => <Identifier>{formatCount(table.estimatedRowCount)}</Identifier>,
      },
      {
        id: 'estimatedBytes',
        header: messages.wizard.scope.columns.estimatedBytes,
        width: 160,
        textValue: (table) => formatBytes(table.estimatedBytes),
        renderCell: (table) => <Identifier>{formatBytes(table.estimatedBytes)}</Identifier>,
      },
      {
        id: 'condition',
        header: messages.wizard.scope.columns.condition,
        width: 320,
        textValue: (table) => table.preflightConclusion,
        renderCell: (table) => (
          <span className="dbx-scope__condition">
            {/* The conclusion is an indicator, never a Tag: Carbon reserves Tag for
                categorisation (ADR-0014). 大记录表 is a category, so it is a Tag. */}
            <ConclusionIndicator
              conclusion={table.preflightConclusion}
              label={messages.conclusion.labels[table.preflightConclusion]}
            />
            {table.largeRecordTable ? (
              <Tag type="cool-gray" size="sm">
                {messages.wizard.scope.largeRecordTable}
              </Tag>
            ) : null}
            {table.mappingExceptionCount > 0 ? (
              <span>{messages.wizard.scope.mappingExceptions(table.mappingExceptionCount)}</span>
            ) : null}
            {table.preflightBlockingFindingCount > 0 ? (
              <span>
                {messages.wizard.scope.blockingFindings(table.preflightBlockingFindingCount)}
              </span>
            ) : null}
          </span>
        ),
      },
    ],
    [],
  );

  const toolbar = (
    <Search
      id="wizard-scope-search"
      size="sm"
      labelText={messages.wizard.scope.searchLabel}
      placeholder={messages.wizard.scope.searchPlaceholder}
      value={search}
      onChange={(event) => setSearch(event.target.value)}
      onClear={() => setSearch('')}
    />
  );

  return (
    <section className="dbx-scope" aria-label={messages.wizard.stages.scope}>
      <p className="dbx-wizard__lead">{messages.wizard.scope.lead}</p>
      <p className="dbx-wizard__fact">{messages.wizard.scope.estimateNotice}</p>

      <p
        className="dbx-scope__summary"
        role="status"
        aria-label={messages.wizard.scope.summaryLabel}
      >
        {messages.wizard.scope.scopeTotal(patch.selectedTables.length)}
      </p>

      <DbxTable
        label={messages.wizard.scope.listLabel}
        columns={columns}
        rows={rows}
        rowId={(table) => table.name}
        rowWindow={{ kind: 'virtual', visibleHeight: 480 }}
        loading={query.isPending}
        error={
          query.isError
            ? {
                title: messages.wizard.scope.error.title,
                body: messages.wizard.scope.error.body,
                onRetry: () => void query.refetch(),
              }
            : null
        }
        empty={{
          title: messages.wizard.scope.empty.title,
          body: messages.wizard.scope.empty.body,
        }}
        filterActive={search.trim() !== ''}
        densityPreferenceKey="wizard-scope"
        selection={{ model, unitLabel: messages.wizard.scope.unitLabel }}
        toolbar={toolbar}
      />

      <section className="dbx-scope__excluded" aria-label={messages.wizard.scope.excludedHeading}>
        <h3 className="dbx-wizard__pane-title">{messages.wizard.scope.excludedHeading}</h3>
        <p className="dbx-wizard__fact">{messages.wizard.scope.excludedConsequence}</p>
        {excluded.length === 0 ? (
          <p className="dbx-wizard__fact">{messages.wizard.scope.excludedEmpty}</p>
        ) : (
          <ul className="dbx-scope__excluded-list">
            {excluded.map((name) => (
              <li key={name}>
                <Identifier>{name}</Identifier>
                <Button kind="ghost" size="sm" onClick={() => model.toggleRow(name)}>
                  {messages.wizard.scope.restoreAction(name)}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </section>
  );
}
