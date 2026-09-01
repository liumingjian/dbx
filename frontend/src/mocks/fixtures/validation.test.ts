import { describe, expect, it } from 'vitest';
import type { ValidationReport, ValidationReportRow } from '@/contract';
import { createControllableClock } from '../clock';
import { createMemoryDraftPersistence } from '../persistence';
import { scenarios, type ScenarioDefinition } from '../scenarios';
import { createMockStore, type MockStore } from '../store';
import { MONITORED_RUN_ID } from './runProgress';

/**
 * 校验报告 and 校验处置, checked where the constraint actually lives (#40).
 *
 * The single most important assertion in this file is the one that proves a negative:
 * **recording a 校验处置 changes nothing about the 校验执行**. `CONTEXT.md` puts it in the
 * glossary — 「Accepting risk may close the workflow but never changes the technical
 * validation result to passed」 — and lists 「Manual pass, overridden result」 under
 * `_Avoid_`. If that ever stops holding, every piece of evidence the product produced
 * earlier becomes worthless, and a screen-level test would not necessarily notice.
 *
 * The rest of the file pins the three separations the report exists to keep: the three
 * conclusions, 「没迁」 versus 「迁了但没过」, and `NOT_APPLICABLE` / `NOT_RUN` / a real
 * failure.
 */

function storeOf(scenarioId: string): MockStore {
  const scenario = scenarios.get(scenarioId) as ScenarioDefinition;
  // Frozen real time: nothing here depends on how long the test itself took.
  const clock = createControllableClock({ rate: 0, realNow: () => 0 });
  return createMockStore({
    scenario,
    clock,
    draftPersistence: createMemoryDraftPersistence(),
  });
}

function reportOf(store: MockStore): ValidationReport {
  const report = store.getValidationReport(MONITORED_RUN_ID);
  expect(report).toBeDefined();
  return report as ValidationReport;
}

function rowWith(report: ValidationReport, conclusion: string): ValidationReportRow {
  const row = report.rows.find((entry) => entry.conclusion === conclusion);
  expect(row, `expected a row concluding ${conclusion}`).toBeDefined();
  return row as ValidationReportRow;
}

describe('校验处置 never rewrites the technical result', () => {
  it('leaves every校验项 exactly as it was, and never produces a PASS', () => {
    const store = storeOf('inconclusive-validation');
    const before = rowWith(reportOf(store), 'INCONCLUSIVE');
    const itemsBefore = before.execution?.items;
    expect(itemsBefore).toBeDefined();

    const result = store.recordValidationDisposition(MONITORED_RUN_ID, {
      unitId: before.unitId,
      reason: '业务已确认这批差异在本次窗口内可以接受。',
      accountableOperator: 'li.na',
    });
    expect(result.ok).toBe(true);

    const after = reportOf(store).rows.find((row) => row.unitId === before.unitId);
    expect(after).toBeDefined();
    // The conclusion, item by item, byte for byte.
    expect(after?.conclusion).toBe('INCONCLUSIVE');
    expect(after?.execution?.items).toStrictEqual(itemsBefore);
    expect(after?.execution?.planVersion).toBe(before.execution?.planVersion);
    // And in particular: not a pass, by any route.
    expect(after?.conclusion).not.toBe('PASS');
    expect(
      after?.execution?.items.some(
        (item) => item.state === 'PASS' && item.checkId === 'VALUE_CHECKSUM_SAMPLE',
      ),
    ).toBe(false);
  });

  it('records the reason and the named 责任人, and closes the workflow with 接受风险', () => {
    const store = storeOf('inconclusive-validation');
    const row = rowWith(reportOf(store), 'INCONCLUSIVE');
    // 校验处置 is what a table waiting for a decision is waiting for: until then DBX gives
    // it no outcome of its own, because 迁移完成 requires every enabled check to have passed.
    expect(row.unitOutcome).toBeNull();

    store.recordValidationDisposition(MONITORED_RUN_ID, {
      unitId: row.unitId,
      reason: '差异已在变更评审中逐行复核。',
      accountableOperator: 'zhang.wei',
    });

    const after = reportOf(store).rows.find((entry) => entry.unitId === row.unitId);
    expect(after?.disposition?.accountableOperator).toBe('zhang.wei');
    expect(after?.disposition?.reason).toContain('逐行复核');
    expect(after?.disposition?.acceptedCheckIds).toContain('VALUE_CHECKSUM_SAMPLE');
    // The workflow closes — and what closes it is emphatically not `SUCCEEDED`.
    expect(after?.unitOutcome).toBe('COMPLETED_WITH_ACCEPTED_RISK');
    expect(after?.unitOutcome).not.toBe('SUCCEEDED');
  });

  it('refuses a decision with no 理由 or no 责任人', () => {
    const store = storeOf('inconclusive-validation');
    const row = rowWith(reportOf(store), 'INCONCLUSIVE');

    for (const request of [
      { reason: '   ', accountableOperator: 'li.na' },
      { reason: '可以接受', accountableOperator: '' },
    ]) {
      const result = store.recordValidationDisposition(MONITORED_RUN_ID, {
        unitId: row.unitId,
        ...request,
      });
      expect(result.ok).toBe(false);
      expect(result.ok ? '' : result.code).toBe('REASON_OR_OPERATOR_MISSING');
    }
    expect(
      reportOf(store).rows.find((entry) => entry.unitId === row.unitId)?.disposition,
    ).toBeNull();
  });

  it('has nothing to dispose of when the 校验执行 concluded PASS', () => {
    const store = storeOf('inconclusive-validation');
    const passing = rowWith(reportOf(store), 'PASS');
    const result = store.recordValidationDisposition(MONITORED_RUN_ID, {
      unitId: passing.unitId,
      reason: '想顺手关掉',
      accountableOperator: 'li.na',
    });
    expect(result.ok).toBe(false);
    expect(result.ok ? '' : result.code).toBe('NOTHING_TO_DISPOSE');
  });

  it('is already recorded in the 「已记录校验处置」 scenario, with the result unchanged', () => {
    // Lead decision D22: the state is reachable on first paint rather than performed first.
    const report = reportOf(storeOf('accepted-risk'));
    const disposed = report.rows.filter((row) => row.disposition !== null);
    expect(disposed.length).toBeGreaterThan(0);
    for (const row of disposed) {
      expect(row.conclusion).toBe('FAIL');
      expect(row.disposition?.accountableOperator).not.toBe('');
      expect(row.disposition?.reason).not.toBe('');
      expect(row.unitOutcome).toBe('COMPLETED_WITH_ACCEPTED_RISK');
    }
  });
});

