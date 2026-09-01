import { describe, expect, it } from 'vitest';
import type { MigrationRun, RunProgressSnapshot } from '@/contract';
import { createControllableClock } from '../clock';
import { createMemoryDraftPersistence } from '../persistence';
import { scenarios, type ScenarioDefinition } from '../scenarios';
import { createMockStore } from '../store';
import {
  MONITORED_RUN_ID,
  OBSERVATION_INTERVAL_MOCK_MS,
  SEEDED_RUN_ELAPSED_QUANTA,
  STUCK_THRESHOLD_MOCK_MS,
  buildRunPlan,
  projectRunProgress,
} from './runProgress';

/**
 * The time dimension of a 迁移运行, checked as arithmetic rather than by watching it.
 *
 * The journey is asserted at seam ① (`e2e/run-monitoring.spec.ts`). What is pinned here is
 * what the *projection* claims, because three of its properties are the ones an interface
 * would quietly lose:
 *
 *  - progress **jumps and lags**, so no view may render smooth monotonic advance;
 *  - 卡死 is reached only by the configured hard threshold, and the stalled table gets
 *    **no outcome** — ADR-0004 forbids inventing per-table blame;
 *  - a 取消 is a terminal stop that leaves already-terminal results alone.
 */

function storeOf(scenarioId: string, rate = 0) {
  const scenario = scenarios.get(scenarioId) as ScenarioDefinition;
  let realTime = 0;
  const clock = createControllableClock({ rate, realNow: () => realTime });
  const store = createMockStore({
    scenario,
    clock,
    draftPersistence: createMemoryDraftPersistence(),
  });
  return { store, clock, advance: (ms: number) => clock.advance(ms), tickReal: () => realTime };
}

function quanta(count: number): number {
  return count * OBSERVATION_INTERVAL_MOCK_MS;
}

function snapshotOf(scenarioId: string): RunProgressSnapshot {
  const { store } = storeOf(scenarioId);
  const snapshot = store.getRunProgress(MONITORED_RUN_ID);
  expect(snapshot).toBeDefined();
  return snapshot as RunProgressSnapshot;
}

describe('the seeded 迁移运行', () => {
  it('is already in flight on first paint, at a fixed identifier', () => {
    // Lead decision D22: a late state is reachable by URL rather than by waiting for it.
    const snapshot = snapshotOf('default');
    expect(snapshot.run.id).toBe(MONITORED_RUN_ID);
    expect(snapshot.run.status).toBe('RUNNING');
    expect(snapshot.run.endedAt).toBeNull();
    expect(snapshot.units.length).toBeGreaterThan(0);
    // Some tables are done, some are moving, some have not been admitted yet: a monitoring
    // screen that only ever shows one of those states proves nothing.
    const phases = new Set(snapshot.units.map((unit) => unit.phase));
    expect(phases.size).toBeGreaterThan(1);
  });

  it('reports the same screen twice for the same instant', () => {
    // Determinism is what makes a review link and a screenshot mean anything.
    expect(JSON.stringify(snapshotOf('default'))).toBe(JSON.stringify(snapshotOf('default')));
  });
});

