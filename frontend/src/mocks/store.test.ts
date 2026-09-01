import { describe, expect, it } from 'vitest';
import { createControllableClock } from './clock';
import { createMemoryDraftPersistence } from './persistence';
import { scenarios } from './scenarios';
import { CONFIRM_DRAFT_ID, createMockStore, type MockStore } from './store';
import type { ScenarioDefinition } from './scenarios';
import type { WriteFreezeDeclaration } from '@/contract';
import { reportCoversEveryTableOnce } from '@/features/remigration/candidates';

/**
 * 「开始迁移」 as the store performs it — the draft→task→run transition and the two
 * constraints that stand in front of it.
 *
 * The journey is asserted at seam ① (`e2e/execution-confirmation.spec.ts`), where a real
 * browser has to be refused. What is checked here is what the *server* does with a request
 * that never went through the wizard, because that is where Gate 5 and Gate 6 have to be
 * true rather than merely displayed: a gate that only lives in a screen is a suggestion.
 *
 * The immutability of a 迁移运行 is checked as a property of the object the store hands
 * out, not as a `readonly` type — which is erased at run time — and not as prose.
 */

function storeOf(scenarioId: string): MockStore {
  const scenario = scenarios.get(scenarioId) as ScenarioDefinition;
  return createMockStore({
    scenario,
    // Anchored and never advanced: nothing here is about the passage of time.
    clock: createControllableClock({ rate: 0, realNow: () => 0 }),
    draftPersistence: createMemoryDraftPersistence(),
  });
}

const freeze: WriteFreezeDeclaration = {
  accountableOperator: 'zhang.wei',
  durationHours: 8,
  changeReference: 'CHG-2026-0901',
};

describe('startMigrationRun', () => {
  it('turns the 迁移草稿 into an approved 迁移任务 and one 迁移运行, and consumes the draft', () => {
    const store = storeOf('stage-confirm');
    const summary = store.summariseExecutionConfirmation(CONFIRM_DRAFT_ID);
    expect(summary).toBeDefined();

    const started = store.startMigrationRun(CONFIRM_DRAFT_ID, freeze);
    expect(started.ok).toBe(true);
    if (!started.ok) return;

    // Approval is part of what a 迁移任务 is, and the 写冻结's accountable operator is who
    // made it.
    expect(started.task.approvedBy).toBe('zhang.wei');
    expect(started.task.latestRunId).toBe(started.run.id);
    expect(started.run.taskId).toBe(started.task.id);
    expect(started.run.writeFreeze.accountableOperator).toBe('zhang.wei');
    expect(started.run.selectedTableCount).toBe(summary?.tables.length);

    // The draft is consumed rather than kept beside its task: a second, editable copy of a
    // scope that is now audit evidence is exactly what must not exist.
    expect(store.getMigrationDraft(CONFIRM_DRAFT_ID)).toBeUndefined();
    expect(store.listMigrationDrafts().map((draft) => draft.id)).not.toContain(CONFIRM_DRAFT_ID);
    expect(store.listMigrationRuns(started.task.id).map((run) => run.id)).toEqual([started.run.id]);
  });

  it('hands out a 迁移运行 that cannot be edited afterwards', () => {
    const store = storeOf('stage-confirm');
    const started = store.startMigrationRun(CONFIRM_DRAFT_ID, freeze);
    expect(started.ok).toBe(true);
    if (!started.ok) return;

    const run = store.getMigrationRun(started.run.id);
    expect(run).toBeDefined();
    if (run === undefined) return;

    // 「One immutable execution attempt」, at run time. The scope recorded at this instant
    // is what the whole audit chain hangs from, so every level of it refuses a write.
    expect(() => {
      (run as { selectedTableCount: number }).selectedTableCount = 0;
    }).toThrow(TypeError);
    expect(() => {
      (run.writeFreeze as { accountableOperator: string }).accountableOperator = 'someone.else';
    }).toThrow(TypeError);
    expect(() => {
      (run.sourceBaseline as { capturedAt: string }).capturedAt = '2026-09-02T09:00:00.000Z';
    }).toThrow(TypeError);
    expect(run.selectedTableCount).toBe(started.run.selectedTableCount);
    expect(run.writeFreeze.accountableOperator).toBe('zhang.wei');
  });

  it('is Gate 5: a 写冻结 with no 责任人 or no 时限 starts nothing', () => {
    for (const declaration of [
      { ...freeze, accountableOperator: '   ' },
      { ...freeze, durationHours: 0 },
    ]) {
      const store = storeOf('stage-confirm');
      const taskIdsBefore = store.listMigrationTasks().map((task) => task.id);
      const refused = store.startMigrationRun(CONFIRM_DRAFT_ID, declaration);
      expect(refused).toEqual({ ok: false, code: 'WRITE_FREEZE_NOT_CONFIRMED' });
      // Refused means nothing happened: the draft is still a draft, and the 迁移任务 the
      // scenario seeds are the only ones there are.
      expect(store.getMigrationDraft(CONFIRM_DRAFT_ID)).toBeDefined();
      expect(store.listMigrationTasks().map((task) => task.id)).toEqual(taskIdsBefore);
    }
  });

  it('is Gate 6: a table with no possible 结构证明 starts nothing', () => {
    // The target schema already holds one of the tables the 迁移范围 would create, so no
    // 结构证明 can be established for it (ADR-0011) — and the refusal is the server's,
    // which is where the constraint actually lives.
    const store = storeOf('structural-proof-missing');
    expect(
      store.summariseExecutionConfirmation(CONFIRM_DRAFT_ID)?.structuralProof.gaps.length,
    ).toBeGreaterThan(0);

    const taskIdsBefore = store.listMigrationTasks().map((task) => task.id);
    const refused = store.startMigrationRun(CONFIRM_DRAFT_ID, freeze);
    expect(refused).toEqual({ ok: false, code: 'STRUCTURAL_PROOF_MISSING' });
    expect(store.getMigrationDraft(CONFIRM_DRAFT_ID)).toBeDefined();
    expect(store.listMigrationTasks().map((task) => task.id)).toEqual(taskIdsBefore);
  });
});

