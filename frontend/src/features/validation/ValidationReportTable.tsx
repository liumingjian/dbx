import { Button, Link as CarbonLink, Tag } from '@carbon/react';
import { Link } from 'react-router-dom';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import { ConclusionIndicator } from '@/conclusions';
import type { ValidationReportRow } from '@/contract';
import { unitOutcomeLabel } from '@/features/runs';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';
import { isDisposable, itemStateCountsOf } from './reportSummary';

/**
 * 逐表校验结论 — one row per table (#40).
 *
 * **Gate 8 lives in these columns.** The technical conclusion and the 校验处置 are two
 * separate columns, rendered with two different kinds of element, and neither ever
 * qualifies the other:
 *
 *  - the technical conclusion is a `ConclusionIndicator`, whose kind comes from the single
 *    conclusion→indicator mapping and never from a local conditional. It reads the
 *    execution and nothing else, so a disposed `FAIL` still shows `FAIL`;
 *  - the 校验处置 is a `Tag`. ADR-0014 reserves `Tag` for **categorisation and explicitly
 *    not for status**, which is exactly right here: a disposition is not a status of the
 *    table and not a conclusion about it. Giving it an indicator would put it in the same
 *    visual vocabulary as the judgement beside it, which is the one confusion this ticket
 *    exists to prevent.
 *
 * The 表迁移单元技术结果 is a third column again, because it is a third thing: a workflow
 * outcome. `COMPLETED_WITH_ACCEPTED_RISK` appearing there beside a `FAIL` conclusion is the
 * report working correctly, not a contradiction.
 */
interface ValidationReportTableProps {
  readonly runId: string;
  readonly rows: readonly ValidationReportRow[];
  readonly filterActive: boolean;
  readonly onRecordDisposition: (row: ValidationReportRow) => void;
}

export function ValidationReportTable({
  runId,
  rows,
  filterActive,
  onRecordDisposition,
}: ValidationReportTableProps) {
  const copy = messages.validation.rows;

  const columns: readonly DbxTableColumn<ValidationReportRow>[] = [
    {
      id: 'sourceTable',
      header: copy.columns.sourceTable,
      identifying: true,
      width: 220,
      textValue: (row) => row.sourceTable,
      renderCell: (row) => row.sourceTable,
    },
    {
      id: 'targetTable',
      header: copy.columns.targetTable,
      width: 200,
      textValue: (row) => row.targetTable,
      renderCell: (row) => row.targetTable,
    },
    {
      id: 'conclusion',
      header: copy.columns.conclusion,
      width: 220,
      textValue: (row) => messages.conclusion.labels[row.conclusion],
      renderCell: (row) => <ConclusionIndicator conclusion={row.conclusion} />,
    },
    {
      id: 'items',
      header: copy.columns.items,
      width: 260,
      textValue: (row) =>
        row.execution === null
          ? copy.noExecution
          : copy.itemsOf(
              itemStateCountsOf(row).map(
                (entry) => `${messages.conclusion.labels[entry.state]} ${entry.count}`,
              ),
            ),
      renderCell: (row) =>
        row.execution === null ? (
          copy.noExecution
        ) : (
          <span>
            {copy.itemsOf(
              itemStateCountsOf(row).map(
                (entry) => `${messages.conclusion.labels[entry.state]} ${entry.count}`,
              ),
            )}
          </span>
        ),
    },
    {
      id: 'disposition',
      header: copy.columns.disposition,
      width: 260,
      textValue: (row) =>
        row.disposition === null
          ? messages.validation.disposition.none
          : messages.validation.disposition.recorded,
      renderCell: (row) =>
        row.disposition === null ? (
          isDisposable(row) ? (
            <Button
              kind="ghost"
              size="sm"
              onClick={() => onRecordDisposition(row)}
              aria-label={messages.validation.disposition.openFor(row.sourceTable)}
            >
              {messages.validation.disposition.action}
            </Button>
          ) : (
            <span className="dbx-validation__muted">{messages.validation.disposition.none}</span>
          )
        ) : (
          <Tag type="outline" size="sm">
            {`${messages.validation.disposition.recorded} · ${messages.validation.disposition.operatorLabel} ${row.disposition.accountableOperator}`}
          </Tag>
        ),
    },
    {
      id: 'unitOutcome',
      header: copy.columns.unitOutcome,
      width: 240,
      textValue: (row) => unitOutcomeLabel(row.unitOutcome),
      renderCell: (row) => unitOutcomeLabel(row.unitOutcome),
    },
    {
      id: 'evidence',
      header: copy.columns.evidence,
      width: 140,
      textValue: () => copy.evidenceAction,
      renderCell: (row) => (
        // Straight to #39's route: a failed table in the report is one click from the
        // 错误事件 and 诊断 that explain it, and the address is the one a colleague can be
        // sent. Built through `paths`, so the scenario travels with it (D25).
        <CarbonLink as={Link} to={paths.tableMigrationUnit(runId, row.unitId)}>
          {copy.evidenceAction}
        </CarbonLink>
      ),
    },
  ];

  return (
    <DbxTable
      label={copy.listLabel}
      columns={columns}
      rows={rows}
      rowId={(row) => row.unitId}
      filterActive={filterActive}
      densityPreferenceKey="validation-report"
      empty={copy.empty}
    />
  );
}