describe('progress observations', () => {
  it('jump rather than advance smoothly', () => {
    const { store, advance } = storeOf('default');
    const first = store.getRunProgress(MONITORED_RUN_ID) as RunProgressSnapshot;
    // A table that is still moving: a finished one reports the same number forever, which
    // would prove nothing either way.
    const tracked = first.units.find((unit) => unit.phase === 'TRANSFERRING');
    expect(tracked).toBeDefined();

    const readings: number[] = [];
    for (let step = 0; step < 20; step += 1) {
      const snapshot = store.getRunProgress(MONITORED_RUN_ID) as RunProgressSnapshot;
      const unit = snapshot.units.find((entry) => entry.id === tracked?.id);
      readings.push(unit?.progress?.sourceRowsRead ?? 0);
      advance(quanta(1));
    }

    const deltas = readings.slice(1).map((value, index) => value - (readings[index] ?? 0));
    // Monotonic — a row count never goes backwards — but emphatically not uniform, and at
    // least one interval reports nothing new at all. Both are what ADR-0004 permits, and
    // both are what a bar animating between two polls would hide.
    expect(deltas.every((delta) => delta >= 0)).toBe(true);
    expect(new Set(deltas).size).toBeGreaterThan(2);
    expect(deltas.some((delta) => delta === 0)).toBe(true);
  });

  it("lets a unit's observation lag behind the snapshot without being at fault", () => {
    const snapshot = snapshotOf('default');
    const lagging = snapshot.units.filter(
      (unit) =>
        unit.progress !== null &&
        unit.phase !== 'TERMINAL' &&
        unit.progress.observedAt < snapshot.observedAt,
    );
    expect(lagging.length).toBeGreaterThan(0);
    // Lagging is not 卡死: nothing about it produces a diagnosis.
    expect(snapshot.stuck).toBeNull();
  });

  it('never reports more written than read', () => {
    for (const unit of snapshotOf('default').units) {
      if (unit.progress !== null) {
        expect(unit.progress.targetRowsWritten).toBeLessThanOrEqual(unit.progress.sourceRowsRead);
      }
    }
  });
});

describe('「部分表失败」', () => {
  it('records FAILED on some tables while others go on succeeding', () => {
    const snapshot = snapshotOf('partial-table-failure');
    const failed = snapshot.units.filter((unit) => unit.outcome === 'FAILED');
    expect(failed.length).toBeGreaterThan(0);
    expect(snapshot.units.length).toBeGreaterThan(failed.length);
    // A failed unit is terminal and its observation stops where it stopped.
    for (const unit of failed) {
      expect(unit.phase).toBe('TERMINAL');
    }
  });
});

describe('「某表卡死」', () => {
  it('diagnoses 卡死 only after the configured hard threshold', () => {
    const scenario = scenarios.get('stuck-table') as ScenarioDefinition;
    const plan = buildRunPlan({
      seed: scenario.seed,
      runPlan: 'stuck-table',
      sourceDatabase: 'orders',
    });
    const startedAtMs = Date.parse('2026-09-01T00:00:00.000Z');
    const run = {
      id: MONITORED_RUN_ID,
      startedAt: new Date(startedAtMs).toISOString(),
      endedAt: null,
      cancellationRequestedAt: null,
      selectedTableCount: plan.units.length,
    } as unknown as MigrationRun;

    const at = (quantaCount: number): RunProgressSnapshot =>
      projectRunProgress({
        run,
        plan,
        nowMs: startedAtMs + quanta(quantaCount),
        cancellationRequestedAtMs: null,
      });

    // The first instant at which the diagnosis exists at all.
    const horizon = [...Array(80).keys()];
    const firstStuck = horizon.find((quantum) => at(quantum).stuck !== null);
    expect(firstStuck).toBeDefined();
    if (firstStuck === undefined) return;

    // A table that has stopped moving is not yet 卡死: 卡死 *is* the configured hard
    // threshold, and reaching it earlier would make the diagnosis a synonym for 「慢」.
    const before = at(firstStuck - 1);
    expect(before.stuck).toBeNull();
    expect(before.run.status).toBe('RUNNING');

    const diagnosis = at(firstStuck).stuck;
    expect(diagnosis).not.toBeNull();
    if (diagnosis === null) return;
    expect(Date.parse(diagnosis.diagnosedAt) - Date.parse(diagnosis.lastProgressAt)).toBe(
      STUCK_THRESHOLD_MOCK_MS,
    );
    expect(at(firstStuck).run.status).toBe('ATTENTION_REQUIRED');
  });

  it('leaves the stalled table without an outcome and blocks the tables beside it', () => {
    const snapshot = snapshotOf('stuck-table');
    const stuck = snapshot.stuck;
    expect(stuck).not.toBeNull();
    if (stuck === null) return;

    const stalled = snapshot.units.filter((unit) => stuck.stalledUnitIds.includes(unit.id));
    expect(stalled.length).toBeGreaterThan(0);
    for (const unit of stalled) {
      // ADR-0004: 「STUCK is deliberately not a table outcome … DBX never invents per-table
      // blame merely to populate an outcome.」
      expect(unit.outcome).toBeNull();
      expect(unit.phase).not.toBe('TERMINAL');
    }

    const blocked = snapshot.units.filter((unit) => stuck.blockedUnitIds.includes(unit.id));
    expect(blocked.length).toBeGreaterThan(0);
    for (const unit of blocked) {
      expect(unit.outcome).toBe('BLOCKED_BY_BOX_FAILURE');
    }

    // A 卡死 run has not ended: DBX preserves the target data and the evidence and waits.
    expect(snapshot.run.endedAt).toBeNull();
    expect(stuck.thresholdMs).toBe(STUCK_THRESHOLD_MOCK_MS);
  });
});

