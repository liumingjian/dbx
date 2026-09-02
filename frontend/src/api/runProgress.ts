import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { MigrationRunId, RunCancellationConsequences, RunProgressSnapshot } from '@/contract';
import { getJson, postJson } from './http';
import { currentScenarioKey, dbxQueryKey } from './queryKeys';
import { migrationTaskKeys } from './migrationTasks';
import { defaultRunProgressSource, type RunProgressSource } from './runProgressSource';

/**
 * Reading a 迁移运行 as it happens, and asking for it to stop.
 *
 * The read does **not** go through TanStack Query, and that is deliberate rather than an
 * oversight. A query is a cached answer to a question asked once; run progress is a
 * subscription to something that keeps changing, and which transport delivers it is
 * undecided (ADR-0007). Routing it through a cache would have quietly reintroduced the
 * decision — a `refetchInterval` is a polling transport by another name — and would have
 * made a push implementation the odd one out. So the hook holds the latest snapshot and
 * the `RunProgressSource` seam decides how it arrives.
 *
 * The 取消 *is* a mutation and is written as one: it is a request the operator makes once.
 */

export const runProgressKeys = {
  cancellation: (runId: MigrationRunId) => dbxQueryKey('migration-runs', runId, 'cancellation'),
};

const runPath = (runId: MigrationRunId) => `/migration-runs/${encodeURIComponent(runId)}`;

export interface RunProgressState {
  /** The latest observation, or null until the first one arrives. */
  readonly snapshot: RunProgressSnapshot | null;
  /** The most recent delivery failure. Cleared as soon as an observation gets through. */
  readonly error: unknown;
  /** True while nothing has arrived yet and nothing has failed. */
  readonly pending: boolean;
  readonly refresh: () => void;
}

export function useRunProgress(
  runId: MigrationRunId,
  source: RunProgressSource = defaultRunProgressSource,
): RunProgressState {
  const [snapshot, setSnapshot] = useState<RunProgressSnapshot | null>(null);
  const [error, setError] = useState<unknown>(null);
  const subscription = useRef<{ readonly refresh: () => void } | null>(null);
  /**
   * The scenario is part of what identifies a subscription, not decoration.
   *
   * `run-monitored` is the 迁移运行 id in **every** scenario (D22), so a scenario change
   * that does not reload the page leaves `runId` and `source` both identical and the
   * previous world's subscription attached. And `runProgressSource` latches closed the
   * moment a snapshot carries an `endedAt`, so the page would then hold the previous
   * scenario's final observation for ever. #42's coverage matrix walks scenarios inside
   * one session, which is exactly the walk that would break.
   */
  const scenarioKey = currentScenarioKey();

  useEffect(() => {
    setSnapshot(null);
    setError(null);
    const active = source.subscribe(runId, {
      onSnapshot: (next) => {
        setSnapshot(next);
        setError(null);
      },
      onError: (failure) => setError(failure),
    });
    subscription.current = active;
    return () => {
      subscription.current = null;
      active.close();
    };
  }, [runId, source, scenarioKey]);

  const refresh = useCallback(() => subscription.current?.refresh(), []);

  return { snapshot, error, pending: snapshot === null && error === null, refresh };
}

/**
 * What a 取消 would stop, read from the platform before the operator commits to it.
 *
 * #30 requires a destructive action to state its consequences *before* it happens, and the
 * consequences of stopping a run are a fact about the run at this instant — how many tables
 * are still in flight — rather than a sentence someone typed into a dialog.
 */
export function useRunCancellationConsequences(runId: MigrationRunId, enabled: boolean) {
  return useQuery({
    queryKey: runProgressKeys.cancellation(runId),
    queryFn: () => getJson<RunCancellationConsequences>(`${runPath(runId)}/cancellation`),
    enabled: enabled && runId !== '',
    // A dialog that opens on a stale count would be describing a different run.
    staleTime: 0,
  });
}

export function useRequestRunCancellation(runId: MigrationRunId) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => postJson<RunProgressSnapshot>(`${runPath(runId)}/cancellation`),
    onSuccess: () => {
      // The run's projected status has changed, so every list that names it is stale.
      void queryClient.invalidateQueries({ queryKey: migrationTaskKeys.all() });
      void queryClient.invalidateQueries({ queryKey: runProgressKeys.cancellation(runId) });
    },
  });
}
