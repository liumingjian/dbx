/**
 * The conclusion → indicator mapping.
 *
 * This module is the **single site in DBX that knows which indicator carries which
 * conclusion** (ADR-0014, #30 Implementation Decisions). Nothing else may hard-code an
 * indicator kind: a second conditional somewhere in a view is exactly how `INCONCLUSIVE`
 * ends up rendered as a caution in one screen and as a failure in another.
 *
 * The most important row of the table is `INCONCLUSIVE` → `unknown`. It is deliberately
 * not a caution variant, because 「无法判定」 must not be read as 「有点风险但可以过」.
 */
import type {
  ConnectionCheckOutcome,
  MigrationRunStatus,
  PreflightConclusion,
  TableMigrationOutcome,
  ValidationItemState,
} from '@/contract';

/**
 * The kinds of `IconIndicator` DBX uses. Declared here rather than imported from Carbon so
 * that the mapping is a DBX fact: swapping the indicator component is then a change in
 * `ConclusionIndicator`, not a change of meaning.
 */
export type ConclusionIndicatorKind =
  | 'succeeded'
  | 'failed'
  | 'unknown'
  | 'in-progress'
  | 'not-started'
  | 'undefined'
  | 'caution-major';

/**
 * The closed set of judgements DBX renders as an indicator.
 *
 * It is the union of the preflight conclusions, the validation item states, and the two
 * cross-cutting conditions that are not either: a unit that is in flight, and a 卡死
 * diagnosis. `ArePreflightConclusionsCovered` below fails to compile if the contract ever
 * grows a conclusion this table does not answer for.
 */
export type DbxConclusion =
  | PreflightConclusion
  | ValidationItemState
  /** 执行中 — the work is under way, so no judgement has been reached yet. */
  | 'IN_FLIGHT'
  /** 卡死 (`CONTEXT.md`, ADR-0004): a terminal diagnosis, not a slow table. */
  | 'STUCK';

/**
 * The mapping, fixed by #30 and restated in #33's acceptance criteria.
 *
 * `NOT_APPLICABLE` → `undefined` and `INCONCLUSIVE` → `unknown` are separate rows on
 * purpose: 「规则说这项不适用」 and 「DBX 无法判定」 are different facts, and ADR-0004
 * forbids recording one as the other.
 */
export const conclusionIndicatorKind: Readonly<Record<DbxConclusion, ConclusionIndicatorKind>> = {
  SUPPORTED: 'succeeded',
  PASS: 'succeeded',
  UNSUPPORTED: 'failed',
  FAIL: 'failed',
  INCONCLUSIVE: 'unknown',
  IN_FLIGHT: 'in-progress',
  NOT_RUN: 'not-started',
  NOT_APPLICABLE: 'undefined',
  STUCK: 'caution-major',
};

/** Every conclusion DBX can render, in the order the mapping table states them. */
export const dbxConclusions: readonly DbxConclusion[] = [
  'SUPPORTED',
  'PASS',
  'UNSUPPORTED',
  'FAIL',
  'INCONCLUSIVE',
  'IN_FLIGHT',
  'NOT_RUN',
  'NOT_APPLICABLE',
  'STUCK',
];

/**
 * A migration run's status is a deterministic projection of its units (ADR-0004) rather
 * than a conclusion of its own, so it is translated into the conclusion vocabulary here —
 * in the one module that owns the mapping — instead of growing a second indicator table.
 *
 * Two of these rows are judgements rather than mechanics, and are written down as such:
 *
 *  - `ATTENTION_REQUIRED` is the run-level projection of a unit that has stopped making
 *    observable progress, which is what 卡死 names;
 *  - `COMPLETED_WITH_ACCEPTED_RISK` is **not** `succeeded`. A 校验处置 closes the workflow
 *    and never turns the technical result into a pass (`CONTEXT.md`, ADR-0004), so the run
 *    carries the indicator of a judgement DBX could not reach.
 *  - `CANCELLED` carries `NOT_APPLICABLE`: no technical conclusion applies to a run an
 *    operator stopped.
 */