/**
 * 重新迁移 as the store performs it (#41).
 *
 * The journey is asserted at seam ① (`e2e/re-migration.spec.ts`). What is checked here is
 * the property no screenshot can show: that a re-migration **creates** a record and
 * **touches nothing**. `CONTEXT.md` lists 「retry in place」 under 迁移运行's `_Avoid_`, so
 * the interesting assertions below are the negative ones — the earlier run comes out of
 * the store byte-identical, and still refuses a write.
 */
describe('startRemigration', () => {
  const remigrationScenario = 'inconclusive-validation';
  const seededRunId = 'run-monitored';

  it('creates a new 迁移运行 over a narrower scope and leaves the earlier one untouched', () => {
    const store = storeOf(remigrationScenario);
    const before = store.getMigrationRun(seededRunId);
    const offer = store.describeRemigration(seededRunId);
    expect(before).toBeDefined();
    expect(offer).toBeDefined();
    if (before === undefined || offer === undefined) return;
    expect(offer.candidates.length).toBeGreaterThan(0);
    // Narrower by construction: only the tables whose own result is undetermined.
    expect(offer.candidates.length).toBeLessThan(before.selectedTableCount);

    const started = store.startRemigration(seededRunId, {
      unitIds: offer.candidates.map((candidate) => candidate.unitId),
      writeFreeze: freeze,
    });
    expect(started.ok).toBe(true);
    if (!started.ok) return;

    expect(started.run.id).not.toBe(seededRunId);
    expect(started.run.taskId).toBe(before.taskId);
    expect(started.run.origin).toEqual({ kind: 'REMIGRATION', ofRunId: seededRunId });
    // Its own selected scope, and a smaller one than the run it came from.
    expect(started.run.selectedTableCount).toBe(offer.candidates.length);
    expect(started.run.selectedTableCount).toBeLessThan(before.selectedTableCount);
    expect(started.run.sourceBaseline.entries).toHaveLength(offer.candidates.length);
    // A rerun creates new 表迁移单元 rather than reopening the old ones, so no unit
    // identifier is shared between the two attempts.
    const previousUnitIds = new Set(offer.candidates.map((candidate) => candidate.unitId));
    for (const unit of store.getRunProgress(started.run.id)?.units ?? []) {
      expect(previousUnitIds.has(unit.id)).toBe(false);
    }

    // The history now holds both attempts, and the earlier one is exactly as it was.
    const runs = store.listMigrationRuns(before.taskId);
    expect(runs.map((run) => run.id)).toContain(started.run.id);
    expect(runs.map((run) => run.id)).toContain(seededRunId);
    expect(store.getMigrationTask(before.taskId)?.runCount).toBe(2);

    const after = store.getMigrationRun(seededRunId);
    expect(after?.selectedTableCount).toBe(before.selectedTableCount);
    expect(after?.startedAt).toBe(before.startedAt);
    expect(after?.writeFreeze).toEqual(before.writeFreeze);
    expect(after?.sourceBaseline).toEqual(before.sourceBaseline);
    expect(after?.origin).toEqual({ kind: 'INITIAL' });
    // Still frozen: nothing about starting another run made the first one writable.
    expect(() => {
      (after as unknown as { selectedTableCount: number }).selectedTableCount = 0;
    }).toThrow(TypeError);
  });

  it('establishes its own evidence rather than carrying the earlier run’s forward', () => {
    const store = storeOf(remigrationScenario);
    const previous = store.getMigrationRun(seededRunId);
    const offer = store.describeRemigration(seededRunId);
    expect(previous).toBeDefined();
    expect(offer).toBeDefined();
    if (previous === undefined || offer === undefined) return;

    const started = store.startRemigration(seededRunId, {
      unitIds: offer.candidates.map((candidate) => candidate.unitId),
      writeFreeze: freeze,
    });
    expect(started.ok).toBe(true);
    if (!started.ok) return;
    const run = started.run;

    // 「A rerun freshly tests connections and capabilities … obtains a new write-freeze
    // commitment and source baseline, regenerates write contracts」 (ADR-0006). Every
    // instant below is this run's own, and none of them is the earlier run's.
    expect(run.establishedEvidence.connectionChecks).toHaveLength(2);
    for (const check of run.establishedEvidence.connectionChecks) {
      expect(check.outcome).toBe('SUCCEEDED');
      expect(check.checkedAt).toBe(run.startedAt);
    }
    expect(run.writeFreeze.confirmedAt).toBe(run.startedAt);
    expect(run.writeFreeze.confirmedAt).not.toBe(previous.writeFreeze.confirmedAt);
    expect(run.sourceBaseline.capturedAt).toBe(run.startedAt);
    expect(run.sourceBaseline.capturedAt).not.toBe(previous.sourceBaseline.capturedAt);
    expect(run.establishedEvidence.tables).toHaveLength(run.selectedTableCount);
    for (const table of run.establishedEvidence.tables) {
      // Only a `SUPPORTED` 预检 may be approved, and its 表写入契约 was regenerated now.
      expect(table.preflightConclusion).toBe('SUPPORTED');
      expect(table.preflightConcludedAt).toBe(run.startedAt);
      expect(table.contractGeneratedAt).toBe(run.startedAt);
      expect(table.contractVersion).toBeGreaterThan(0);
    }
  });

  it('never offers a 预检排除项 or a passing table, and refuses one that is asked for', () => {
    const store = storeOf(remigrationScenario);
    const report = store.getValidationReport(seededRunId);
    const offer = store.describeRemigration(seededRunId);
    expect(report).toBeDefined();
    expect(offer).toBeDefined();
    if (report === undefined || offer === undefined) return;

    const offered = [...offer.candidates, ...offer.ineligible];
    expect(offer.exclusions.length).toBeGreaterThan(0);
    for (const exclusion of offer.exclusions) {
      // 「没迁」 is not 「迁了但没过」: a table that never migrated has no technical
      // conclusion and may never be offered as though it had failed.
      expect(offered.map((candidate) => candidate.sourceTable)).not.toContain(
        exclusion.sourceTable,
      );
    }
    const passing = report.rows.filter((row) => row.conclusion === 'PASS');
    expect(passing.length).toBeGreaterThan(0);
    for (const row of passing) {
      expect(offered.map((candidate) => candidate.unitId)).not.toContain(row.unitId);
    }

    const runsBefore = store.listMigrationRuns(report.taskId).length;
    const refused = store.startRemigration(seededRunId, {
      unitIds: [passing[0]?.unitId ?? ''],
      writeFreeze: freeze,
    });
    expect(refused).toEqual({ ok: false, code: 'NOT_A_CANDIDATE' });
    // Refused means nothing happened.
    expect(store.listMigrationRuns(report.taskId)).toHaveLength(runsBefore);
  });

  it('needs a 写冻结 of its own, and at least one table', () => {
    const store = storeOf(remigrationScenario);
    const offer = store.describeRemigration(seededRunId);
    expect(offer).toBeDefined();
    if (offer === undefined) return;
    const unitIds = offer.candidates.map((candidate) => candidate.unitId);

    expect(
      store.startRemigration(seededRunId, {
        unitIds,
        writeFreeze: { ...freeze, accountableOperator: '  ' },
      }),
    ).toEqual({ ok: false, code: 'WRITE_FREEZE_NOT_CONFIRMED' });
    expect(
      store.startRemigration(seededRunId, {
        unitIds,
        writeFreeze: { ...freeze, durationHours: 0 },
      }),
    ).toEqual({ ok: false, code: 'WRITE_FREEZE_NOT_CONFIRMED' });
    expect(store.startRemigration(seededRunId, { unitIds: [], writeFreeze: freeze })).toEqual({
      ok: false,
      code: 'NO_TABLES_SELECTED',
    });
    expect(store.startRemigration('no-such-run', { unitIds, writeFreeze: freeze })).toEqual({
      ok: false,
      code: 'NOT_FOUND',
    });
  });
});

