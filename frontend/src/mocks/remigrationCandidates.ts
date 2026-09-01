import type { ValidationReport, ValidationReportRow } from '@/contract';

/**
 * Which rows of a 校验报告 the **mock platform** would offer for a 重新迁移.
 *
 * A deliberate second copy of the rule the browser applies
 * (`src/features/remigration/candidates.ts`), for the same reason `src/routes/paths.ts`
 * keeps its own copy of the scenario parameter names: the mock stands in for a server, and
 * a server that computed its answer by calling the client's function proves nothing. With
 * one shared function, `store.test.ts`'s 「never offers a 预检排除项」 asserted only that a
 * function agrees with itself.
 *
 * The rule itself is D35's, restated here so this file can be read on its own:
 *
 *  - `FAIL`, `INCONCLUSIVE` and `NOT_RUN` are candidates — 「没跑」 is not a failure, and a
 *    table whose write never completed is exactly the one that needs another run;
 *  - a unit stopped alongside a failure (`BLOCKED_BY_BOX_FAILURE`) is a candidate: its own
 *    technical result is undetermined rather than failed;
 *  - `PASS` is not, and a 预检排除项 never appears here at all, because it was never in
 *    `rows` — it never migrated and has no technical conclusion to re-migrate against.
 *
 * `remigrationCandidates.test.ts` asserts the two copies agree over every combination the
 * contract allows, so the duplication cannot drift.
 */
export function isServerRemigrationCandidate(row: ValidationReportRow): boolean {
  if (row.unitOutcome === 'BLOCKED_BY_BOX_FAILURE') {
    return true;
  }
  switch (row.conclusion) {
    case 'FAIL':
    case 'INCONCLUSIVE':
    case 'NOT_RUN':
      return true;
    default:
      return false;
  }
}

export function serverRemigrationCandidateRows(
  report: ValidationReport,
): readonly ValidationReportRow[] {
  return report.rows.filter(isServerRemigrationCandidate);
}
