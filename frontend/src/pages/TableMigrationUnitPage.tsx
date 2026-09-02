import { useCallback } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/http';
import { useTableMigrationUnitEvidence } from '@/api/tableEvidence';
import { TableEvidenceDrawer } from '@/features/runs';
import { paths } from '@/routes/paths';

/**
 * `/runs/:runId/tables/:unitId` — one table's evidence, as a drawer over 运行监控 (#39).
 *
 * This is a **child route of the run**, which is what makes the drawer's URL real rather
 * than decorative. The run page stays mounted and rendered underneath; opening a row
 * pushes this address, and visiting or reloading it lands on the same screen a colleague
 * was looking at when they pasted it into a ticket. Closing navigates to the run's own
 * URL, so the address bar and the screen never disagree — the prototype's `?variant=A`, a
 * drawer whose state lived in a component, is not carried forward.
 */
export function TableMigrationUnitPage() {
  const { runId = '', unitId = '' } = useParams();
  const navigate = useNavigate();
  const evidence = useTableMigrationUnitEvidence(runId, unitId);

  const close = useCallback(() => {
    // A navigation rather than a state change: the drawer *is* a URL, so closing it is a
    // move to the run's URL. Pushing rather than replacing keeps the operator's own
    // history honest — going back returns to the evidence they were reading, which is
    // also the only behaviour that works when the drawer was entered from a link.
    void navigate(paths.migrationRun(runId));
  }, [navigate, runId]);

  // A 表迁移单元 the run does not contain is not a failed read: a retry cannot help, and
  // saying so is more use than a spinner that will never resolve.
  const missing = evidence.error instanceof ApiError && evidence.error.status === 404;

  return (
    <TableEvidenceDrawer
      evidence={evidence.data ?? null}
      pending={evidence.isPending}
      failed={evidence.isError}
      missing={missing}
      onRetry={() => void evidence.refetch()}
      onClose={close}
    />
  );
}
