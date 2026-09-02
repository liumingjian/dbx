import type {
  IsoTimestamp,
  MigrationRun,
  RunProgressSnapshot,
  TableMigrationUnit,
  ValidationCheckId,
  ValidationConclusion,
  ValidationDisposition,
  ValidationExecution,
  ValidationItemResult,
  ValidationItemState,
  ValidationPlan,
  ValidationReport,
  ValidationReportRow,
} from '@/contract';
import {
  OBSERVATION_INTERVAL_MOCK_MS,
  validationExecutionIdOf,
  type RunPlan,
  type UnitPlan,
} from './runProgress';

/**
 * 校验计划, 校验执行 and 校验报告, as the mock states them (#40).
 *
 * Everything here is a projection of the run plan and the clock, exactly like the progress
 * it sits beside — and that is the load-bearing property, not a stylistic one. **A
 * 校验执行 in this mock is derived from immutable inputs and is therefore not writable at
 * all**: there is no code path, here or in the store, through which recording a 校验处置
 * could reach an item's state. The separation `CONTEXT.md` demands — 「a later execution or
 * disposition never rewrites the original result」 — is thus structural rather than a rule
 * somebody has to remember.
 *
 * What a disposition *can* do is close the workflow: the store passes the disposed units to
 * `withDispositions`, which grants the unit `COMPLETED_WITH_ACCEPTED_RISK`. That is an
 * outcome, not a conclusion, and it is not `SUCCEEDED`.
 */

/** The version of the 校验计划 this mock's runs were configured with. */
export const VALIDATION_PLAN_VERSION = 3;

/** The checks, in the order the report lists them. */
export const VALIDATION_CHECK_IDS: readonly ValidationCheckId[] = [
  'ROW_COUNT',
  'PRIMARY_KEY_TERMINAL_VALUE',
  'NULL_CONSTRAINT_CONFORMANCE',
  'VALUE_CHECKSUM_SAMPLE',
  'LARGE_RECORD_VALUE_INTEGRITY',
];

/** The one check whose result decides a non-`PASS` conclusion in these fixtures. */
const FALTERING_CHECK: ValidationCheckId = 'VALUE_CHECKSUM_SAMPLE';

/**
 * The 校验计划 of one table: 「the immutable, versioned set of **enabled, disabled, and
 * not-applicable** checks … fixed before execution」.
 *
 * The three categories are what make `NOT_RUN` and `NOT_APPLICABLE` different facts rather
 * than two words for absence. A disabled check did not run because the plan says so; a
 * not-applicable check could never have run, and the plan says why. Neither is a failure,
 * and a DBA must not be sent chasing either.
 */
export function validationPlanOf(unit: UnitPlan): ValidationPlan {
  return {
    version: VALIDATION_PLAN_VERSION,
    items: VALIDATION_CHECK_IDS.map((checkId) => {
      if (checkId === 'PRIMARY_KEY_TERMINAL_VALUE' && !unit.hasMonotonicPrimaryKey) {
        return {
          checkId,
          enabled: true,
          notApplicableReason: '该表没有单调主键，源基线里没有可比对的终值。',
        };
      }
      return {
        checkId,
        enabled: checkId !== 'LARGE_RECORD_VALUE_INTEGRITY' || unit.largeRecordTable,
        notApplicableReason: null,
      };
    }),
  };
}

function detailOf(
  checkId: ValidationCheckId,
  state: ValidationItemState,
  unit: UnitPlan,
): string | null {
  if (state === 'PASS') {
    return checkId === 'ROW_COUNT' ? `目标表 ${unit.baselineRowCount} 行，与源基线一致。` : null;
  }
  if (state === 'FAIL') {
    return '抽样比对发现 4 行的值与源不一致。';
  }
  if (state === 'INCONCLUSIVE') {
    return '抽样比对没有完成：读取源端样本时连接中断，DBX 无法判定这一项。';
  }
  return null;
}

/**
 * One retained attempt, projected at quantum `q`.
 *
 * A running attempt has **no** conclusion and says so by leaving `completedAt` null: every
 * item is `NOT_RUN` until the attempt concludes, and the report renders 校验进行中 rather
 * than a verdict assembled from whatever has landed so far.
 */
export function validationExecutionOf(
  unit: UnitPlan,
  q: number,
  at: (quantum: number) => IsoTimestamp,
): ValidationExecution {
  const plan = validationPlanOf(unit);
  const concluded = q >= unit.validationEndsAt;
  const items: readonly ValidationItemResult[] = plan.items.map((planItem) => {
    const state: ValidationItemState = !concluded
      ? 'NOT_RUN'
      : planItem.notApplicableReason !== null
        ? 'NOT_APPLICABLE'
        : !planItem.enabled
          ? 'NOT_RUN'
          : planItem.checkId === FALTERING_CHECK && unit.validationConclusion !== 'PASS'
            ? (unit.validationConclusion as ValidationItemState)
            : 'PASS';
    return {
      checkId: planItem.checkId,
      state,
      observedAt: concluded && state !== 'NOT_RUN' ? at(unit.validationEndsAt) : null,
      detail:
        planItem.notApplicableReason ??
        (!planItem.enabled && concluded ? '校验计划里没有启用这一项。' : null) ??
        detailOf(planItem.checkId, state, unit),
    };
  });

  return {
    id: validationExecutionIdOf(unit.id),
    unitId: unit.id,
    planVersion: plan.version,
    startedAt: at(unit.transferEndsAt),
    completedAt: concluded ? at(unit.validationEndsAt) : null,
    items,
  };
}

