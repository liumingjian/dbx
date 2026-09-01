import { describe, expect, it } from 'vitest';
import type { MigrationRunStatus } from '@/contract';
import {
  conclusionIndicatorKind,
  dbxConclusions,
  migrationRunConclusion,
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
});
