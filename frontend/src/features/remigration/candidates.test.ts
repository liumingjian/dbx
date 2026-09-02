import { describe, expect, it } from 'vitest';
import type {
  ValidationConclusion,
  ValidationReport,
  ValidationReportRow,
  TableMigrationOutcome,
} from '@/contract';
import {
  isRemigrationCandidate,
  remigrationCandidateRows,
  reportCoversEveryTableOnce,
} from './candidates';

/**
 * Which tables a 重新迁移 may be offered for (#41, lead decision D35).
 *
 * The rule decides what an operator is invited to run against a production database again,
 * so it is asserted here rather than read off the screen. Every case below is a way of
 * telling somebody that a table has a result it does not have.
 */

function rowOf(
  sourceTable: string,
  conclusion: ValidationConclusion,
  unitOutcome: TableMigrationOutcome | null = null,
): ValidationReportRow {
  return {
    unitId: `run-1-unit-${sourceTable}`,
    sourceTable,
    targetTable: sourceTable,
    execution: null,
    conclusion,
    unitPhase: 'TERMINAL',
    unitOutcome,
    disposition: null,
  };
}

function reportOf(rows: readonly ValidationReportRow[]): ValidationReport {
  return {
    runId: 'run-1',
    taskId: 'task-1',
    observedAt: '2026-09-01T00:00:00.000Z',
    runStatus: 'COMPLETED_WITH_FAILURES',
    scope: {
      sourceDatabase: 'orders',
      targetSchema: 'orders_migrated',
      selectedTableCount: rows.length,
      excludedTableCount: 1,
      baselineCapturedAt: '2026-09-01T00:00:00.000Z',
    },
    exclusions: [{ sourceTable: 'excluded_table', reason: 'PREFLIGHT_UNSUPPORTED' }],
    rows,
    validationInFlight: false,
  };
}

describe('remigration candidates', () => {
  it('offers a failed, an inconclusive and a never-run table', () => {
    // Three different facts, all of them 「this table has no result in its favour」.
    expect(isRemigrationCandidate(rowOf('a', 'FAIL', 'FAILED'))).toBe(true);
    expect(isRemigrationCandidate(rowOf('b', 'INCONCLUSIVE'))).toBe(true);
    // 「没跑」 is not a failure, and it is exactly why the table needs another run.
    expect(isRemigrationCandidate(rowOf('c', 'NOT_RUN', 'FAILED'))).toBe(true);
  });

  it('offers a table stopped by another table’s failure, whose result is undetermined', () => {
    // `CONTEXT.md`: 「Its own technical result is undetermined rather than failed, and it
    // is a candidate for re-migration.」 Included even while its own 校验执行 has not
    // concluded either way, because the reason it has not is not this table's doing.
    expect(isRemigrationCandidate(rowOf('d', 'IN_FLIGHT', 'BLOCKED_BY_BOX_FAILURE'))).toBe(true);
    expect(isRemigrationCandidate(rowOf('e', 'NOT_APPLICABLE', 'BLOCKED_BY_BOX_FAILURE'))).toBe(
      true,
    );
  });

  it('does not offer a table that passed, or one whose 校验执行 has not finished', () => {
    // A settled table put back at risk is the one harm this feature can do on its own.
    expect(isRemigrationCandidate(rowOf('f', 'PASS', 'SUCCEEDED'))).toBe(false);
    // 「还没跑完」 is not a conclusion, and offering it as one would ask an operator to act
    // on a half-finished 校验执行.
    expect(isRemigrationCandidate(rowOf('g', 'IN_FLIGHT'))).toBe(false);
    // A rule in the versioned 校验计划 said this check does not apply; that is not a gap.
    expect(isRemigrationCandidate(rowOf('h', 'NOT_APPLICABLE', 'SUCCEEDED'))).toBe(false);
  });

  it('does not offer a table that was never migrated, because it is not in the rows at all', () => {
    const report = reportOf([rowOf('a', 'FAIL', 'FAILED'), rowOf('f', 'PASS', 'SUCCEEDED')]);
    const candidates = remigrationCandidateRows(report);

    expect(candidates.map((row) => row.sourceTable)).toEqual(['a']);
    // The 预检排除项 is named by the report and is not among its rows, so no filter is
    // what keeps it out — the report's own separation is.
    expect(report.exclusions.map((exclusion) => exclusion.sourceTable)).toEqual(['excluded_table']);
    expect(candidates.map((row) => row.sourceTable)).not.toContain('excluded_table');
    expect(reportCoversEveryTableOnce(report)).toBe(true);
  });

  it('reports a table that is both a result and an exclusion as the contradiction it is', () => {
    // 「没迁」 and 「迁了但没过」 cannot both be true of one table. If they ever were, the
    // candidate list would be untrustworthy, so the condition is checkable rather than
    // assumed.
    expect(reportCoversEveryTableOnce(reportOf([rowOf('excluded_table', 'FAIL', 'FAILED')]))).toBe(
      false,
    );
  });

  it('keeps the report’s own order, so the list reads like the report above it', () => {
    const report = reportOf([
      rowOf('a', 'INCONCLUSIVE'),
      rowOf('b', 'PASS', 'SUCCEEDED'),
      rowOf('c', 'FAIL', 'FAILED'),
    ]);
    expect(remigrationCandidateRows(report).map((row) => row.sourceTable)).toEqual(['a', 'c']);
  });
});
