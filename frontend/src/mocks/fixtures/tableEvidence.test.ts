import { describe, expect, it } from 'vitest';
import type { RunProgressSnapshot, TableMigrationUnitEvidence } from '@/contract';
import { createControllableClock } from '../clock';
import { createMemoryDraftPersistence } from '../persistence';
import { scenarios, type ScenarioDefinition } from '../scenarios';
import { createMockStore } from '../store';
import { MONITORED_RUN_ID, OBSERVATION_INTERVAL_MOCK_MS } from './runProgress';
import { buildTableMigrationUnitEvidence } from './tableEvidence';

/**
 * 单表证据, checked as the projection it is (#39).
 *
 * The journey — a drawer that owns a URL — is asserted at seam ① in
 * `e2e/table-evidence.spec.ts`. What is pinned here is what the *evidence* claims, because
 * these are the properties an interface would quietly lose:
 *
 *  - a **诊断 is separate from the 技术结果**. A stalled table has a diagnosis and no
 *    outcome at all, and a table stopped alongside it is undetermined rather than failed
 *    (ADR-0004, ADR-0005).
 *  - the 根因域 distinguishes a **source** problem from a **target** problem, while the two
 *    execution-platform domains are retained in the evidence and never surfaced (Gate 7).
 *  - a healthy table has **no diagnosis**. An interpretation invented to fill a panel is
 *    exactly the speculative cause ADR-0005 forbids.
 */

function storeOf(scenarioId: string) {
  const scenario = scenarios.get(scenarioId) as ScenarioDefinition;
  // Frozen real time: every instant below is chosen explicitly, so no assertion depends on
  // how long the test itself took.
  const clock = createControllableClock({ rate: 0, realNow: () => 0 });
  const store = createMockStore({
    scenario,
    clock,
    draftPersistence: createMemoryDraftPersistence(),
  });
  return {
    store,
    advance: (quanta: number) => clock.advance(quanta * OBSERVATION_INTERVAL_MOCK_MS),
  };
}

function snapshotOf(scenarioId: string, quanta = 0): RunProgressSnapshot {
  const { store, advance } = storeOf(scenarioId);
  advance(quanta);
  return store.getRunProgress(MONITORED_RUN_ID) as RunProgressSnapshot;
}

function evidenceIn(snapshot: RunProgressSnapshot, unitId: string): TableMigrationUnitEvidence {
  const evidence = buildTableMigrationUnitEvidence(snapshot, unitId);
  expect(evidence).toBeDefined();
  return evidence as TableMigrationUnitEvidence;
}

describe('the evidence of one 表迁移单元', () => {
  it('describes the same instant the monitor is looking at', () => {
    // One projection behind both, so the drawer and the row it was opened from can never
    // disagree about what was observed.
    const snapshot = snapshotOf('default');
    const unit = snapshot.units[0];
    const evidence = evidenceIn(snapshot, unit?.id ?? '');
    expect(evidence.observedAt).toBe(snapshot.observedAt);
    expect(evidence.unit).toEqual(unit);
  });

  it('is undefined for a table this run does not contain', () => {
    // A link naming a stranger's table is not a failed read, and the drawer says so.
    expect(buildTableMigrationUnitEvidence(snapshotOf('default'), 'no-such-unit')).toBeUndefined();
  });

  it('reaches no diagnosis for a table that has not failed', () => {
    const snapshot = snapshotOf('default');
    const healthy = snapshot.units.find((unit) => unit.outcome !== 'FAILED');
    const evidence = evidenceIn(snapshot, healthy?.id ?? '');
    expect(evidence.diagnosis).toBeNull();
    expect(evidence.occurrences).toHaveLength(0);
  });
});

