import type {
  ValidationConclusion,
  ValidationItemState,
  ValidationReport,
  ValidationReportRow,
} from '@/contract';

/**
 * What a 校验报告 adds up to (#40).
 *
 * A pure function of the report, kept out of the view so the counting rules can be
 * asserted rather than eyeballed. Three of them are load-bearing:
 *
 *  - **the three conclusions are three columns, never two and a caveat.** `PASS`, `FAIL`
 *    and `INCONCLUSIVE` are always listed, including when a count is zero: a review reads
 *    「INCONCLUSIVE 0」 as a fact, and a missing line as nothing at all.
 *  - **counting never consults a 校验处置.** `disposedRowCount` is beside the conclusion
 *    counts, never subtracted from them. A disposed `FAIL` is still counted as a `FAIL`.
 *  - **`NOT_APPLICABLE` and `NOT_RUN` are counted apart from each other and apart from
 *    failure**, at item level as well as at table level.
 */

/** The order the report states conclusions in: the three judgements first. */
export const reportConclusionOrder: readonly ValidationConclusion[] = [
  'PASS',
  'FAIL',
  'INCONCLUSIVE',
  'NOT_APPLICABLE',
  'NOT_RUN',
  'IN_FLIGHT',
];

/** The order item states are counted in. Same principle. */
export const reportItemStateOrder: readonly ValidationItemState[] = [
  'PASS',
  'FAIL',
  'INCONCLUSIVE',
  'NOT_APPLICABLE',
  'NOT_RUN',
];

export interface ConclusionCount {
  readonly conclusion: ValidationConclusion;
  readonly count: number;
}

export interface ItemStateCount {
  readonly state: ValidationItemState;
  readonly count: number;
}

export interface ValidationReportSummary {
  readonly conclusionCounts: readonly ConclusionCount[];
  readonly itemStateCounts: readonly ItemStateCount[];
  /** Tables whose 校验执行 has reached a conclusion of any kind. */
  readonly concludedRowCount: number;
  readonly rowCount: number;
  /** `FAIL` or `INCONCLUSIVE` with no 校验处置 recorded against it yet. */
  readonly openRowCount: number;
  readonly disposedRowCount: number;
}

/** Whether this table's technical conclusion is one a 校验处置 could be recorded about. */
export function isDisposable(row: ValidationReportRow): boolean {
  return row.conclusion === 'FAIL' || row.conclusion === 'INCONCLUSIVE';
}

export function summariseValidationReport(report: ValidationReport): ValidationReportSummary {
  const rows = report.rows;
  const conclusionCounts = reportConclusionOrder.map((conclusion) => ({
    conclusion,
    count: rows.filter((row) => row.conclusion === conclusion).length,
  }));

  const items = rows.flatMap((row) => row.execution?.items ?? []);
  const itemStateCounts = reportItemStateOrder.map((state) => ({
    state,
    count: items.filter((item) => item.state === state).length,
  }));

  return {
    conclusionCounts,
    itemStateCounts,
    concludedRowCount: rows.filter((row) => row.conclusion !== 'IN_FLIGHT').length,
    rowCount: rows.length,
    // Counted from the conclusion and the presence of a decision — never by asking the
    // decision what the conclusion was.
    openRowCount: rows.filter((row) => isDisposable(row) && row.disposition === null).length,
    disposedRowCount: rows.filter((row) => row.disposition !== null).length,
  };
}

/** The item states of one row, counted for its own cell. */
export function itemStateCountsOf(row: ValidationReportRow): readonly ItemStateCount[] {
  const items = row.execution?.items ?? [];
  return reportItemStateOrder
    .map((state) => ({ state, count: items.filter((item) => item.state === state).length }))
    .filter((entry) => entry.count > 0);
}
