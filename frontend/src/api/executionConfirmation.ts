import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  ExecutionConfirmationSummary,
  MigrationRun,
  MigrationTask,
  WriteFreezeDeclaration,
} from '@/contract';
import { getJson, postJson } from './http';
import { dbxQueryKey } from './queryKeys';
import { migrationDraftKeys } from './migrationDrafts';
import { migrationTaskKeys } from './migrationTasks';

/**
 * 执行确认 — the summary the last stage reads, and the one write that ends the wizard.
 *
 * The read is an aggregate on purpose (see the contract module): a summary assembled from
 * separate requests could show a 表写入契约 list that no longer matches the 未解决的发现
 * beside it, and starting a production migration against a self-contradictory screen is
 * precisely the failure this stage exists to prevent.
 *
 * The write is not a mutation of the draft. It creates a 迁移任务 and an immutable
 * 迁移运行 and consumes the draft, which is why nothing here writes the summary back into
 * the cache: everything the draft's queries were describing has stopped existing, so they
 * are invalidated rather than patched.
 */

export const executionConfirmationKeys = {
  summary: (draftId: string) => dbxQueryKey('migration-drafts', draftId, 'execution-confirmation'),
};

const draftPath = (draftId: string) => `/migration-drafts/${encodeURIComponent(draftId)}`;

/**
 * The 执行确认 summary.
 *
 * `enabled` for the same reason `useDraftTableConfigurations` has one: the summary is an
 * aggregate over the whole 迁移范围, and the stages before 执行确认 do not read it. Where
 * it is off the summary is `null`, which **blocks** — D22's polarity, unchanged.
 */
export function useExecutionConfirmationSummary(draftId: string, enabled = true) {
  return useQuery({
    queryKey: executionConfirmationKeys.summary(draftId),
    queryFn: () =>
      getJson<ExecutionConfirmationSummary>(`${draftPath(draftId)}/execution-confirmation`),
    enabled: enabled && draftId !== '',
  });
}

/** What starting produced: the approved 迁移任务 and its first 迁移运行. */
export interface StartedMigration {
  readonly task: MigrationTask;
  readonly run: MigrationRun;
}

/**
 * Starting the migration.
 *
 * Serialised under the same scope as stage three's writes, so a start can never be
 * interleaved with a mapping change against the same draft.
 */
export function useStartMigrationRun(draftId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    scope: { id: 'migration-draft' },
    mutationFn: (freeze: WriteFreezeDeclaration) =>
      postJson<StartedMigration>(`${draftPath(draftId)}/migration-runs`, freeze),
    onSuccess: () => {
      // The draft is gone and a task exists that did not before. Both lists are refetched
      // rather than edited in place: the run is a server record and the frontend does not
      // get to say what is in it.
      void queryClient.invalidateQueries({ queryKey: migrationDraftKeys.all() });
      void queryClient.invalidateQueries({ queryKey: migrationTaskKeys.all() });
    },
  });
}
