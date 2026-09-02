import type { DatabasePair, MigrationRunStatus, MigrationTask } from '@/contract';
import { formatDialect } from '@/format/display';

/**
 * Filtering the migration task list (user story 20).
 *
 * Kept as plain functions over the contract types rather than inside the page, because the
 * one thing a filter must never do is quietly change what the list means — and that is far
 * easier to check here than through a rendered table.
 */

export type ApprovedWithin = 'ANY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'LAST_90_DAYS';

export interface MigrationTaskFilter {
  /** The task's latest migration run status, or `ANY`. */
  readonly status: MigrationRunStatus | 'ANY';
  /** A 数据库对 label, or `ANY`. v1 registers exactly one pair (ADR-0008). */
  readonly databasePair: string | 'ANY';
  readonly approvedWithin: ApprovedWithin;
}

export const noMigrationTaskFilter: MigrationTaskFilter = {
  status: 'ANY',
  databasePair: 'ANY',
  approvedWithin: 'ANY',
};

const windowDays: Readonly<Record<ApprovedWithin, number | null>> = {
  ANY: null,
  LAST_7_DAYS: 7,
  LAST_30_DAYS: 30,
  LAST_90_DAYS: 90,
};

/**
 * A 数据库对 is a directed, explicitly registered relationship (`CONTEXT.md`), so it reads
 * as one label rather than as two independent dialect columns.
 */
export function databasePairLabel(pair: DatabasePair): string {
  return `${formatDialect(pair.sourceDialect)} → ${formatDialect(pair.targetDialect)}`;
}

export function isMigrationTaskFilterActive(filter: MigrationTaskFilter): boolean {
  return (
    filter.status !== 'ANY' || filter.databasePair !== 'ANY' || filter.approvedWithin !== 'ANY'
  );
}

export function filterMigrationTasks(
  tasks: readonly MigrationTask[],
  filter: MigrationTaskFilter,
  now: number,
): readonly MigrationTask[] {
  const days = windowDays[filter.approvedWithin];
  const earliest = days === null ? null : now - days * 24 * 60 * 60 * 1000;
  return tasks.filter((task) => {
    if (filter.status !== 'ANY' && task.latestRunStatus !== filter.status) return false;
    if (
      filter.databasePair !== 'ANY' &&
      databasePairLabel(task.databasePair) !== filter.databasePair
    )
      return false;
    if (earliest !== null && Date.parse(task.approvedAt) < earliest) return false;
    return true;
  });
}

/** The database pairs actually present, so the filter never offers an empty result. */
export function databasePairsOf(tasks: readonly MigrationTask[]): readonly string[] {
  return [...new Set(tasks.map((task) => databasePairLabel(task.databasePair)))].sort();
}
