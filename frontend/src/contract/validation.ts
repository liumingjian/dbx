import type {
  IsoTimestamp,
  MigrationRunId,
  MigrationTaskId,
  TableMigrationUnitId,
  ValidationExecutionId,
} from './primitives';
import type { MigrationRunStatus } from './migrationRun';
import type { TableMigrationOutcome, TableMigrationPhase } from './tableMigrationUnit';

/**
 * Validation executions are retained attempts; a later execution or a disposition never
 * rewrites an earlier technical result (`CONTEXT.md`, ADR-0004).
 *
 * The whole file turns on one sentence of the glossary: a 校验处置 「may close the workflow
 * but never changes the technical validation result to passed」, and `Manual pass,
 * overridden result` sits under its `_Avoid_`. So the technical result and the disposition
 * are **two records that cannot collapse into one** — a `ValidationExecution` has no field
 * a disposition could write, and a `ValidationDisposition` has no field that could state a
 * conclusion. Nothing in the audit chain is worth anything if that stops being true: every
 * piece of evidence produced earlier in the product exists to support this one report.
 */

/**
 * The checks a 校验计划 can contain in v1.
 *
 * A closed union rather than a free string, for the same reason `PreflightFindingCode` is
 * one: the wording belongs in `src/messages` and a code is what keeps the mock, the
 * contract and that copy from drifting apart.
 */
export type ValidationCheckId =
  /** The target table holds the 源基线's exact row count. */
  | 'ROW_COUNT'
  /** The terminal value of the monotonic primary key matches the 源基线. */
  | 'PRIMARY_KEY_TERMINAL_VALUE'
  /** Every target column declared `NOT NULL` by the 表写入契约 holds no null. */
  | 'NULL_CONSTRAINT_CONFORMANCE'
  /** A sampled value-level comparison between source and target. */
  | 'VALUE_CHECKSUM_SAMPLE'
  /** Byte-level integrity of the values that made this a 大记录表. */
  | 'LARGE_RECORD_VALUE_INTEGRITY';

export type ValidationItemState = 'PASS' | 'FAIL' | 'INCONCLUSIVE' | 'NOT_APPLICABLE' | 'NOT_RUN';

/**
 * What the report says about one table's 校验执行 as a whole.
 *
 * `IN_FLIGHT` is **not** an item state and never becomes one: it says the attempt has not
 * concluded, which is a different fact from every conclusion below it. Presenting an
 * unfinished execution as `NOT_RUN` — or worse, as `PASS` because nothing has failed yet —
 * is exactly the half-finished conclusion this report may not show.
 */
export type ValidationConclusion = ValidationItemState | 'IN_FLIGHT';

/** The immutable, versioned set of enabled, disabled and not-applicable checks. */
export interface ValidationPlanItem {
  readonly checkId: ValidationCheckId;
  readonly enabled: boolean;
  /** Only a rule in the versioned plan may classify a check as not applicable. */
  readonly notApplicableReason: string | null;
}

export interface ValidationPlan {
  readonly version: number;
  readonly items: readonly ValidationPlanItem[];
}

export interface ValidationItemResult {
  readonly checkId: ValidationCheckId;
  readonly state: ValidationItemState;
  readonly observedAt: IsoTimestamp | null;
  readonly detail: string | null;
}

export interface ValidationExecution {
  readonly id: ValidationExecutionId;
  readonly unitId: TableMigrationUnitId;
  readonly planVersion: number;
  readonly startedAt: IsoTimestamp;
  /** Null while the attempt is still running. A running attempt has no conclusion. */
  readonly completedAt: IsoTimestamp | null;
  readonly items: readonly ValidationItemResult[];
}

/**
 * An operator's audited decision about a failed or inconclusive validation result.
 * Accepting risk may close the workflow but never turns the technical result into `PASS`,
 * which is why this is a separate record rather than a mutation of the execution.
 *
 * There is deliberately **no** conclusion field here. A disposition names who decided,
 * when, why, and which failed or inconclusive checks the risk was accepted for — and it
 * cannot express a technical judgement at all, because it is not one.
 */
