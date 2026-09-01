import { describe, expect, it, vi } from 'vitest';
import type { RunProgressSnapshot } from '@/contract';
import {
  createChannelRunProgressSource,
  createPollingRunProgressSource,
  type RunProgressSource,
  type RunProgressSubscriber,
} from './runProgressSource';

/**
 * The `RunProgressSource` seam (ADR-0016 / ADR-0007).
 *
 * What is worth pinning here is not that polling polls — it is that **the seam is real**:
 * the default polling implementation and a push implementation are interchangeable through
 * one interface, so ADR-0007's undecided transport stays undecided. If a future SSE or
 * WebSocket implementation cannot satisfy these same expectations, the seam has leaked.
 */

function snapshotAt(observedAt: string, endedAt: string | null = null): RunProgressSnapshot {
  return {
    observedAt,
    run: { endedAt } as RunProgressSnapshot['run'],
    units: [],
    unitTotalCount: 0,
    stuck: null,
    events: [],
    eventTotalCount: 0,
    log: [],
    logTotalCount: 0,
  };
}

/** A scheduler under the test's control, so nothing waits on a real timer. */
function manualScheduler() {
  const pending: (() => void)[] = [];
  return {
    schedule: (callback: () => void) => {
      pending.push(callback);
      return () => {
        const index = pending.indexOf(callback);
        if (index >= 0) pending.splice(index, 1);
      };
    },
    async fire(): Promise<void> {
      const next = pending.shift();
      next?.();
      await Promise.resolve();
      await Promise.resolve();
    },
    get depth() {
      return pending.length;
    },
  };
}

function collector(): RunProgressSubscriber & { readonly seen: RunProgressSnapshot[] } {
  const seen: RunProgressSnapshot[] = [];
  return {
    seen,
    onSnapshot: (snapshot) => void seen.push(snapshot),
    onError: () => {},
  };
}

describe('the polling implementation', () => {
  it('delivers an observation immediately and keeps asking while the run is unfinished', async () => {
    const scheduler = manualScheduler();
    let call = 0;
    const source = createPollingRunProgressSource({
      read: async () => snapshotAt(`2026-09-01T09:0${call++}:00.000Z`),
      schedule: scheduler.schedule,
    });

    const subscriber = collector();
    const subscription = source.subscribe('run-1', subscriber);
    await Promise.resolve();
    await Promise.resolve();
    expect(subscriber.seen).toHaveLength(1);

    await scheduler.fire();
    expect(subscriber.seen).toHaveLength(2);
    // Two observations of the same run can legitimately differ by any amount, or not at
    // all: the source delivers what it was given and interprets nothing.
    expect(subscriber.seen[0]?.observedAt).not.toBe(subscriber.seen[1]?.observedAt);

    subscription.close();
  });

  it('stops once the 迁移运行 has ended, because there is nothing further to observe', async () => {
    const scheduler = manualScheduler();
    const source = createPollingRunProgressSource({
      read: async () => snapshotAt('2026-09-01T12:00:00.000Z', '2026-09-01T12:00:00.000Z'),
      schedule: scheduler.schedule,
    });

    const subscriber = collector();
    source.subscribe('run-1', subscriber);
    await Promise.resolve();
    await Promise.resolve();

    expect(subscriber.seen).toHaveLength(1);
    expect(scheduler.depth).toBe(0);
  });

  it('reports a failed observation without ending the subscription', async () => {
    const scheduler = manualScheduler();
    let attempt = 0;
    const source = createPollingRunProgressSource({
      read: async () => {
        attempt += 1;
        if (attempt === 1) throw new Error('transport');
        return snapshotAt('2026-09-01T09:05:00.000Z');
      },
      schedule: scheduler.schedule,
    });

    const errors: unknown[] = [];
    const seen: RunProgressSnapshot[] = [];
    source.subscribe('run-1', {
      onSnapshot: (snapshot) => void seen.push(snapshot),
      onError: (error) => void errors.push(error),
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(errors).toHaveLength(1);
    // One failed observation is not the end of the run: the next one is still asked for.
    await scheduler.fire();
    expect(seen).toHaveLength(1);
  });

  it('asks nothing more once it is closed', async () => {
    const scheduler = manualScheduler();
    const read = vi.fn(async () => snapshotAt('2026-09-01T09:00:00.000Z'));
    const source = createPollingRunProgressSource({ read, schedule: scheduler.schedule });

    const subscription = source.subscribe('run-1', collector());
    await Promise.resolve();
    await Promise.resolve();
    subscription.close();
    await scheduler.fire();

    expect(read).toHaveBeenCalledTimes(1);
  });
});

describe('a push implementation behind the same interface', () => {
  it('delivers snapshots the same way and is substitutable for the polling one', () => {
    // This is what ADR-0007's undecided transport looks like from the frontend: an SSE or
    // WebSocket implementation supplies `open` and nothing above the seam changes.
    const opened: RunProgressSubscriber[] = [];
    const closed = vi.fn();
    const source: RunProgressSource = createChannelRunProgressSource({
      open: (_runId, subscriber) => {
        opened.push(subscriber);
        return { close: closed };
      },
    });

    const subscriber = collector();
    const subscription = source.subscribe('run-1', subscriber);
    opened[0]?.onSnapshot(snapshotAt('2026-09-01T09:00:00.000Z'));
    opened[0]?.onSnapshot(snapshotAt('2026-09-01T09:02:00.000Z'));

    expect(subscriber.seen.map((snapshot) => snapshot.observedAt)).toEqual([
      '2026-09-01T09:00:00.000Z',
      '2026-09-01T09:02:00.000Z',
    ]);

    subscription.close();
    expect(closed).toHaveBeenCalledOnce();
  });
});