/**
 * The technical conclusion of one table, from its 校验执行 and nothing else.
 *
 * The precedence is the domain's: a `FAIL` outranks an `INCONCLUSIVE`, which outranks a
 * `PASS`, and a table whose every check was disabled or not applicable concluded nothing
 * technical at all. `NOT_APPLICABLE` and `NOT_RUN` never contribute a pass, and the
 * disposition is not an input to this function — it is not even in scope.
 */
export function conclusionOf(
  execution: ValidationExecution | null,
  unit: TableMigrationUnit,
): ValidationConclusion {
  if (execution === null) {
    // 「没跑」 and 「还没跑完」 are different facts: a table DBX has stopped working on will
    // never have an execution, while one still moving simply has not reached one yet.
    return unit.phase === 'TERMINAL' ? 'NOT_RUN' : 'IN_FLIGHT';
  }
  if (execution.completedAt === null) {
    return 'IN_FLIGHT';
  }
  const states = execution.items.map((item) => item.state);
  if (states.includes('FAIL')) {
    return 'FAIL';
  }
  if (states.includes('INCONCLUSIVE')) {
    return 'INCONCLUSIVE';
  }
  return states.includes('PASS') ? 'PASS' : 'NOT_APPLICABLE';
}

export interface ValidationReportOptions {
  readonly run: MigrationRun;
  readonly plan: RunPlan;
  readonly snapshot: RunProgressSnapshot;
  /** Recorded 校验处置, keyed by 表迁移单元. Read-only input; never written back. */
  readonly dispositions: ReadonlyMap<string, ValidationDisposition>;
}

/** The quantum the snapshot was observed at, recovered from its own instants. */
function quantumOf(snapshot: RunProgressSnapshot, run: MigrationRun): number {
  return Math.round(
    (Date.parse(snapshot.observedAt) - Date.parse(run.startedAt)) / OBSERVATION_INTERVAL_MOCK_MS,
  );
}

/**
 * 校验报告 for one 迁移运行.
 *
 * Assembled from the same snapshot 运行监控 is looking at, so the two can never disagree
 * about what a table did — and assembled at one instant, so a change reviewer reads one
 * moment rather than a collage of several.
 */
export function buildValidationReport({
  run,
  plan,
  snapshot,
  dispositions,
}: ValidationReportOptions): ValidationReport {
  const q = quantumOf(snapshot, run);
  const startedAtMs = Date.parse(run.startedAt);
  const at = (quantum: number): IsoTimestamp =>
    new Date(startedAtMs + quantum * OBSERVATION_INTERVAL_MOCK_MS).toISOString();

  const plansByUnitId = new Map(plan.units.map((unit) => [unit.id, unit]));

  const rows: readonly ValidationReportRow[] = snapshot.units.map((unit) => {
    const unitPlan = plansByUnitId.get(unit.id);
    const execution =
      unitPlan === undefined || unit.latestValidationExecutionId === null
        ? null
        : validationExecutionOf(unitPlan, q, at);
    return {
      unitId: unit.id,
      sourceTable: unit.sourceTable,
      targetTable: unit.targetTable,
      execution,
      conclusion: conclusionOf(execution, unit),
      unitPhase: unit.phase,
      unitOutcome: unit.outcome,
      disposition: dispositions.get(unit.id) ?? null,
    };
  });

  return {
    runId: run.id,
    taskId: run.taskId,
    observedAt: snapshot.observedAt,
    runStatus: snapshot.run.status,
    scope: {
      sourceDatabase: run.sourceDatabase,
      targetSchema: run.targetSchema,
      selectedTableCount: run.selectedTableCount,
      excludedTableCount: run.excludedTableCount,
      baselineCapturedAt: run.sourceBaseline.capturedAt,
    },
    exclusions: plan.exclusions,
    rows,
    validationInFlight: rows.some((row) => row.conclusion === 'IN_FLIGHT'),
  };
}

/**
 * The checks whose risk a 校验处置 is accepting: the ones that failed or could not be
 * judged.
 *
 * Listing them is what keeps the decision auditable — an operator accepts *these* results,
 * not 「the table」 — and it is also why the disposition cannot be mistaken for a verdict:
 * it names the results it leaves standing.
 */
export function acceptedCheckIdsOf(execution: ValidationExecution): readonly ValidationCheckId[] {
  return execution.items
    .filter((item) => item.state === 'FAIL' || item.state === 'INCONCLUSIVE')
    .map((item) => item.checkId);
}
