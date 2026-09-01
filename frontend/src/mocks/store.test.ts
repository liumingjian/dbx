import { describe, expect, it } from 'vitest';
import { createControllableClock } from './clock';
import { createMemoryDraftPersistence } from './persistence';
import { scenarios } from './scenarios';
import { CONFIRM_DRAFT_ID, createMockStore, type MockStore } from './store';
import type { ScenarioDefinition } from './scenarios';
import type { WriteFreezeDeclaration } from '@/contract';

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
