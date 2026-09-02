import { useQuery } from '@tanstack/react-query';
import type {
  MigrationRun,
  MigrationTask,
  MigrationTaskList,
  SourceTableSummary,
} from '@/contract';
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

/**
 * The 迁移任务 list, and the instant the platform assembled it.
 *
 * The instant is part of the answer rather than something the page takes from
 * `Date.now()`: 「最近 7 天批准的」 is measured against the same clock the 批准时间 were
 * written on, which in the mocked world is the controllable clock and in the real one is
 * the backend's. Taking it from the browser worked only for as long as the two happened to
 * agree — the fixture is anchored at 2026-09-01, so a week later the window silently
 * emptied the list.
 */
export function useMigrationTasks() {
  return useQuery({
    queryKey: migrationTaskKeys.all(),
    queryFn: () => getJson<MigrationTaskList>('/migration-tasks'),
  });
}

export function useMigrationTask(taskId: string) {
  return useQuery({
    queryKey: migrationTaskKeys.one(taskId),
    queryFn: () => getJson<MigrationTask>(`/migration-tasks/${encodeURIComponent(taskId)}`),
    // A page may ask before it knows which task it is looking at; an empty identifier is
    // 「not yet」 rather than a request worth making.
    enabled: taskId !== '',
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
