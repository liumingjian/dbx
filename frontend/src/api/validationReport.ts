import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  MigrationRunId,
  RecordValidationDispositionRequest,
  ValidationReport,
} from '@/contract';
import { getJson, postJson } from './http';
import { dbxQueryKey } from './queryKeys';
import { migrationTaskKeys } from './migrationTasks';

/**
 * Reading 校验报告, and recording a 校验处置 against it (#40).
 *
 * A query rather than the `RunProgressSource` seam, and the contrast with `useRunProgress`
 * is the same one #39 drew: run progress is a subscription to something that keeps
 * changing, while a 校验报告 is the artefact a DBA submits to a change review — asked for
 * once, read carefully, exported, quoted. A cached answer with an explicit refetch is
 * exactly right for it.
 *
 * The mutation is a **POST that appends a decision**. There is no mutation here that could
 * write a technical result, because there is no endpoint that accepts one.
 */

export const validationReportKeys = {
  ofRun: (runId: MigrationRunId) => dbxQueryKey('migration-runs', runId, 'validation-report'),
};

const runPath = (runId: MigrationRunId) => `/migration-runs/${encodeURIComponent(runId)}`;

export function useValidationReport(runId: MigrationRunId) {
  return useQuery({
    queryKey: validationReportKeys.ofRun(runId),
    queryFn: () => getJson<ValidationReport>(`${runPath(runId)}/validation-report`),
    enabled: runId !== '',
  });
}

export function useRecordValidationDisposition(runId: MigrationRunId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: RecordValidationDispositionRequest) =>
      postJson<ValidationReport>(`${runPath(runId)}/validation-dispositions`, request),
    onSuccess: (report) => {
      // A disposition closes a table's workflow, so the run's projected status — and every
      // list that names it — has moved. What it does **not** move is the report's technical
      // conclusions, which is why the fresh report is written straight into the cache.
      queryClient.setQueryData(validationReportKeys.ofRun(runId), report);
      void queryClient.invalidateQueries({ queryKey: migrationTaskKeys.all() });
    },
  });
}
