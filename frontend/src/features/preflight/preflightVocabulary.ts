import type { PreflightFindingCode, PreflightInconclusiveReason } from '@/contract';
import { messages } from '@/messages';

/**
 * How a 预检发现 says what it found (#42).
 *
 * One module, so the wizard's findings pane and 执行确认's findings table cannot word the
 * same fact two ways. Both halves are `CONTEXT.md` wording rather than the persisted
 * literal: the code has a short name under 「Preflight finding code」, and the `detail` of
 * an `ENVELOPE_SCAN_INCONCLUSIVE` finding is one of the three 「Preflight inconclusive
 * reason」 values — a condition the operator can go and fix, which is only useful if it is
 * said in words they read.
 *
 * Every other finding's `detail` is an exact measured fact (a byte count, a source type),
 * and those are rendered as they are: they are data, not vocabulary.
 */

export function findingLabel(code: PreflightFindingCode): string {
  return messages.wizard.tables.preflight.codeLabels[code];
}

const inconclusiveReasons = messages.wizard.tables.preflight.inconclusiveReasons;

function isInconclusiveReason(detail: string): detail is PreflightInconclusiveReason {
  return Object.prototype.hasOwnProperty.call(inconclusiveReasons, detail);
}

/**
 * The finding's own detail, worded where it is a value rather than a measurement.
 *
 * An unrecognised detail is returned unchanged rather than blanked: it is a fact DBX
 * observed, and dropping it would lose evidence to protect a translation.
 */
export function findingDetail(detail: string): string {
  return isInconclusiveReason(detail) ? inconclusiveReasons[detail] : detail;
}
