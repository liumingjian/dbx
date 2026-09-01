import { useMemo, useState } from 'react';
import { Button, ContentSwitcher, Link as CarbonLink, Switch } from '@carbon/react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '@/api/http';
import { useRecordValidationDisposition, useValidationReport } from '@/api/validationReport';
import { EmptyState, ErrorState, LoadingState } from '@/components/ViewState';
import { ConclusionIndicator, migrationRunConclusion } from '@/conclusions';
import type { ValidationReport, ValidationReportRow } from '@/contract';
import { RemigrationPanel } from '@/features/remigration';
import {
  RecordDispositionModal,
  ValidationReportTable,
  formatValidationReport,
  isDisposable,
  summariseValidationReport,
} from '@/features/validation';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';
import { Identifier } from './Identifier';
import { Page } from './Page';

/**
 * 校验报告 — the wizard's sixth stage, seen from the 迁移运行 it concludes (#40).
 *
 * A route of its own rather than a wizard stage component, for the reason 运行监控 is one:
 * a 迁移草稿 「produces no migration run」, so nothing that has a report is a draft any more.
 *
 * The page is organised around one refusal, and everything else follows from it: **a
 * 校验处置 never rewrites a technical result.** Hence
 *
 *  1. the technical conclusions and the 校验处置 are in separate columns, separate
 *     sections, and separate visual vocabularies — an indicator for a judgement, a tag for
 *     a decision (Gate 8);
 *  2. 预检排除项 are a section of their own, because a table that never migrated has no
 *     technical result and listing it beside real ones would claim it was checked;
 *  3. while any 校验执行 is still running the page says so **instead of** presenting an
 *     aggregate verdict, since a half-finished conclusion submitted to a change review is
 *     read as a finished one.
 */

type RowFilter = 'all' | 'unresolved' | 'disposed';

const rowFilters: readonly RowFilter[] = ['all', 'unresolved', 'disposed'];

function filteredRows(report: ValidationReport, filter: RowFilter): readonly ValidationReportRow[] {
  if (filter === 'unresolved') {
    return report.rows.filter(isDisposable);
  }
  if (filter === 'disposed') {
    return report.rows.filter((row) => row.disposition !== null);
  }
  return report.rows;
}

/**
 * Hands the report to the operator as a file.
 *
 * A generated `Blob` and an anchor rather than a print stylesheet: what a change review
 * wants is something that can be attached, and what a browser can always produce without
 * a backend is a download. The copy path beside it exists because the commonest thing a
 * DBA actually does with a report is paste it into a ticket.
 */
