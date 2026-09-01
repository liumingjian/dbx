import type { DbxConclusion } from '@/conclusions';
import type { Preflight, PreflightConclusion } from '@/contract';

/**
 * What a 预检 conclusion means for the interface around it — as pure functions, so the
 * claims can be tested without a browser and cannot drift between views.
 *
 * The one this module exists for is `preflightNoticeKind`. `INCONCLUSIVE` gets its own
 * form and it is **not a caution**: drawing 「无法判定」 as a warning teaches the operator
 * to read it as 「有点风险但可以过」, which is the single misreading #30 says this stage
 * exists to prevent. The indicator side of that is already fixed in `src/conclusions/`
 * (`INCONCLUSIVE` → `unknown`); this is the notification side of the same rule, and it is
 * written here rather than inline in a component so a future edit has to argue with a test.
 */

/**
 * The Carbon notification kinds DBX allows a 预检 to use.
 *
 * Deliberately two, and deliberately neither of Carbon's `warning` variants. `error` says
 * 「无法迁移」; `info` says 「无法确认是否可迁移」. There is no kind for 「大概可以」 because
 * there is no such conclusion.
 */
export type PreflightNoticeKind = 'error' | 'info';

export function preflightNoticeKind(conclusion: PreflightConclusion): PreflightNoticeKind {
  return conclusion === 'INCONCLUSIVE' ? 'info' : 'error';
}

/** The conclusion to render, where a scan still running is a state of its own. */
export function preflightIndicatorConclusion(
  conclusion: PreflightConclusion | null,
): DbxConclusion {
  return conclusion ?? 'IN_FLIGHT';
}

/**
 * Whether this 预检 stops the table being approved.
 *
 * `CONTEXT.md`: 「only `SUPPORTED` may proceed」. A missing conclusion blocks too — the scan
 * has not finished, and an unknown safety fact is not a satisfied one.
 */
export function preflightBlocks(preflight: Preflight): boolean {
  return (
    preflight.conclusion !== 'SUPPORTED' || preflight.findings.some((finding) => finding.blocking)
  );
}

/**
 * The columns ADR-0003's second exit can act on.
 *
 * Only a *blocking* finding that names a source coordinate: cutting a column that no block
 * is attributed to would be damage without a reason, and an exit offered where it cannot
 * work is worse than one that is honestly unavailable.
 */
export function prunableColumnsOf(preflight: Preflight): readonly string[] {
  const columns: string[] = [];
  for (const finding of preflight.findings) {
    if (
      finding.blocking &&
      finding.sourceColumn !== null &&
      !columns.includes(finding.sourceColumn)
    ) {
      columns.push(finding.sourceColumn);
    }
  }
  return columns;
}
