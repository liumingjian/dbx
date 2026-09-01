import type { ValidationReport } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { unitOutcomeLabel } from '@/features/runs';
import { summariseValidationReport } from './reportSummary';

/**
 * The 校验报告 as plain text, for copying into a change review or attaching to a ticket.
 *
 * Plain text rather than a rendered document on purpose: what a change reviewer receives
 * has to survive being pasted into a ticket, a mail client and a wiki without losing the
 * distinctions the screen makes. So every section names itself, the technical conclusion
 * and the 校验处置 are printed as **separate labelled fields**, and the exclusions are a
 * section of their own — the same three separations the screen keeps, in a form that
 * cannot be re-styled into one.
 *
 * The wording comes from `src/messages`, so the export and the screen cannot drift apart.
 */
export function formatValidationReport(report: ValidationReport): string {
  const copy = messages.validation;
  const summary = summariseValidationReport(report);
  const lines: string[] = [];

  lines.push(copy.title);
  lines.push(`${copy.runLabel} ${report.runId}`);
  lines.push(copy.observedAt(formatTimestamp(report.observedAt)));
  lines.push(`${copy.statusLabel} ${messages.tasks.runStatuses[report.runStatus]}`);
  lines.push('');

  lines.push(copy.scope.heading);
  lines.push(`  ${copy.scope.databases(report.scope.sourceDatabase, report.scope.targetSchema)}`);
  lines.push(`  ${copy.scope.selected(report.scope.selectedTableCount)}`);
  lines.push(`  ${copy.scope.excluded(report.scope.excludedTableCount)}`);
  lines.push(`  ${copy.scope.baseline(formatTimestamp(report.scope.baselineCapturedAt))}`);
  lines.push(`  ${copy.scope.covers}`);
  lines.push('');

  // Said before any conclusion is read, not after: a reviewer must not reach the counts
  // believing they are final when they are not.
  if (report.validationInFlight) {
    lines.push(copy.inFlight.heading);
    lines.push(`  ${copy.inFlight.body(summary.concludedRowCount, summary.rowCount)}`);
    lines.push('');
  }

  lines.push(copy.summary.heading);
  for (const entry of summary.conclusionCounts) {
    lines.push(`  ${messages.conclusion.labels[entry.conclusion]} ${entry.count}`);
  }
  lines.push(`  ${copy.summary.note}`);
  lines.push('');

  lines.push(copy.summary.itemsHeading);
  for (const entry of summary.itemStateCounts) {
    lines.push(`  ${messages.conclusion.labels[entry.state]} ${entry.count}`);
  }
  lines.push(`  ${copy.summary.itemsNote}`);
  lines.push('');

  lines.push(copy.rows.heading);
  for (const row of report.rows) {
    // The technical conclusion and the disposition are two labelled fields on one line.
    // Neither can be read as qualifying the other, and no row ever prints a conclusion the
    // execution did not reach.
    lines.push(
      [
        `  ${row.sourceTable}`,
        `${copy.rows.columns.conclusion} ${messages.conclusion.labels[row.conclusion]}`,
        `${copy.rows.columns.disposition} ${
          row.disposition === null ? copy.disposition.none : copy.disposition.recorded
        }`,
        `${copy.rows.columns.unitOutcome} ${unitOutcomeLabel(row.unitOutcome)}`,
      ].join(' · '),
    );
    for (const item of row.execution?.items ?? []) {
      lines.push(
        `    ${copy.checks[item.checkId]} ${messages.conclusion.labels[item.state]}` +
          (item.detail === null ? '' : ` · ${item.detail}`),
      );
    }
  }
  lines.push('');

  lines.push(copy.exclusions.heading);
  lines.push(`  ${copy.exclusions.statement}`);
  if (report.exclusions.length === 0) {
    lines.push(`  ${copy.exclusions.none}`);
  }
  for (const exclusion of report.exclusions) {
    lines.push(
      `  ${exclusion.sourceTable} · ${copy.exclusions.reasons[exclusion.reason]}` +
        ` · ${copy.exclusions.reasonDetails[exclusion.reason]}`,
    );
  }
  lines.push('');

  lines.push(copy.disposition.heading);
  lines.push(`  ${copy.disposition.statement}`);
  const disposed = report.rows.filter((row) => row.disposition !== null);
  if (disposed.length === 0) {
    lines.push(`  ${copy.disposition.noneRecorded}`);
  }
  for (const row of disposed) {
    const disposition = row.disposition;
    if (disposition === null) {
      continue;
    }
    lines.push(
      [
        `  ${row.sourceTable}`,
        `${copy.disposition.operatorLabel} ${disposition.accountableOperator}`,
        copy.disposition.recordedAt(formatTimestamp(disposition.recordedAt)),
        `${copy.disposition.reasonLabel} ${disposition.reason}`,
        copy.disposition.acceptedChecks(
          disposition.acceptedCheckIds.map((checkId) => copy.checks[checkId]).join('、'),
        ),
        // Printed on the disposition's own line, so the record of the decision carries the
        // result it did **not** change.
        copy.disposition.technicalResultUnchanged(messages.conclusion.labels[row.conclusion]),
      ].join(' · '),
    );
  }

  return `${lines.join('\n')}\n`;
}