export interface ValidationDisposition {
  readonly executionId: ValidationExecutionId;
  readonly unitId: TableMigrationUnitId;
  readonly recordedAt: IsoTimestamp;
  /** The named person answerable for the decision. Never blank, never a role. */
  readonly accountableOperator: string;
  readonly reason: string;
  /** The item results whose risk was accepted; their states stay unchanged. */
  readonly acceptedCheckIds: readonly ValidationCheckId[];
}

/** What an operator sends when they accept the risk of one table's validation result. */
export interface RecordValidationDispositionRequest {
  readonly unitId: TableMigrationUnitId;
  readonly reason: string;
  readonly accountableOperator: string;
}

/**
 * Why a source table of the 迁移任务 is not in this run's 迁移范围.
 *
 * 「没迁」 and 「迁了但没过」 are different facts, and a report that mixes them tells a
 * change reviewer that a table was checked when it never was. The reasons are codes rather
 * than server prose, like every other reason in this contract.
 */
export type ScopeExclusionReason =
  /** 「显式排除是可复核的例外」: the operator took the table out of the 迁移范围. */
  | 'OPERATOR_EXCLUDED'
  /** 预检 concluded `UNSUPPORTED`, and only `SUPPORTED` may proceed (`CONTEXT.md`). */
  | 'PREFLIGHT_UNSUPPORTED'
  /** 预检 could not conclude, which is not a conclusion in DBX's favour either. */
  | 'PREFLIGHT_INCONCLUSIVE';

export interface ScopeExclusion {
  readonly sourceTable: string;
  readonly reason: ScopeExclusionReason;
}

/**
 * One table of the run, as the 校验报告 states it.
 *
 * `conclusion` is read from the 校验执行 **alone**. `disposition` is beside it, never
 * inside it, and no code path anywhere may let the second decide the first.
 */
export interface ValidationReportRow {
  readonly unitId: TableMigrationUnitId;
  readonly sourceTable: string;
  readonly targetTable: string;
  /** The retained attempt, or null when this table never reached one. */
  readonly execution: ValidationExecution | null;
  /** The technical conclusion, derived from the execution's items and nothing else. */
  readonly conclusion: ValidationConclusion;
  /** The 表迁移单元's own workflow state, which is not a validation result. */
  readonly unitPhase: TableMigrationPhase;
  readonly unitOutcome: TableMigrationOutcome | null;
  /** The audited decision recorded about this result, when one has been. */
  readonly disposition: ValidationDisposition | null;
}

/** The 迁移范围 this report's conclusions cover, stated so a reader can bound them. */
export interface ValidationReportScope {
  readonly sourceDatabase: string;
  readonly targetSchema: string;
  readonly selectedTableCount: number;
  readonly excludedTableCount: number;
  /** The 源基线's instant: the boundary every conclusion below is measured against. */
  readonly baselineCapturedAt: IsoTimestamp;
}

/**
 * 校验报告 — the artefact a DBA submits to a change review (#40).
 *
 * One aggregate rather than several reads, for the reason 单表证据 is one: a report whose
 * halves were fetched separately could describe different instants, and a change reviewer
 * cannot tell that from looking at it.
 */
export interface ValidationReport {
  readonly runId: MigrationRunId;
  readonly taskId: MigrationTaskId;
  readonly observedAt: IsoTimestamp;
  readonly runStatus: MigrationRunStatus;
  readonly scope: ValidationReportScope;
  /** Tables that never migrated. Never mixed into `rows`. */
  readonly exclusions: readonly ScopeExclusion[];
  readonly rows: readonly ValidationReportRow[];
  /**
   * True while at least one 校验执行 has not concluded.
   *
   * The report says so instead of presenting an aggregate verdict: a partial conclusion
   * submitted to a change review is worse than an honest 「还没跑完」.
   */
  readonly validationInFlight: boolean;
}
