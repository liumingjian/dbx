import { describe, expect, it } from 'vitest';
import { conclusionIndicatorKind } from '@/conclusions';
import type { Preflight } from '@/contract';
import {
  preflightBlocks,
  preflightIndicatorConclusion,
  preflightNoticeKind,
  prunableColumnsOf,
} from './preflightExits';

/**
 * The judgement rules of 预检, tested where they are decided rather than through a screen.
 *
 * These are the claims #30 says the stage exists to make, so they are asserted as rules:
 * `INCONCLUSIVE` never becomes a caution, an unfinished scan never reads as a pass, and an
 * exit is only offered where it can actually work.
 */

function preflight(overrides: Partial<Preflight> = {}): Preflight {
  return {
    conclusion: 'SUPPORTED',
    evaluatedAt: '2026-09-01T09:00:00.000Z',
    findings: [],
    largeRecordTable: false,
    largestValueBytes: null,
    largestRowBytes: null,
    ...overrides,
  };
}

describe('预检 as the interface reads it', () => {
  it('never draws 无法判定 as a caution', () => {
    // The whole point of #36: 「无法判定」 read as 「有点风险但可以过」 is the misreading
    // that lets an unprovable table into a production migration.
    expect(preflightNoticeKind('INCONCLUSIVE')).toBe('info');
    expect(preflightNoticeKind('INCONCLUSIVE')).not.toContain('warning');
    expect(preflightNoticeKind('INCONCLUSIVE')).not.toContain('caution');
    // And the indicator side of the same rule, taken from the product's one mapping.
    expect(conclusionIndicatorKind.INCONCLUSIVE).toBe('unknown');
  });

  it('keeps 无法迁移 and 无法确认是否可迁移 in different forms', () => {
    expect(preflightNoticeKind('UNSUPPORTED')).toBe('error');
    expect(preflightNoticeKind('UNSUPPORTED')).not.toBe(preflightNoticeKind('INCONCLUSIVE'));
  });

  it('renders a scan that has not concluded as 执行中, not as a conclusion', () => {
    expect(preflightIndicatorConclusion(null)).toBe('IN_FLIGHT');
    expect(conclusionIndicatorKind[preflightIndicatorConclusion(null)]).toBe('in-progress');
    expect(preflightIndicatorConclusion('SUPPORTED')).toBe('SUPPORTED');
  });

  it('blocks on anything that is not SUPPORTED, including a missing conclusion', () => {
    expect(preflightBlocks(preflight())).toBe(false);
    expect(preflightBlocks(preflight({ conclusion: 'UNSUPPORTED' }))).toBe(true);
    expect(preflightBlocks(preflight({ conclusion: 'INCONCLUSIVE' }))).toBe(true);
    expect(preflightBlocks(preflight({ conclusion: null, evaluatedAt: null }))).toBe(true);
  });

  it('blocks a SUPPORTED conclusion that still carries a blocking finding', () => {
    // A contradiction is refused rather than resolved in the permissive direction.
    expect(
      preflightBlocks(
        preflight({
          findings: [
            { code: 'VALUE_DOMAIN_OUT_OF_RANGE', sourceColumn: 'amount', blocking: true, detail: '' },
          ],
        }),
      ),
    ).toBe(true);
  });

  it('offers 裁剪 only for the coordinates a block is actually attributed to', () => {
    const columns = prunableColumnsOf(
      preflight({
        conclusion: 'UNSUPPORTED',
        findings: [
          { code: 'LARGE_RECORD_VALUE', sourceColumn: 'payload', blocking: true, detail: '1' },
          { code: 'LARGE_RECORD_ROW', sourceColumn: null, blocking: true, detail: '2' },
          { code: 'LARGE_RECORD_VALUE', sourceColumn: 'payload', blocking: true, detail: '1' },
          { code: 'VALUE_DOMAIN_OUT_OF_RANGE', sourceColumn: 'remark', blocking: false, detail: '' },
        ],
      }),
    );
    // Named and blocking only, each once: a finding about the whole row names no column to
    // cut, and cutting a column no block is attributed to would be damage without a reason.
    expect(columns).toEqual(['payload']);
  });

  it('offers no 裁剪 at all for a scan that could not conclude', () => {
    // ADR-0003: an inconclusive envelope scan 「cannot be overridden into a runnable
    // table」, and cutting a column is not what fixes a timeout or a missing permission.
    expect(
      prunableColumnsOf(
        preflight({
          conclusion: 'INCONCLUSIVE',
          findings: [
            {
              code: 'ENVELOPE_SCAN_INCONCLUSIVE',
              sourceColumn: null,
              blocking: true,
              detail: 'QUERY_TIMEOUT',
            },
          ],
        }),
      ),
    ).toEqual([]);
  });
});
