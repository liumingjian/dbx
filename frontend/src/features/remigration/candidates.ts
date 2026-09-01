import type { ValidationReport, ValidationReportRow } from '@/contract';

/**
 * Which tables of a 迁移运行 a 重新迁移 may be offered for (#41, lead decision D35).
 *
 * A pure function of the 校验报告, so the rule is written once and can be asserted rather
 * than eyeballed. Three of its edges are load-bearing, and each of them is a different way
 * of getting the same thing wrong — telling an operator that a table has a result it does
 * not have:
 *
 *  - **`PASS` is not a candidate.** The table has a technical conclusion in its favour.
 *    Re-migrating it would put a settled table back at risk for no stated reason.
 *  - **`NOT_RUN` is a candidate, and 「没跑」 is not a failure.** A table whose write never
 *    completed never reached a 校验执行 at all; that is precisely why it needs another run.
 *  - **A unit stopped alongside a failure is a candidate.** `CONTEXT.md`: 「Its own
 *    technical result is undetermined rather than failed, and it is a candidate for
 *    re-migration.」 It is included even where its 校验执行 has not concluded either way.
 *
 * And one exclusion that is not an edge case at all but the point of the whole partition:
 * **`report.exclusions` are never candidates.** A 预检排除项 never migrated and has no
 * technical conclusion, so offering it here would present 「没迁」 as 「迁了但没过」. It is
 * not filtered out — it was never in `rows` to begin with — which is the property this
 * module depends on and which `reportCoversEveryTableOnce` below states out loud.
 */

/** Whether this table's own result is undetermined or failed, and so may be migrated again. */
export function isRemigrationCandidate(row: ValidationReportRow): boolean {
  if (row.unitOutcome === 'BLOCKED_BY_BOX_FAILURE') {
    return true;
  }
  return row.conclusion === 'FAIL' || row.conclusion === 'INCONCLUSIVE' || row.conclusion === 'NOT_RUN';
}

/** The rows of the report a 重新迁移 may cover, in the report's own order. */
export function remigrationCandidateRows(
  report: ValidationReport,
): readonly ValidationReportRow[] {
  return report.rows.filter(isRemigrationCandidate);
}

/**
 * Whether the report keeps 「没迁」 and 「迁了但没过」 apart, as this partition assumes.
 *
 * A table named in both `rows` and `exclusions` would be a table with a technical
 * conclusion *and* no migration, which is not a state that exists. Asserted rather than
 * assumed, because the candidate list is only trustworthy while it holds.
 */
export function reportCoversEveryTableOnce(report: ValidationReport): boolean {
  const excluded = new Set(report.exclusions.map((exclusion) => exclusion.sourceTable));
  return report.rows.every((row) => !excluded.has(row.sourceTable));
}
