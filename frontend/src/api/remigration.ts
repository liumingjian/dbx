import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  MigrationRun,
  MigrationRunId,
  MigrationTask,
  RemigrationOffer,
  StartRemigrationRequest,
} from '@/contract';
import { getJson, postJson } from './http';
import { dbxQueryKey } from './queryKeys';
import { migrationTaskKeys } from './migrationTasks';

/**
 * Reading what a 重新迁移 could cover, and starting one (#41).
 *
 * The mutation returns the **new** 迁移运行 rather than an updated old one, which is the
 * whole point: 「a rerun is a new migration run」, so there is nothing here that could
 * write into the run it was started from, and the caller's next move is a navigation to a
 * record that did not exist a moment ago.
 *
 * On success the task list and the task's run history are invalidated, because both have
 * genuinely moved — one more attempt, a new 最近运行状态. The 校验报告 of the earlier run
 * is deliberately **not** invalidated: nothing about it changed, and refetching it would
 * suggest otherwise.
 */

export const remigrationKeys = {
  ofRun: (runId: MigrationRunId) => dbxQueryKey('migration-runs', runId, 'remigration'),
};

export interface StartedRemigration {
  readonly task: MigrationTask;
  readonly run: MigrationRun;
}

export function useRemigrationOffer(runId: MigrationRunId) {
  return useQuery({
    queryKey: remigrationKeys.ofRun(runId),
    queryFn: () =>
      getJson<RemigrationOffer>(`/migration-runs/${encodeURIComponent(runId)}/remigration`),
    enabled: runId !== '',
  });
}

export function useStartRemigration(runId: MigrationRunId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: StartRemigrationRequest) =>
      postJson<StartedRemigration>(
        `/migration-runs/${encodeURIComponent(runId)}/remigrations`,
        request,
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: migrationTaskKeys.all() });
      void queryClient.invalidateQueries({ queryKey: remigrationKeys.ofRun(runId) });
    },
  });
}