const runStatusConclusions: Readonly<Record<MigrationRunStatus, DbxConclusion>> = {
  PREPARING: 'IN_FLIGHT',
  RUNNING: 'IN_FLIGHT',
  CANCELLING: 'IN_FLIGHT',
  ATTENTION_REQUIRED: 'STUCK',
  COMPLETED: 'PASS',
  COMPLETED_WITH_FAILURES: 'FAIL',
  COMPLETED_WITH_ACCEPTED_RISK: 'INCONCLUSIVE',
  CANCELLED: 'NOT_APPLICABLE',
};

export function migrationRunConclusion(status: MigrationRunStatus): DbxConclusion {
  return runStatusConclusions[status];
}

/**
 * One 表迁移单元's technical result, as an indicator (#38).
 *
 * Three rows carry a judgement and are written down as such:
 *
 *  - `BLOCKED_BY_BOX_FAILURE` — 因关联失败而阻塞 — is **not** a failure. `CONTEXT.md`:
 *    「Its own technical result is undetermined rather than failed, and it is a candidate
 *    for re-migration」, so it takes the indicator of a judgement DBX could not reach.
 *  - `CANCELLED` and `SKIPPED` carry `NOT_APPLICABLE`: no technical conclusion applies to
 *    a table an operator stopped or excluded.
 *  - `COMPLETED_WITH_ACCEPTED_RISK` is not a pass. A 校验处置 closes the workflow and
 *    never overwrites the technical result (ADR-0004).
 *
 * A unit with no outcome yet is `IN_FLIGHT`. 卡死 is **not** in this table at all, because
 * ADR-0004 makes `STUCK` a diagnosis about a scheduling group rather than a table outcome
 * — `tableMigrationConclusion`'s caller passes it separately, from the run's diagnosis.
 */
const tableMigrationOutcomeConclusions: Readonly<Record<TableMigrationOutcome, DbxConclusion>> = {
  SUCCEEDED: 'PASS',
  FAILED: 'FAIL',
  BLOCKED_BY_BOX_FAILURE: 'INCONCLUSIVE',
  SKIPPED: 'NOT_APPLICABLE',
  CANCELLED: 'NOT_APPLICABLE',
  COMPLETED_WITH_ACCEPTED_RISK: 'INCONCLUSIVE',
};

/**
 * @param outcome the unit's one outcome, or null while it has not reached one.
 * @param stalled whether the run's 卡死 diagnosis names this unit as having stopped.
 */
export function tableMigrationConclusion(
  outcome: TableMigrationOutcome | null,
  stalled = false,
): DbxConclusion {
  if (stalled) {
    return 'STUCK';
  }
  return outcome === null ? 'IN_FLIGHT' : tableMigrationOutcomeConclusions[outcome];
}

/**
 * A connection check concludes whether an endpoint and identity are usable right now
 * (ADR-0006). It is a judgement, so it is rendered as one — through the same table, rather
 * than through a second indicator vocabulary invented for 数据源.
 */
const connectionCheckConclusions: Readonly<Record<ConnectionCheckOutcome, DbxConclusion>> = {
  SUCCEEDED: 'PASS',
  FAILED: 'FAIL',
  NOT_RUN: 'NOT_RUN',
};

export function connectionCheckConclusion(outcome: ConnectionCheckOutcome): DbxConclusion {
  return connectionCheckConclusions[outcome];
}

/**
 * Compile-time proof that the contract's own conclusion vocabularies are fully covered.
 * If `PreflightConclusion` or `ValidationItemState` gains a member, these stop compiling.
 */
type Covered<T extends DbxConclusion> = T;
export type ArePreflightConclusionsCovered = Covered<PreflightConclusion>;
export type AreValidationItemStatesCovered = Covered<ValidationItemState>;
