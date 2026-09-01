import { useQuery } from '@tanstack/react-query';
import type { MigrationRunId, TableMigrationUnitEvidence, TableMigrationUnitId } from '@/contract';
import { getJson } from './http';
import { dbxQueryKey } from './queryKeys';

/**
 * Reading one 表迁移单元's 单表证据 (#39).
 *
 * This one *is* a query, and the contrast with `useRunProgress` is deliberate rather than
 * inconsistent. Run progress is a subscription to something that keeps changing, which is
 * why it goes through the `RunProgressSource` seam and never through a cache. A table's
 * evidence is the opposite kind of read: a question asked once, about facts that are
 * append-only, opened from a link and quoted into a ticket. A cached answer with an
 * explicit refetch is exactly right for it, and pretending otherwise would have put a
 * second transport decision behind a drawer.
 */

export const tableEvidenceKeys = {
  ofUnit: (runId: MigrationRunId, unitId: TableMigrationUnitId) =>
    dbxQueryKey('migration-runs', runId, 'table-migration-units', unitId, 'evidence'),
};

export function useTableMigrationUnitEvidence(runId: MigrationRunId, unitId: TableMigrationUnitId) {
  return useQuery({
    queryKey: tableEvidenceKeys.ofUnit(runId, unitId),
    queryFn: () =>
      getJson<TableMigrationUnitEvidence>(
        `/migration-runs/${encodeURIComponent(runId)}` +
          `/table-migration-units/${encodeURIComponent(unitId)}/evidence`,
      ),
    enabled: runId !== '' && unitId !== '',
  });
}