describe('取消', () => {
  it('is a terminal stop that leaves already-terminal results untouched', () => {
    const { store } = storeOf('default');
    const before = store.getRunProgress(MONITORED_RUN_ID) as RunProgressSnapshot;
    const alreadyTerminal = before.units.filter((unit) => unit.phase === 'TERMINAL');

    const consequences = store.describeRunCancellation(MONITORED_RUN_ID);
    expect(consequences?.terminalUnitCount).toBe(alreadyTerminal.length);
    expect(consequences?.inFlightUnitCount).toBe(before.units.length - alreadyTerminal.length);
    expect(consequences?.alreadyRequested).toBe(false);

    const after = store.requestRunCancellation(MONITORED_RUN_ID) as RunProgressSnapshot;
    expect(after.run.cancellationRequestedAt).not.toBeNull();
    // The technical results that already existed are not rewritten by a 取消.
    for (const unit of alreadyTerminal) {
      const now = after.units.find((entry) => entry.id === unit.id);
      expect(now?.outcome).toBe(unit.outcome);
    }
  });

  it('reaches the tables that were still in flight', () => {
    const { store, advance } = storeOf('default');
    store.requestRunCancellation(MONITORED_RUN_ID);
    advance(quanta(3));
    const after = store.getRunProgress(MONITORED_RUN_ID) as RunProgressSnapshot;
    expect(after.run.status).toBe('CANCELLED');
    expect(after.run.endedAt).not.toBeNull();
    expect(after.units.some((unit) => unit.outcome === 'CANCELLED')).toBe(true);
  });

  it('is already recorded in the 「操作员取消」 scenario', () => {
    const snapshot = snapshotOf('operator-cancellation');
    expect(snapshot.run.cancellationRequestedAt).not.toBeNull();
    expect(snapshot.run.status).toBe('CANCELLED');
  });
});

describe('the run, the timeline and the log', () => {
  it('reports its own observation instant and the true totals behind its bounds', () => {
    const snapshot = snapshotOf('default');
    expect(Date.parse(snapshot.observedAt)).toBeGreaterThan(Date.parse(snapshot.run.startedAt));
    expect(snapshot.eventTotalCount).toBeGreaterThanOrEqual(snapshot.events.length);
    expect(snapshot.logTotalCount).toBeGreaterThanOrEqual(snapshot.log.length);
    expect(snapshot.unitTotalCount).toBeGreaterThanOrEqual(snapshot.units.length);
    // Most recent first, in both.
    const times = snapshot.events.map((event) => event.occurredAt);
    expect([...times].sort((a, b) => b.localeCompare(a, 'en'))).toEqual(times);
  });

  it('keeps the execution platform out of the log a DBA would paste into a ticket', () => {
    // Gate 7 applies to server-produced evidence as much as to translated copy.
    const text = snapshotOf('stuck-table')
      .log.map((line) => line.text)
      .join('\n')
      .toLowerCase();
    for (const forbidden of ['box', 'connector', 'topic', 'kafka']) {
      expect(text).not.toContain(forbidden);
    }
  });

  it('elapses far enough to be worth looking at, at the seeded start', () => {
    expect(SEEDED_RUN_ELAPSED_QUANTA * OBSERVATION_INTERVAL_MOCK_MS).toBeGreaterThan(
      STUCK_THRESHOLD_MOCK_MS,
    );
  });
});
