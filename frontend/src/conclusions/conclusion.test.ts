import { describe, expect, it } from 'vitest';
import type { MigrationRunStatus, TableMigrationOutcome } from '@/contract';
import {
  conclusionIndicatorKind,
  dbxConclusions,
  migrationRunConclusion,
  tableMigrationConclusion,
  type DbxConclusion,
} from './conclusion';

describe('conclusion → indicator mapping', () => {
  it('is the mapping #30 fixed, row for row', () => {
    expect(conclusionIndicatorKind).toEqual({
      SUPPORTED: 'succeeded',
      PASS: 'succeeded',
      UNSUPPORTED: 'failed',
      FAIL: 'failed',
      INCONCLUSIVE: 'unknown',
      IN_FLIGHT: 'in-progress',
      NOT_RUN: 'not-started',
      NOT_APPLICABLE: 'undefined',
      STUCK: 'caution-major',
    });
  });

  it('never lets 无法判定 read as a caution', () => {
    // The most important row of the table: `INCONCLUSIVE` maps to `unknown` and to no
    // caution variant, so "DBX cannot judge this" is never read as "a bit risky but fine".
    expect(conclusionIndicatorKind.INCONCLUSIVE).toBe('unknown');
    expect(conclusionIndicatorKind.INCONCLUSIVE).not.toContain('caution');
  });

  it('reserves caution-major for 卡死 alone', () => {
    const cautionary = dbxConclusions.filter((conclusion) =>
      conclusionIndicatorKind[conclusion].startsWith('caution'),
    );
    expect(cautionary).toEqual(['STUCK']);
  });

  it('keeps NOT_APPLICABLE and INCONCLUSIVE apart', () => {
    // ADR-0004: a check the validation specification requires but DBX cannot prove is
    // `INCONCLUSIVE`, never `NOT_APPLICABLE`. The indicators must not collapse them either.
    expect(conclusionIndicatorKind.NOT_APPLICABLE).not.toBe(conclusionIndicatorKind.INCONCLUSIVE);
  });

  it('answers for every conclusion it declares, and declares no others', () => {
    expect([...dbxConclusions].sort()).toEqual(
      (Object.keys(conclusionIndicatorKind) as DbxConclusion[]).sort(),
    );
  });

  it('translates every migration run status through the same table', () => {
    const statuses: MigrationRunStatus[] = [
      'PREPARING',
      'RUNNING',
      'ATTENTION_REQUIRED',
      'CANCELLING',
      'COMPLETED',
      'COMPLETED_WITH_FAILURES',
      'COMPLETED_WITH_ACCEPTED_RISK',
      'CANCELLED',
    ];
    for (const status of statuses) {
      expect(dbxConclusions).toContain(migrationRunConclusion(status));
    }
    // Accepting risk closes the workflow but never turns the technical result into a
    // pass (`CONTEXT.md`), so the run must not borrow the succeeded indicator.
    expect(
      conclusionIndicatorKind[migrationRunConclusion('COMPLETED_WITH_ACCEPTED_RISK')],
    ).not.toBe('succeeded');
    expect(migrationRunConclusion('COMPLETED')).toBe('PASS');
  });

  it('never reads 因关联失败而阻塞 as a failure', () => {
    // `CONTEXT.md`: a unit blocked by an upstream failure has an 「own technical result …
    // undetermined rather than failed, and it is a candidate for re-migration」. Drawing it
    // as a failure would put blame on a table that did nothing wrong, and would hide the
    // one property a DBA needs from it — that it is worth migrating again.
    expect(tableMigrationConclusion('BLOCKED_BY_BOX_FAILURE')).toBe('INCONCLUSIVE');
    expect(conclusionIndicatorKind[tableMigrationConclusion('BLOCKED_BY_BOX_FAILURE')]).not.toBe(
      'failed',
    );
    expect(tableMigrationConclusion('FAILED')).toBe('FAIL');
  });

  it('keeps 卡死 out of the outcome table and reaches it only from the diagnosis', () => {
    // ADR-0004: 「STUCK is deliberately not a table outcome. It is a terminal box
    // diagnosis.」 So no outcome maps to it; the run's diagnosis is what names the table.
    const outcomes: TableMigrationOutcome[] = [
      'SUCCEEDED',
      'FAILED',
      'BLOCKED_BY_BOX_FAILURE',
      'SKIPPED',
      'CANCELLED',
      'COMPLETED_WITH_ACCEPTED_RISK',
    ];
    for (const outcome of outcomes) {
      expect(tableMigrationConclusion(outcome)).not.toBe('STUCK');
    }
    expect(tableMigrationConclusion(null)).toBe('IN_FLIGHT');
    // A stalled table has no outcome at all, and 卡死 is what it is shown as.
    expect(tableMigrationConclusion(null, true)).toBe('STUCK');
    expect(conclusionIndicatorKind[tableMigrationConclusion(null, true)]).toBe('caution-major');
  });

  it('gives a cancelled or skipped table no technical conclusion at all', () => {
    expect(tableMigrationConclusion('CANCELLED')).toBe('NOT_APPLICABLE');
    expect(tableMigrationConclusion('SKIPPED')).toBe('NOT_APPLICABLE');
    // Accepting risk never becomes a pass.
    expect(
      conclusionIndicatorKind[tableMigrationConclusion('COMPLETED_WITH_ACCEPTED_RISK')],
    ).not.toBe('succeeded');
  });
});
