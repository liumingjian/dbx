import type { MigrationRunId, RunProgressSnapshot } from '@/contract';
import { getJson } from './http';

/**
 * `RunProgressSource` — the seam between 运行监控 and however a 迁移运行's progress arrives
 * (ADR-0016, ADR-0007).
 *
 * ADR-0007 lists the live-update transport as **undecided** and forbids inferring it from
 * the prototype. ADR-0016 states the consequence: 「the frontend depends only on a
 * `RunProgressSource` abstraction … the mock drives it from the controllable clock; the
 * default real implementation polls; server-sent events or WebSocket remain substitutable
 * behind the same interface」. So this module defines the seam and declines to choose the
 * mechanism.
 *
 * The interface is a **subscription that delivers snapshots**, which is the shape all three
 * candidates share: polling produces them on a timer, SSE and WebSocket produce them when
 * the server pushes. Anything narrower — a `fetchProgress()` function, or a `pollInterval`
 * option — would have decided the question by implication:
 *
 *  - `createPollingRunProgressSource` is the default real implementation, and the one the
 *    application uses today. It repeats one plain GET and stops once the run has ended.
 *  - `createChannelRunProgressSource` is the substitution point. An SSE or WebSocket
 *    transport supplies an `open` function and nothing else changes: no view, no hook and
 *    no test knows which one it is talking to.
 *  - the mock is *behind* the seam rather than a third implementation of it. Its time
 *    dimension comes from the controllable clock inside the mock store, so the same
 *    polling source reads a world that moves, and a scenario can replay three hours in
 *    tens of seconds without the frontend knowing anything about it.
 */

export interface RunProgressSubscriber {
  readonly onSnapshot: (snapshot: RunProgressSnapshot) => void;
  /** Delivery failed. The subscription stays open: one failed observation is not the end. */
  readonly onError: (error: unknown) => void;
}

export interface RunProgressSubscription {
  /**
   * Ask for an observation now.
   *
   * Used after the operator has done something — requested a 取消 — and by a retry. It is
   * a hint rather than a guarantee: a push transport may already be delivering.
   */
  readonly refresh: () => void;
  readonly close: () => void;
}

export interface RunProgressSource {
  readonly subscribe: (
    runId: MigrationRunId,
    subscriber: RunProgressSubscriber,
  ) => RunProgressSubscription;
}

/**
 * How often the polling implementation asks, in **real** milliseconds.
 *
 * Real rather than mock time on purpose: this is a property of the transport, not of the
 * migration. The mock's observation quantum is coarser than this interval, which is why
 * consecutive polls legitimately return the same observation — see `mocks/fixtures/runProgress.ts`.
 */
export const DEFAULT_POLL_INTERVAL_MS = 2_000;

export interface PollingRunProgressSourceOptions {
  readonly read?: (runId: MigrationRunId) => Promise<RunProgressSnapshot>;
  readonly intervalMs?: number;
  /** Injected in tests so nothing has to wait for a real timer. */
  readonly schedule?: (callback: () => void, delayMs: number) => () => void;
}

const defaultSchedule = (callback: () => void, delayMs: number): (() => void) => {
  const handle = setTimeout(callback, delayMs);
  return () => clearTimeout(handle);
};

export function readRunProgress(runId: MigrationRunId): Promise<RunProgressSnapshot> {
  return getJson<RunProgressSnapshot>(`/migration-runs/${encodeURIComponent(runId)}/progress`);
}

/**
 * The default real implementation: repeat one GET until the run has ended.
 *
 * Stopping at the end is the only cleverness here, and it is a fact about the domain rather
 * than an optimisation: a 迁移运行 is one immutable execution attempt, so once it has an
 * end time there is nothing further to observe. A 卡死 run has **no** end time — DBX
 * preserves it for diagnosis — so polling continues, which is correct: the operator may
 * still cancel it.
 */
export function createPollingRunProgressSource({
  read = readRunProgress,
  intervalMs = DEFAULT_POLL_INTERVAL_MS,
  schedule = defaultSchedule,
}: PollingRunProgressSourceOptions = {}): RunProgressSource {
  return {
    subscribe(runId, subscriber) {
      let closed = false;
      let cancelTimer: (() => void) | null = null;
      let inFlight = false;

      const later = (): void => {
        if (closed) return;
        cancelTimer = schedule(() => void observe(), intervalMs);
      };

      const observe = async (): Promise<void> => {
        // One request at a time: a slow answer must not queue a second one behind it.
        if (closed || inFlight) return;
        inFlight = true;
        try {
          const snapshot = await read(runId);
          if (closed) return;
          subscriber.onSnapshot(snapshot);
          if (snapshot.run.endedAt !== null) {
            closed = true;
            return;
          }
        } catch (error) {
          if (!closed) subscriber.onError(error);
        } finally {
          inFlight = false;
        }
        later();
      };

      void observe();

      return {
        refresh() {
          cancelTimer?.();
          cancelTimer = null;
          void observe();
        },
        close() {
          closed = true;
          cancelTimer?.();
          cancelTimer = null;
        },
      };
    },
  };
}

/** What a push transport hands back when it opens a stream. */
export interface RunProgressChannel {
  readonly close: () => void;
  /** Optional: a push transport that can be asked for an immediate observation. */
  readonly refresh?: () => void;
}

export interface ChannelRunProgressSourceOptions {
  readonly open: (
    runId: MigrationRunId,
    subscriber: RunProgressSubscriber,
  ) => RunProgressChannel;
}

/**
 * The substitution point for a push transport.
 *
 * A server-sent-events implementation would supply an `open` that constructs an
 * `EventSource` and calls `onSnapshot` per message; a WebSocket implementation would do the
 * same with a socket. **DBX does not choose between them here** — ADR-0007 left the
 * question open and this ticket defines the seam rather than answering it. What this
 * function proves is that the seam is real: everything above it is written against
 * `RunProgressSource`, so the swap is one line in `src/api/runProgress.ts`.
 */
export function createChannelRunProgressSource({
  open,
}: ChannelRunProgressSourceOptions): RunProgressSource {
  return {
    subscribe(runId, subscriber) {
      const channel = open(runId, subscriber);
      return {
        refresh() {
          channel.refresh?.();
        },
        close() {
          channel.close();
        },
      };
    },
  };
}

/**
 * The source 运行监控 uses.
 *
 * Named here, once, so that adopting a push transport later is a change to this constant
 * and to nothing else in the product.
 */
export const defaultRunProgressSource: RunProgressSource = createPollingRunProgressSource();
