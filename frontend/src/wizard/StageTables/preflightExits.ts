import type { DbxConclusion } from '@/conclusions';
import type { Preflight, PreflightConclusion } from '@/contract';

/**
 * The three exits ADR-0003 gives a blocked 预检, and what a conclusion means for the stage
 * around it — as pure functions, so the claims can be tested without a browser.
 *
 * The conclusion→**visual form** rules are not here. They live in `src/conclusions/`, which
 * is the single site that knows which form carries which conclusion: `INCONCLUSIVE` →
 * `unknown` for the indicator and `info` for the notification are two halves of one rule,
 * and keeping them in two modules is how they end up disagreeing.
 */

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
