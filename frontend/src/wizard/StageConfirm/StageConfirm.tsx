import { useMemo, useState } from 'react';
import { Button, InlineNotification, Tag } from '@carbon/react';
import { useNavigate } from 'react-router-dom';
import { useExecutionConfirmationSummary, useStartMigrationRun } from '@/api/executionConfirmation';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import { ConclusionIndicator } from '@/conclusions';
import { findingDetail, findingLabel } from '@/features/preflight/preflightVocabulary';
import { ErrorState, LoadingState } from '@/components/ViewState';
import type {
  ExecutionSummaryTable,
  MigrationDraftPatch,
  UnresolvedFinding,
  WriteFreezeDeclaration,
} from '@/contract';
import { formatCount } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { paths } from '@/routes/paths';
import { mayStartMigration, type WizardGateContext } from '../stageGates';
import { StartMigrationModal } from './StartMigrationModal';
import { WriteFreezePanel } from './WriteFreezePanel';

/**
 * Stage four — 执行确认 (#37).
 *
 * One screen, one question: is this what should happen to a production database in the
 * next three hours? Everything the operator has decided across three stages is restated
 * here as facts assembled by the platform — the pair, the 迁移范围, the 显式排除, the
 * 表写入契约 that would be approved, and the 预检发现 nobody resolved — because a global
 * check made against four separate screens is not a global check.
 *
 * Two constraints stand between the summary and a running migration, and the stage owns
 * both. **Gate 5**: no 写冻结 with a named 责任人 and a 时限, no start. **Gate 6**: no
 * 结构证明, no writing to the target — stated rather than enforced here (lead decision
 * D11), because the comparison is a server-side catalog proof performed inside the run.
 * The refusal is real either way: while the summary reports a table no 结构证明 can be
 * established for, this screen will not start anything.
 *
 * The 未解决的发现 are placed above the scope rather than below it. They are the one thing
 * on the page nobody chose to see, and a summary that buries them at the bottom is how a
 * production migration starts with an unread finding in it.
 */
interface StageConfirmProps {
  readonly context: WizardGateContext;
  readonly onPatch: (patch: MigrationDraftPatch) => void;
}

