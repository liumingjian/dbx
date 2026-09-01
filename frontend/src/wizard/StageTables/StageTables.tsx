import { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  useDraftTableConfigurations,
  useDraftTableWorkspace,
  useRecordMappingRule,
} from '@/api/draftTables';
import { EmptyState, ErrorState, LoadingState } from '@/components/ViewState';
import type { MappingRuleAction } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import type { WizardGateContext } from '../stageGates';
import { DdlPane } from './DdlPane';
import { FindingsPane } from './FindingsPane';
import { ObjectTreePane } from './ObjectTreePane';

/**
 * Stage three — 逐表配置与预检, front half (#35).
 *
 * The stage is one screen with three panes: the object tree, the source and target DDL
 * side by side, and the findings list (story 38). That layout is the stated reason the
 * wizard is a full page rather than Carbon's recommended wide tearsheet (ADR-0014), so it
 * has to actually fit — which is why the panes scroll inside themselves rather than
 * letting the page grow.
 *
 * The DDL is a **read-only complete rendering of the 表写入契约** (ADR-0011). Structure is
 * changed through the bounded controls in the findings pane, and the contract and both
 * DDLs are regenerated from the recorded 映射规则. There is no path from this component to
 * a text edit, which is Gate 4 stated as a shape rather than as a rule.
 *
 * Which table is open lives in the URL (`?table=`), like everything else in DBX: a DBA
 * has to be able to paste a link to the table they are arguing about.
 */
interface StageTablesProps {
  readonly context: WizardGateContext;
}

export function StageTables({ context }: StageTablesProps) {
  const { draft } = context;
  const [searchParams, setSearchParams] = useSearchParams();
  const configurations = useDraftTableConfigurations(draft.id);
  const tables = configurations.data ?? [];

  const requested = searchParams.get('table');
  // A link to a table that is no longer in the 迁移范围 opens the first one rather than an
  // error: the draft moved on, and there is somewhere real to land.
  const selected =
    requested !== null && tables.some((table) => table.sourceTable === requested)
      ? requested
      : (tables[0]?.sourceTable ?? null);

  const workspace = useDraftTableWorkspace(draft.id, selected);
  const record = useRecordMappingRule(draft.id);

  const openTable = useCallback(
    (sourceTable: string) => {
      // Rewritten from the existing query rather than replaced: `?scenario=` also lives
      // there, and dropping it would silently serve a different mock world. `replace`, so
      // walking the object tree does not fill the back button with tables.
      setSearchParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          next.set('table', sourceTable);
          return next;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  const chooseRule = useCallback(
    (sourceColumn: string, action: MappingRuleAction, targetValue: string) => {
      if (selected === null) {
        return;
      }
      record.mutate({ sourceTable: selected, sourceColumn, action, targetValue });
    },
    [record, selected],
  );

  if (configurations.isPending) {
    return <LoadingState description={messages.wizard.tables.loading} />;
  }

  if (configurations.isError) {
    return (
      <ErrorState
        title={messages.wizard.tables.error.title}
        body={messages.wizard.tables.error.body}
        onRetry={() => void configurations.refetch()}
      />
    );
  }

  if (tables.length === 0) {
    return (
      <EmptyState
        title={messages.wizard.tables.emptyScope.title}
        body={messages.wizard.tables.emptyScope.body}
      />
    );
  }

  const contract = workspace.data?.tableWriteContract ?? null;

  return (
    <section className="dbx-workspace" aria-label={messages.wizard.stages.tables}>
      <p className="dbx-wizard__lead">{messages.wizard.tables.lead}</p>

      <div className="dbx-workspace__grid">
        <ObjectTreePane
          tables={tables}
          selectedTable={selected}
          onSelectTable={openTable}
          objects={workspace.data?.objectTree ?? null}
        />

        <div className="dbx-workspace__content">
          {workspace.isPending ? (
            <LoadingState description={messages.wizard.tables.loading} />
          ) : workspace.isError ? (
            <ErrorState
              title={messages.wizard.tables.error.title}
              body={messages.wizard.tables.error.body}
              onRetry={() => void workspace.refetch()}
            />
          ) : (
            <>
              <p className="dbx-workspace__notice">{messages.wizard.tables.readOnlyNotice}</p>

              <DdlPane
                title={messages.wizard.tables.sourceDdlTitle}
                sql={workspace.data?.sourceDdl ?? null}
                emptyTitle={messages.wizard.tables.chooseTable}
                emptyBody={messages.wizard.tables.chooseTable}
              />

              <div className="dbx-workspace__target">
                <DdlPane
                  title={messages.wizard.tables.targetDdlTitle}
                  sql={contract?.targetDdl ?? null}
                  emptyTitle={messages.wizard.tables.contractMissing.title}
                  emptyBody={messages.wizard.tables.contractMissing.body}
                  facts={
                    contract === null
                      ? undefined
                      : `${messages.wizard.tables.contractVersion(contract.version)} · ${messages.wizard.tables.contractGeneratedAt(
                          formatTimestamp(contract.generatedAt),
                        )}`
                  }
                />
                {contract?.supplementalSql == null ? null : (
                  <DdlPane
                    title={messages.wizard.tables.supplementalTitle}
                    sql={contract.supplementalSql}
                    facts={messages.wizard.tables.outOfContractNotice}
                  />
                )}
              </div>

              <FindingsPane
                exceptions={workspace.data?.mappingExceptions ?? []}
                onChoose={chooseRule}
                recording={record.isPending}
              />
            </>
          )}
        </div>
      </div>
    </section>
  );
}
