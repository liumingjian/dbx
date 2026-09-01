import { useQuery } from '@tanstack/react-query';
import type { MigrationRun, MigrationTask, SourceTableSummary } from '@/contract';
import { getJson } from './http';
import { dbxQueryKey } from './queryKeys';

/**
 * Reading migration tasks, their migration runs, and the tables of a source database.
 *
 * A task's runs are a history, not a mutable field: `CONTEXT.md` defines a migration run
 * as one immutable execution attempt and a rerun as a new run, so this module never offers
 * a way to change one.
 */

interface ListResponse<T> {
  readonly items: readonly T[];
}

export const migrationTaskKeys = {
  all: () => dbxQueryKey('migration-tasks'),
  one: (taskId: string) => dbxQueryKey('migration-tasks', taskId),
  runs: (taskId: string) => dbxQueryKey('migration-tasks', taskId, 'runs'),
};

export const sourceTableKeys = {
  ofDatabase: (sourceDatabase: string) => dbxQueryKey('source-tables', sourceDatabase),
};

export function useMigrationTasks() {
  return useQuery({
    queryKey: migrationTaskKeys.all(),
    queryFn: async () => (await getJson<ListResponse<MigrationTask>>('/migration-tasks')).items,
  });
}

export function useMigrationTask(taskId: string) {
  return useQuery({
    queryKey: migrationTaskKeys.one(taskId),
    queryFn: () => getJson<MigrationTask>(`/migration-tasks/${encodeURIComponent(taskId)}`),
  });
}

export function useMigrationRunsOfTask(taskId: string) {
  return useQuery({
    queryKey: migrationTaskKeys.runs(taskId),
    queryFn: async () =>
      (
        await getJson<ListResponse<MigrationRun>>(
          `/migration-tasks/${encodeURIComponent(taskId)}/runs`,
        )
      ).items,
  });
}

export function useSourceTables(sourceDatabase: string) {
  return useQuery({
    queryKey: sourceTableKeys.ofDatabase(sourceDatabase),
    queryFn: async () =>
      (
        await getJson<ListResponse<SourceTableSummary>>(
          `/source-tables?sourceDatabase=${encodeURIComponent(sourceDatabase)}`,
        )
      ).items,
  });
}
