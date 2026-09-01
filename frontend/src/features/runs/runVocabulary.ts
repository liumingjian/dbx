import type {
  RootCauseDomain,
  RunEvent,
  TableMigrationOutcome,
  TableMigrationPhase,
} from '@/contract';
import { formatCount } from '@/format/display';
import { messages } from '@/messages';

/**
 * How 运行监控 says what the state machine records (#38, Gate 7).
 *
 * One module, so that no view builds its own conditional and the two decided translations
 * cannot exist in two versions:
 *
 *  - `WAITING_FOR_BOX` → **等待调度**, an ordinary waiting state carrying no diagnosis;
 *  - `BLOCKED_BY_BOX_FAILURE` → **因关联失败而阻塞**, a technical result that is
 *    undetermined rather than failed;
 *  - 根因域 `Kafka Connect` and `Kafka` → the single **迁移平台** domain.
 *
 * Everything else is rendered as its enum literal, because `CONTEXT.md` carries no `_中文_`
 * line for it and the repository rule is to add the word there first. The three above are
 * the exception precisely because the fallback is unavailable for them: rendering those
 * literals would put the execution platform's own vocabulary on the operator's screen,
 * which is the one thing Gate 7 forbids.
 */

export function phaseLabel(phase: TableMigrationPhase): string {
  return messages.run.phases[phase];
}

export function outcomeLabel(outcome: TableMigrationOutcome): string {
  return messages.run.outcomes[outcome];
}

/**
 * The 根因域 as the operator sees it.
 *
 * The contract keeps the specific domain — `CONTEXT.md` requires it to be 「retained in the
 * diagnostic evidence for support use」 — and this is the only place that decides what is
 * shown instead. #39's evidence drawer presents the same domain through the same function.
 */
export function presentRootCauseDomain(domain: RootCauseDomain): string {
  return messages.run.rootCauseDomains[domain];
}

/** One timeline entry, in the vocabulary above. */
export function runEventLabel(event: RunEvent): string {
  const copy = messages.run.events.types;
  switch (event.type) {
    case 'PHASE_ENTERED':
      return copy.phaseEntered(event.phase === null ? '' : phaseLabel(event.phase));
    case 'OUTCOME_RECORDED':
      return copy.outcomeRecorded(event.outcome === null ? '' : outcomeLabel(event.outcome));
    case 'RUN_STATUS_CHANGED':
      return copy.runStatusChanged(
        event.runStatus === null ? '' : messages.tasks.runStatuses[event.runStatus],
      );
    case 'STUCK_DIAGNOSED':
      return copy.stuckDiagnosed;
    case 'CANCELLATION_REQUESTED':
      return copy.cancellationRequested;
  }
}

/** Durations are quoted in minutes: the hard threshold behind 卡死 is stated in them. */
export function formatMinutes(milliseconds: number): string {
  return formatCount(Math.round(milliseconds / 60_000));
}