/**
 * The seeded 迁移运行 history, checked against the plan it is a record of.
 *
 * A seeded run records a status; the store derives its end time from the plan the
 * projection replays (`runPlanEndQuantum`). The property that keeps the two honest is
 * asserted here, and it is asserted against the **projection** — the units, the diagnosis
 * and the 校验报告 — rather than against the run record, because the record is what the
 * projection is being checked for agreement *with*.
 *
 * Before this held, a `COMPLETED` run rendered tables still 等待调度 and a 校验报告 that
 * announced its 校验执行 were still running: one fact told twice, in two numbers nobody
 * compared.
 *
 * Walked over **every** scenario, because the plan a run is given depends on the scenario
 * as well as on the recorded status, and a disagreement that only appears under one review
 * link is exactly the kind that reaches #42.
 */
describe('每一条种子迁移运行的投影都与它记录的状态一致', () => {
  for (const scenarioId of scenarios.keys()) {
    it(`agrees with its own record in 「${scenarioId}」`, () => {
      const store = storeOf(scenarioId);
      const runs = store.listMigrationTasks().flatMap((task) => store.listMigrationRuns(task.id));

      for (const run of runs) {
        const snapshot = store.getRunProgress(run.id);
        expect(snapshot, `${run.id} 应当有进度投影`).toBeDefined();
        const report = store.getValidationReport(run.id);
        expect(report, `${run.id} 应当有校验报告`).toBeDefined();
        if (snapshot === undefined || report === undefined) continue;

        const where = `${scenarioId}/${run.id}`;
        const outcomes = snapshot.units.map((unit) => unit.outcome);

        if (run.endedAt === null) {
          // 卡死 stops a run without ending it: DBX preserves the target data and the
          // evidence and waits for a decision, so there is no end time to state.
          expect(
            run.status === 'ATTENTION_REQUIRED' ? snapshot.stuck !== null : true,
            `${where} 记录为需要人工处理，投影里却没有卡死诊断`,
          ).toBe(true);
          continue;
        }

        // An ended run holds no table still waiting to be scheduled and none still moving.
        expect(
          snapshot.units.every((unit) => unit.phase === 'TERMINAL'),
          `${where} 已结束，仍有表迁移单元没有到达终局`,
        ).toBe(true);
        // …and no 校验执行 still in flight beside a finished run's conclusion.
        expect(report.validationInFlight, `${where} 已结束，校验报告却说校验还没跑完`).toBe(false);
        expect(
          report.rows.every((row) => row.conclusion !== 'IN_FLIGHT'),
          `${where} 已结束，校验报告里仍有进行中的结论`,
        ).toBe(true);

        // The recorded status is the one its own units project.
        switch (run.status) {
          case 'COMPLETED':
            expect(
              outcomes.every((outcome) => outcome === 'SUCCEEDED'),
              where,
            ).toBe(true);
            break;
          case 'COMPLETED_WITH_FAILURES':
            expect(outcomes.includes('FAILED'), where).toBe(true);
            break;
          case 'COMPLETED_WITH_ACCEPTED_RISK':
            expect(outcomes.includes('COMPLETED_WITH_ACCEPTED_RISK'), where).toBe(true);
            expect(outcomes.includes('FAILED'), where).toBe(false);
            break;
          case 'CANCELLED':
            expect(run.cancellationRequestedAt, where).not.toBeNull();
            break;
          default:
            throw new Error(`${where}: 已结束的迁移运行不应当记录为 ${run.status}`);
        }

        // The partition the 重新迁移 candidate list depends on: 「没迁」 and 「迁了但没过」
        // are never the same table.
        expect(reportCoversEveryTableOnce(report), `${where} 的校验报告应当只覆盖每张表一次`).toBe(
          true,
        );
      }
    });
  }
});