describe('the report keeps 「没迁」 apart from 「迁了但没过」', () => {
  it('lists 预检排除项 separately from every technical result', () => {
    const report = reportOf(storeOf('inconclusive-validation'));
    expect(report.exclusions.length).toBeGreaterThan(0);
    expect(new Set(report.exclusions.map((entry) => entry.reason))).toEqual(
      new Set(['OPERATOR_EXCLUDED', 'PREFLIGHT_UNSUPPORTED', 'PREFLIGHT_INCONCLUSIVE']),
    );
    // An excluded table is never also a row: it has no 校验执行 to conclude anything.
    const migrated = new Set(report.rows.map((row) => row.sourceTable));
    for (const exclusion of report.exclusions) {
      expect(migrated.has(exclusion.sourceTable)).toBe(false);
    }
    expect(report.scope.excludedTableCount).toBe(report.exclusions.length);
  });

  it('states the 迁移范围 the conclusions cover', () => {
    const report = reportOf(storeOf('inconclusive-validation'));
    expect(report.scope.selectedTableCount).toBeGreaterThan(0);
    expect(report.scope.sourceDatabase).not.toBe('');
    expect(report.scope.targetSchema).not.toBe('');
    expect(report.scope.baselineCapturedAt).not.toBe('');
  });

  it('gives a table whose write failed no 校验执行 at all', () => {
    // 「A 校验执行 is one retained attempt … **after write completion**」. A table that never
    // got there has no attempt — which is NOT_RUN, and is not a failed check.
    const report = reportOf(storeOf('partial-table-failure'));
    const failed = report.rows.filter((row) => row.unitOutcome === 'FAILED');
    expect(failed.length).toBeGreaterThan(0);
    for (const row of failed) {
      expect(row.execution).toBeNull();
      expect(row.conclusion).toBe('NOT_RUN');
      expect(row.conclusion).not.toBe('FAIL');
    }
  });
});

describe('NOT_APPLICABLE, NOT_RUN and a real failure are three things', () => {
  it('marks a check the 校验计划 could not apply, and one it did not enable', () => {
    const report = reportOf(storeOf('accepted-risk'));
    const items = report.rows.flatMap((row) => row.execution?.items ?? []);

    const notApplicable = items.filter((item) => item.state === 'NOT_APPLICABLE');
    expect(notApplicable.length).toBeGreaterThan(0);
    // Only a rule in the versioned plan may classify a check as not applicable, and it says why.
    for (const item of notApplicable) {
      expect(item.detail).not.toBeNull();
    }

    expect(items.some((item) => item.state === 'NOT_RUN')).toBe(true);
    expect(items.some((item) => item.state === 'FAIL')).toBe(true);
    expect(items.some((item) => item.state === 'PASS')).toBe(true);
  });
});

describe('a report of a run that is still validating', () => {
  it('says so rather than concluding early', () => {
    const report = reportOf(storeOf('default'));
    expect(report.validationInFlight).toBe(true);
    expect(report.rows.some((row) => row.conclusion === 'IN_FLIGHT')).toBe(true);
  });

  it('is finished in the two scenarios seeded past validation', () => {
    for (const scenarioId of ['inconclusive-validation', 'accepted-risk']) {
      expect(reportOf(storeOf(scenarioId)).validationInFlight, scenarioId).toBe(false);
    }
  });

  it('never lets an unfinished attempt count as a conclusion', () => {
    const report = reportOf(storeOf('default'));
    for (const row of report.rows) {
      if (row.conclusion === 'IN_FLIGHT') {
        expect(row.execution?.completedAt ?? null).toBeNull();
      }
    }
  });
});