describe('a failed table', () => {
  it('separates a source problem from a target problem by 根因域', () => {
    const snapshot = snapshotOf('partial-table-failure');
    const failed = snapshot.units.filter((unit) => unit.outcome === 'FAILED');
    expect(failed.length).toBeGreaterThan(1);

    const domains = failed.map((unit) => evidenceIn(snapshot, unit.id).diagnosis?.rootCauseDomain);
    // The distinction the drawer exists to make readable, present in the fixture rather
    // than only in the copy that renders it.
    expect(domains).toContain('SOURCE_DATABASE');
    expect(domains).toContain('TARGET_DATABASE');
  });

  it('carries an aggregated 错误事件 with its own times and evidence reference', () => {
    const snapshot = snapshotOf('partial-table-failure');
    const failed = snapshot.units.find((unit) => unit.outcome === 'FAILED');
    const evidence = evidenceIn(snapshot, failed?.id ?? '');
    const occurrence = evidence.occurrences[0];

    expect(evidence.occurrences).toHaveLength(1);
    // ADR-0005: repeated observations of the same fingerprint are aggregated with first
    // seen, last seen and count — never appended as duplicate cards.
    expect(occurrence?.observationCount).toBeGreaterThan(1);
    expect(Date.parse(occurrence?.firstObservedAt ?? '')).toBeLessThan(
      Date.parse(occurrence?.lastObservedAt ?? ''),
    );
    expect(occurrence?.evidenceReference).toContain(failed?.id ?? '');
    // The diagnosis is an interpretation *of* those facts, and says which ones.
    expect(evidence.diagnosis?.occurrenceIds).toEqual([occurrence?.id]);
    expect(evidence.diagnosis?.catalogVersion).not.toBe('');
  });

  it('admits when no rule matched instead of naming a cause', () => {
    // Far enough into the run for its third table to have failed: the catalog's first
    // three codes are a source problem, a target problem and an unidentified one, in that
    // order, so that all three are reachable from one scenario.
    const snapshot = snapshotOf('partial-table-failure', 20);
    const codes = snapshot.units
      .filter((unit) => unit.outcome === 'FAILED')
      .map((unit) => evidenceIn(snapshot, unit.id).diagnosis?.code);
    expect(codes).toContain('DBX-UNKNOWN');
    const unknown = snapshot.units
      .filter((unit) => unit.outcome === 'FAILED')
      .map((unit) => evidenceIn(snapshot, unit.id).diagnosis)
      .find((diagnosis) => diagnosis?.code === 'DBX-UNKNOWN');
    // ADR-0005's fallback is a source kind of its own, not a translation rule.
    expect(unknown?.sourceKind).toBe('SYSTEM_FALLBACK');
  });
});

describe('卡死, seen from one table', () => {
  it('gives the stalled table a diagnosis and no technical result', () => {
    const snapshot = snapshotOf('stuck-table');
    const stalledId = snapshot.stuck?.stalledUnitIds[0] ?? '';
    const evidence = evidenceIn(snapshot, stalledId);

    expect(evidence.unit.outcome).toBeNull();
    expect(evidence.diagnosis?.code).toBe('DBX-NO-OBSERVABLE-PROGRESS');
    // A fact DBX produced itself rather than a translated external signature.
    expect(evidence.diagnosis?.sourceKind).toBe('STRUCTURED');
  });

  it('keeps the specific execution-platform domain in the evidence', () => {
    // `CONTEXT.md`: the specific domain 「is retained in the diagnostic evidence for
    // support use」. Presenting it as 迁移平台 is the interface's job, not the contract's,
    // so what is stored here is still the specific one.
    const snapshot = snapshotOf('stuck-table');
    const stalledId = snapshot.stuck?.stalledUnitIds[0] ?? '';
    expect(evidenceIn(snapshot, stalledId).diagnosis?.rootCauseDomain).toBe('KAFKA_CONNECT');
  });

  it('reports a table stopped alongside it as undetermined, not failed', () => {
    const snapshot = snapshotOf('stuck-table');
    const blockedId = snapshot.stuck?.blockedUnitIds[0] ?? '';
    const evidence = evidenceIn(snapshot, blockedId);

    expect(evidence.unit.outcome).toBe('BLOCKED_BY_BOX_FAILURE');
    expect(evidence.diagnosis?.code).toBe('DBX-STOPPED-BY-RELATED-FAILURE');
  });
});

describe('Gate 7 binds the evidence too', () => {
  it('names no scheduling group, connector or topic in what a DBA would paste', () => {
    const snapshot = snapshotOf('stuck-table');
    const ids = [
      ...(snapshot.stuck?.stalledUnitIds ?? []),
      ...(snapshot.stuck?.blockedUnitIds ?? []),
      ...snapshot.units.map((unit) => unit.id),
    ];
    const text = ids
      .map((id) => JSON.stringify(buildTableMigrationUnitEvidence(snapshot, id)?.occurrences ?? []))
      .join(' ')
      .toLowerCase();

    for (const forbidden of ['box', '箱', 'connector', '连接器', 'topic', 'kafka']) {
      expect(text, `「${forbidden}」 must not reach the operator`).not.toContain(forbidden);
    }
  });
});