export function StageConfirm({ context, onPatch }: StageConfirmProps) {
  const { draft } = context;
  const copy = messages.wizard.confirm;
  const navigate = useNavigate();
  const summaryQuery = useExecutionConfirmationSummary(draft.id);
  const start = useStartMigrationRun(draft.id);
  const [confirming, setConfirming] = useState(false);

  const summary = summaryQuery.data ?? null;
  const canStart = mayStartMigration(context);

  const tableColumns = useMemo<readonly DbxTableColumn<ExecutionSummaryTable>[]>(
    () => [
      {
        id: 'sourceTable',
        header: copy.tableColumns.sourceTable,
        identifying: true,
        width: 240,
        textValue: (row) => row.sourceTable,
        renderCell: (row) => <Identifier>{row.sourceTable}</Identifier>,
      },
      {
        id: 'targetTable',
        header: copy.tableColumns.targetTable,
        width: 240,
        textValue: (row) => row.targetTable,
        renderCell: (row) => <Identifier>{row.targetTable}</Identifier>,
      },
      {
        id: 'preflightConclusion',
        header: copy.tableColumns.preflightConclusion,
        width: 180,
        textValue: (row) => row.preflightConclusion ?? '',
        // The conclusion is never carried by colour alone: the literal is beside it.
        renderCell: (row) =>
          row.preflightConclusion === null ? null : (
            <ConclusionIndicator conclusion={row.preflightConclusion} />
          ),
      },
      {
        id: 'contract',
        header: copy.tableColumns.contract,
        width: 140,
        textValue: (row) =>
          row.contractVersion === null
            ? copy.contractMissing
            : copy.contractVersion(row.contractVersion),
        renderCell: (row) =>
          row.contractVersion === null ? (
            copy.contractMissing
          ) : (
            <Identifier>{copy.contractVersion(row.contractVersion)}</Identifier>
          ),
      },
      {
        id: 'columnCount',
        header: copy.tableColumns.columnCount,
        width: 120,
        textValue: (row) => String(row.contractColumnCount),
        renderCell: (row) => <Identifier>{row.contractColumnCount}</Identifier>,
      },
      {
        id: 'condition',
        header: copy.tableColumns.condition,
        width: 220,
        textValue: (row) =>
          [
            row.largeRecordTable ? messages.wizard.tables.largeRecordTable : '',
            row.prunedColumnCount > 0
              ? messages.wizard.tables.prunedColumnCount(row.prunedColumnCount)
              : '',
          ]
            .filter((entry) => entry !== '')
            .join(' '),
        renderCell: (row) => (
          <>
            {row.largeRecordTable ? (
              <Tag type="magenta">{messages.wizard.tables.largeRecordTable}</Tag>
            ) : null}
            {row.prunedColumnCount > 0 ? (
              <Tag type="cool-gray">
                {messages.wizard.tables.prunedColumnCount(row.prunedColumnCount)}
              </Tag>
            ) : null}
          </>
        ),
      },
    ],
    [copy],
  );

  const findingColumns = useMemo<readonly DbxTableColumn<UnresolvedFinding>[]>(
    () => [
      {
        id: 'sourceTable',
        header: copy.findingColumns.sourceTable,
        identifying: true,
        width: 240,
        textValue: (row) => row.sourceTable,
        renderCell: (row) => <Identifier>{row.sourceTable}</Identifier>,
      },
      {
        id: 'code',
        header: copy.findingColumns.code,
        width: 260,
        // `CONTEXT.md` words every finding code; the sentence in the detail column states
        // the exact fact behind the name.
        textValue: (row) => findingLabel(row.code),
        renderCell: (row) => <span>{findingLabel(row.code)}</span>,
      },
      {
        id: 'coordinate',
        header: copy.findingColumns.coordinate,
        width: 180,
        textValue: (row) => row.sourceColumn ?? copy.wholeTable,
        renderCell: (row) =>
          row.sourceColumn === null ? copy.wholeTable : <Identifier>{row.sourceColumn}</Identifier>,
      },
      {
        id: 'detail',
        header: copy.findingColumns.detail,
        width: 420,
        textValue: (row) => messages.wizard.tables.preflight.codes[row.code],
        renderCell: (row) => (
          <span>
            {messages.wizard.tables.preflight.codes[row.code]}{' '}
            <Identifier>{findingDetail(row.detail)}</Identifier>
          </span>
        ),
      },
    ],
    [copy],
  );

  if (summaryQuery.isPending) {
    return <LoadingState description={copy.loading} />;
  }

  if (summaryQuery.isError || summary === null) {
    return (
      <ErrorState
        title={copy.error.title}
        body={copy.error.body}
        onRetry={() => void summaryQuery.refetch()}
      />
    );
  }

  const contractColumnTotal = summary.tables.reduce(
    (total, table) => total + table.contractColumnCount,
    0,
  );
  const findings = summary.unresolvedFindings;

  return (
    <section className="dbx-confirm" aria-label={messages.wizard.stages.confirm}>
      <p className="dbx-wizard__lead">{copy.lead}</p>

      {start.isError ? (
        <InlineNotification
          kind="error"
          lowContrast
          hideCloseButton
          role="alert"
          title={copy.startFailed.title}
          subtitle={copy.startFailed.body}
        />
      ) : null}

      {/* 未解决的发现 first, and as a notification rather than a row in a list: nobody
          reaches this stage intending to read them, which is exactly why they lead. */}
      {findings.length > 0 ? (
        <InlineNotification
          kind="warning"
          lowContrast
          hideCloseButton
          role="alert"
          title={copy.findingsHeading}
          subtitle={copy.findingsNotice(formatCount(findings.length))}
        />
      ) : null}

      {/* Deliberately unlabelled, unlike the panels below it. The DbxTable inside is
          already a region named 未解决的发现 and it is the whole content of this panel, so
          naming the wrapper as well would publish two nested landmarks under one name and
          「未解决的发现」 would stop identifying anything in particular. The question does
          not arise for the other panels: 本次执行范围 contains a region of a different name
          (选中的表). The heading still names the section on screen. */}
      <section className="dbx-confirm__panel">
        <h3 className="dbx-confirm__heading">{copy.findingsHeading}</h3>
        <DbxTable
          label={copy.findingsHeading}
          columns={findingColumns}
          rows={findings}
          rowId={(row) => `${row.sourceTable}:${row.code}:${row.sourceColumn ?? ''}`}
          empty={{ title: copy.findingsEmpty, body: copy.findingsEmpty }}
          densityPreferenceKey="confirm-findings"
        />
      </section>

      <section className="dbx-confirm__panel" aria-label={copy.scopeHeading}>
        <h3 className="dbx-confirm__heading">{copy.scopeHeading}</h3>
        <dl className="dbx-confirm__facts">
          <div>
            <dt>{copy.sourceLabel}</dt>
            <dd>
              {summary.sourceConnectionName} <Identifier>{summary.sourceDatabase}</Identifier>
            </dd>
          </div>
          <div>
            <dt>{copy.targetLabel}</dt>
            <dd>
              {summary.targetConnectionName} <Identifier>{summary.targetSchema}</Identifier>
            </dd>
          </div>
          <div>
            <dt>{copy.tablesHeading}</dt>
            <dd>
              {formatCount(summary.tables.length)}
              {copy.unitLabel} · {copy.scopeKinds[summary.scopeKind]}
            </dd>
          </div>
        </dl>
        <p className="dbx-confirm__state">
          {copy.contractsSummary(
            formatCount(summary.tables.filter((table) => table.contractVersion !== null).length),
            formatCount(contractColumnTotal),
          )}
        </p>
        <DbxTable
          label={copy.tablesHeading}
          columns={tableColumns}
          rows={summary.tables}
          rowId={(row) => row.sourceTable}
          empty={{ title: copy.tablesHeading, body: copy.lead }}
          densityPreferenceKey="confirm-tables"
        />
      </section>

      <section className="dbx-confirm__panel" aria-label={copy.excludedHeading}>
        <h3 className="dbx-confirm__heading">{copy.excludedHeading}</h3>
        <p className="dbx-confirm__constraint">{copy.excludedConsequence}</p>
        {summary.excludedTables.length === 0 ? (
          <p className="dbx-confirm__state">{copy.excludedEmpty}</p>
        ) : (
          <ul className="dbx-confirm__excluded">
            {summary.excludedTables.map((name) => (
              <li key={name}>
                <Identifier>{name}</Identifier>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Gate 6, as a statement of the constraint plus what the platform can and cannot
          promise about it right now. */}
      <section className="dbx-confirm__panel" aria-label={copy.proofHeading}>
        <h3 className="dbx-confirm__heading">{copy.proofHeading}</h3>
        <p className="dbx-confirm__constraint">{copy.proofConstraint}</p>
        {summary.structuralProof.gaps.length === 0 ? (
          <p className="dbx-confirm__state">
            {copy.proofReady(formatCount(summary.structuralProof.provableTableCount))}
          </p>
        ) : (
          <>
            <h4 className="dbx-confirm__subheading">{copy.proofGapsHeading}</h4>
            <ul className="dbx-confirm__gaps">
              {summary.structuralProof.gaps.map((gap) => (
                <li key={`${gap.sourceTable}:${gap.gap}`}>
                  <Identifier>{gap.sourceTable}</Identifier> {copy.proofGaps[gap.gap]}
                </li>
              ))}
            </ul>
          </>
        )}
      </section>

      <WriteFreezePanel
        freeze={draft.writeFreeze}
        assembledAt={summary.assembledAt}
        onConfirm={(freeze: WriteFreezeDeclaration) => onPatch({ writeFreeze: freeze })}
        onRevoke={() => onPatch({ writeFreeze: null })}
      />

      <div className="dbx-confirm__start">
        <Button
          kind="danger"
          disabled={start.isPending}
          // Deliberately not disabled while a gate blocks. The reason is already on screen
          // — the shell prints it — and a dead button that explains nothing is worse than
          // one that refuses and says why. Pressing it while blocked does nothing at all,
          // which is what Gate 5's Playwright case asserts.
          onClick={() => {
            if (canStart) {
              setConfirming(true);
            }
          }}
        >
          {copy.startAction}
        </Button>
      </div>

      {confirming ? (
        <StartMigrationModal
          sourceDatabase={summary.sourceDatabase}
          onCancel={() => setConfirming(false)}
          onConfirm={() => {
            setConfirming(false);
            const freeze = draft.writeFreeze;
            // Unreachable while the gate holds, and checked anyway: the declaration is
            // what makes the start lawful, so nothing may send one without it.
            if (freeze === null) {
              return;
            }
            start.mutate(freeze, {
              onSuccess: (started) => navigate(paths.migrationRun(started.run.id)),
            });
          }}
        />
      ) : null}
    </section>
  );
}