function downloadReport(report: ValidationReport): void {
  const text = formatValidationReport(report);
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = messages.validation.export.fileNameOf(report.runId);
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function ValidationReportPage() {
  const { runId = '' } = useParams();
  const query = useValidationReport(runId);
  const record = useRecordValidationDisposition(runId);
  const [filter, setFilter] = useState<RowFilter>('all');
  const [disposing, setDisposing] = useState<ValidationReportRow | null>(null);
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');

  const report = query.data ?? null;
  const summary = useMemo(
    () => (report === null ? null : summariseValidationReport(report)),
    [report],
  );
  const rows = useMemo(
    () => (report === null ? [] : filteredRows(report, filter)),
    [report, filter],
  );

  if (query.isPending) {
    return (
      <Page title={messages.validation.title}>
        <LoadingState description={messages.validation.loading} />
      </Page>
    );
  }

  if (report === null || summary === null) {
    const missing = query.error instanceof ApiError && query.error.status === 404;
    return (
      <Page title={messages.validation.title}>
        {missing ? (
          <EmptyState
            title={messages.validation.notFound.title}
            body={messages.validation.notFound.body}
          />
        ) : (
          <ErrorState
            title={messages.validation.error.title}
            body={messages.validation.error.body}
            onRetry={() => void query.refetch()}
          />
        )}
      </Page>
    );
  }

  const copy = messages.validation;

  const copyReport = (): void => {
    const text = formatValidationReport(report);
    const clipboard = navigator.clipboard as Clipboard | undefined;
    if (clipboard === undefined) {
      setCopyState('failed');
      return;
    }
    void clipboard.writeText(text).then(
      () => setCopyState('copied'),
      () => setCopyState('failed'),
    );
  };

  return (
    <Page
      title={copy.title}
      lead={copy.lead}
      width="full"
      actions={
        <div className="dbx-validation__actions">
          <Button kind="tertiary" onClick={copyReport}>
            {copy.export.copyAction}
          </Button>
          <Button kind="tertiary" onClick={() => downloadReport(report)}>
            {copy.export.exportAction}
          </Button>
        </div>
      }
    >
      <section className="dbx-validation__identity" aria-label={copy.statusLabel}>
        <p>
          {copy.runLabel} <Identifier>{report.runId}</Identifier>
        </p>
        <p>
          <ConclusionIndicator
            conclusion={migrationRunConclusion(report.runStatus)}
            label={messages.tasks.runStatuses[report.runStatus]}
          />
        </p>
        <p>{copy.observedAt(formatTimestamp(report.observedAt))}</p>
        <p>
          <CarbonLink as={Link} to={paths.migrationRun(report.runId)}>
            {copy.backToRun}
          </CarbonLink>
        </p>
        {copyState === 'idle' ? null : (
          <p className="dbx-validation__notice" role="status">
            {copyState === 'copied' ? copy.export.copied : copy.export.copyFailed}
          </p>
        )}
      </section>

      {/*
        The 迁移范围 first, before any conclusion: a reader has to know which tables the
        conclusions below cover before the conclusions mean anything at all.
      */}
      <section className="dbx-validation__block" aria-label={copy.scope.heading}>
        <h3 className="dbx-validation__heading">{copy.scope.heading}</h3>
        <p>{copy.scope.databases(report.scope.sourceDatabase, report.scope.targetSchema)}</p>
        <p>
          {copy.scope.selected(report.scope.selectedTableCount)}
          {' · '}
          {copy.scope.excluded(report.scope.excludedTableCount)}
        </p>
        <p>{copy.scope.baseline(formatTimestamp(report.scope.baselineCapturedAt))}</p>
        <p>{copy.scope.covers}</p>
      </section>

      {report.validationInFlight ? (
        <section className="dbx-validation__block" aria-label={copy.inFlight.heading}>
          <h3 className="dbx-validation__heading">{copy.inFlight.heading}</h3>
          <p>{copy.inFlight.body(summary.concludedRowCount, summary.rowCount)}</p>
        </section>
      ) : (
        <section className="dbx-validation__block" aria-label={copy.concluded.heading}>
          <h3 className="dbx-validation__heading">{copy.concluded.heading}</h3>
          <p>{copy.concluded.body}</p>
        </section>
      )}

      {/*
        `PASS` / `FAIL` / `INCONCLUSIVE` are three lines, always, including at zero. A
        review reads 「INCONCLUSIVE 0」 as a fact and a missing line as nothing at all.
      */}
      <section className="dbx-validation__block" aria-label={copy.summary.heading}>
        <h3 className="dbx-validation__heading">{copy.summary.heading}</h3>
        <ul className="dbx-validation__counts">
          {summary.conclusionCounts.map((entry) => (
            <li key={entry.conclusion}>
              <ConclusionIndicator
                conclusion={entry.conclusion}
                label={copy.summary.countOf(
                  messages.conclusion.labels[entry.conclusion],
                  entry.count,
                )}
              />
            </li>
          ))}
        </ul>
        <p className="dbx-validation__notice">{copy.summary.note}</p>
        <h3 className="dbx-validation__heading">{copy.summary.itemsHeading}</h3>
        <ul className="dbx-validation__counts">
          {summary.itemStateCounts.map((entry) => (
            <li key={entry.state}>
              <ConclusionIndicator
                conclusion={entry.state}
                label={copy.summary.countOf(messages.conclusion.labels[entry.state], entry.count)}
              />
            </li>
          ))}
        </ul>
        <p className="dbx-validation__notice">{copy.summary.itemsNote}</p>
      </section>

      {/*
        No `aria-label` on this section: `DbxTable` already publishes the region under
        「逐表校验结论」, and two regions with one name is worse than none.
      */}
      <section className="dbx-validation__block">
        <h3 className="dbx-validation__heading">{copy.rows.heading}</h3>
        <div className="dbx-validation__filters">
          <ContentSwitcher
            aria-label={copy.filters.heading}
            selectedIndex={rowFilters.indexOf(filter)}
            onChange={({ name }) => setFilter((name as RowFilter | undefined) ?? 'all')}
          >
            <Switch name="all" text={copy.filters.all} />
            <Switch name="unresolved" text={copy.filters.unresolved} />
            <Switch name="disposed" text={copy.filters.disposed} />
          </ContentSwitcher>
        </div>
        <ValidationReportTable
          runId={report.runId}
          rows={rows}
          filterActive={filter !== 'all'}
          onRecordDisposition={(row) => {
            record.reset();
            setDisposing(row);
          }}
        />
      </section>

      {/*
        A section of its own, and deliberately *after* the conclusions: 「没迁」 is not a
        result, and a reader who has just read the results needs to be told which tables
        were never among them.
      */}
      <section className="dbx-validation__block" aria-label={copy.exclusions.heading}>
        <h3 className="dbx-validation__heading">{copy.exclusions.heading}</h3>
        <p>{copy.exclusions.statement}</p>
        {report.exclusions.length === 0 ? (
          <p>{copy.exclusions.none}</p>
        ) : (
          <ul className="dbx-validation__list">
            {report.exclusions.map((exclusion) => (
              <li key={exclusion.sourceTable}>
                <Identifier>{exclusion.sourceTable}</Identifier>{' '}
                <span className="dbx-validation__reason">
                  {copy.exclusions.reasons[exclusion.reason]}
                </span>{' '}
                {copy.exclusions.reasonDetails[exclusion.reason]}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="dbx-validation__block" aria-label={copy.disposition.heading}>
        <h3 className="dbx-validation__heading">{copy.disposition.heading}</h3>
        <p>{copy.disposition.statement}</p>
        {summary.disposedRowCount === 0 ? (
          <p>{copy.disposition.noneRecorded}</p>
        ) : (
          <ul className="dbx-validation__list">
            {report.rows
              .filter((row) => row.disposition !== null)
              .map((row) => (
                <li key={row.unitId}>
                  <Identifier>{row.sourceTable}</Identifier> {copy.disposition.operatorLabel}{' '}
                  {row.disposition?.accountableOperator}
                  {' · '}
                  {copy.disposition.recordedAt(
                    formatTimestamp(row.disposition?.recordedAt ?? report.observedAt),
                  )}
                  {' · '}
                  {copy.disposition.reasonLabel} {row.disposition?.reason}
                  {' · '}
                  {copy.disposition.acceptedChecks(
                    (row.disposition?.acceptedCheckIds ?? [])
                      .map((checkId) => copy.checks[checkId])
                      .join('、'),
                  )}
                  {' · '}
                  {/* The decision, carrying the result it did not change. */}
                  {copy.disposition.technicalResultUnchanged(
                    messages.conclusion.labels[row.conclusion],
                  )}
                </li>
              ))}
          </ul>
        )}
      </section>

      {/*
        The next action, after the conclusions and after the decisions: 重新迁移 creates a
        new 迁移运行 for the tables whose result is failed or undetermined (#41). It is
        last because it is what a reader does *having* read the report, and it is on this
        page because the report is where the candidates are established.
      */}
      <RemigrationPanel runId={report.runId} />

      {disposing === null ? null : (
        <RecordDispositionModal
          row={disposing}
          pending={record.isPending}
          failed={record.isError}
          onCancel={() => setDisposing(null)}
          onConfirm={(reason, accountableOperator) =>
            record.mutate(
              { unitId: disposing.unitId, reason, accountableOperator },
              { onSuccess: () => setDisposing(null) },
            )
          }
        />
      )}
    </Page>
  );
}
