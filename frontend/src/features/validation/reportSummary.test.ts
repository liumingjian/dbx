import { describe, expect, it } from 'vitest';
import { messages } from '@/messages';
import type { ValidationReport, ValidationReportRow } from '@/contract';
import { formatValidationReport } from './reportExport';
import { isDisposable, summariseValidationReport } from './reportSummary';

/**
 * The report's arithmetic and its exported form (#40), at seam ②.
 *
 * Two properties are asserted that a screen test could not see clearly: **a 校验处置 is
 * never subtracted from a conclusion count**, and **the exported text keeps the same three
 * separations the screen keeps**. An export that flattened them would be the version that
 * actually reaches the change review.
 */

const baseRow: ValidationReportRow = {
  unitId: 'run-1-unit-1',
  sourceTable: 'order_item',
  targetTable: 'order_item',
  execution: {
    id: 'run-1-unit-1-validation-1',
    unitId: 'run-1-unit-1',
    planVersion: 3,
    startedAt: '2026-09-01T09:00:00.000Z',
    completedAt: '2026-09-01T09:05:00.000Z',
    items: [
      { checkId: 'ROW_COUNT', state: 'PASS', observedAt: '2026-09-01T09:05:00.000Z', detail: null },
      {
        checkId: 'PRIMARY_KEY_TERMINAL_VALUE',
        state: 'NOT_APPLICABLE',
        observedAt: '2026-09-01T09:05:00.000Z',
        detail: '该表没有单调主键。',
      },
      {
        checkId: 'LARGE_RECORD_VALUE_INTEGRITY',
        state: 'NOT_RUN',
        observedAt: null,
        detail: '校验计划里没有启用这一项。',
      },
      {
        checkId: 'VALUE_CHECKSUM_SAMPLE',
        state: 'FAIL',
        observedAt: '2026-09-01T09:05:00.000Z',
        detail: '抽样比对发现 4 行的值与源不一致。',
      },
    ],
  },
  conclusion: 'FAIL',
  unitPhase: 'TERMINAL',
  unitOutcome: 'COMPLETED_WITH_ACCEPTED_RISK',
  disposition: {
    executionId: 'run-1-unit-1-validation-1',
    unitId: 'run-1-unit-1',
    recordedAt: '2026-09-01T10:00:00.000Z',
    accountableOperator: 'zhang.wei',
    reason: '差异已在变更评审中复核。',
    acceptedCheckIds: ['VALUE_CHECKSUM_SAMPLE'],
  },
};

const passingRow: ValidationReportRow = {
  ...baseRow,
  unitId: 'run-1-unit-2',
  sourceTable: 'payment',
  targetTable: 'payment',
  conclusion: 'PASS',
  unitOutcome: 'SUCCEEDED',
  disposition: null,
  execution: {
    ...(baseRow.execution as NonNullable<ValidationReportRow['execution']>),
    id: 'run-1-unit-2-validation-1',
    unitId: 'run-1-unit-2',
    items: [
      { checkId: 'ROW_COUNT', state: 'PASS', observedAt: '2026-09-01T09:05:00.000Z', detail: null },
    ],
  },
};

const report: ValidationReport = {
  runId: 'run-1',
  taskId: 'task-1',
  observedAt: '2026-09-01T11:00:00.000Z',
  runStatus: 'COMPLETED_WITH_ACCEPTED_RISK',
  scope: {
    sourceDatabase: 'orders',
    targetSchema: 'orders_live',
    selectedTableCount: 2,
    excludedTableCount: 1,
    baselineCapturedAt: '2026-09-01T08:00:00.000Z',
  },
  exclusions: [{ sourceTable: 'audit_log', reason: 'PREFLIGHT_UNSUPPORTED' }],
  rows: [baseRow, passingRow],
  validationInFlight: false,
};

describe('summariseValidationReport', () => {
  it('counts a disposed FAIL as a FAIL', () => {
    const summary = summariseValidationReport(report);
    const counts = new Map(
      summary.conclusionCounts.map((entry) => [entry.conclusion, entry.count]),
    );
    expect(counts.get('FAIL')).toBe(1);
    expect(counts.get('PASS')).toBe(1);
    // The decision is counted beside the conclusions, never instead of one of them.
    expect(summary.disposedRowCount).toBe(1);
    expect(summary.openRowCount).toBe(0);
  });

  it('always states all three conclusions, including at zero', () => {
    // A review reads 「INCONCLUSIVE 0」 as a fact and a missing line as nothing at all.
    const stated = summariseValidationReport(report).conclusionCounts.map(
      (entry) => entry.conclusion,
    );
    expect(stated).toContain('PASS');
    expect(stated).toContain('FAIL');
    expect(stated).toContain('INCONCLUSIVE');
  });

  it('counts NOT_APPLICABLE and NOT_RUN apart from each other and from failure', () => {
    const items = new Map(
      summariseValidationReport(report).itemStateCounts.map((e) => [e.state, e.count]),
    );
    expect(items.get('NOT_APPLICABLE')).toBe(1);
    expect(items.get('NOT_RUN')).toBe(1);
    expect(items.get('FAIL')).toBe(1);
  });

  it('offers a decision only where there is a result to decide about', () => {
    expect(isDisposable(baseRow)).toBe(true);
    expect(isDisposable(passingRow)).toBe(false);
  });
});

describe('the exported report', () => {
  const text = formatValidationReport(report);

  it('states the selected 迁移范围 before any conclusion', () => {
    expect(text).toContain('本次迁移运行的选定范围');
    expect(text).toContain('选定表数 2');
    expect(text).toContain('排除表数 1');
    expect(text.indexOf('本次迁移运行的选定范围')).toBeLessThan(text.indexOf('技术结论分布'));
  });

  it('keeps the technical conclusion and the 校验处置 as separate labelled fields', () => {
    expect(text).toContain(`校验执行技术结论 ${messages.conclusion.labels.FAIL}`);
    expect(text).toContain('校验处置 已记录校验处置');
    expect(text).toContain(`技术结论仍然是${messages.conclusion.labels.FAIL}`);
    // Nothing in the export ever says a disposed result passed.
    expect(text).not.toContain(
      `校验执行技术结论 ${messages.conclusion.labels.PASS} · 校验处置 已记录校验处置`,
    );
  });

  it('names the responsible party and the reason', () => {
    expect(text).toContain('责任人 zhang.wei');
    expect(text).toContain('差异已在变更评审中复核。');
  });

  it('lists 预检排除项 as tables that never migrated', () => {
    expect(text).toContain('预检排除项');
    expect(text).toContain('audit_log');
    expect(text).toContain(messages.validation.exclusions.reasons.PREFLIGHT_UNSUPPORTED);
    expect(text).toContain('没有校验执行');
  });

  it('says the validation is unfinished when it is', () => {
    const running = formatValidationReport({ ...report, validationInFlight: true });
    expect(running).toContain('校验尚未跑完');
    expect(text).not.toContain('校验尚未跑完');
  });
});
