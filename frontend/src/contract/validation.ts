import type { IsoTimestamp, TableMigrationUnitId, ValidationExecutionId } from './primitives';

/**
 * Validation executions are retained attempts; a later execution or a disposition never
 * rewrites an earlier technical result (`CONTEXT.md`, ADR-0004).
 */

export type ValidationItemState = 'PASS' | 'FAIL' | 'INCONCLUSIVE' | 'NOT_APPLICABLE' | 'NOT_RUN';

/** The immutable, versioned set of enabled, disabled and not-applicable checks. */
export interface ValidationPlanItem {
  readonly checkId: string;
  readonly enabled: boolean;
  /** Only a rule in the versioned plan may classify a check as not applicable. */
  readonly notApplicableReason: string | null;
}

export interface ValidationPlan {
  readonly version: number;
  readonly items: readonly ValidationPlanItem[];
}

export interface ValidationItemResult {
  readonly checkId: string;
  readonly state: ValidationItemState;
  readonly observedAt: IsoTimestamp | null;
  readonly detail: string | null;
}

export interface ValidationExecution {
  readonly id: ValidationExecutionId;
  readonly unitId: TableMigrationUnitId;
  readonly planVersion: number;
  readonly startedAt: IsoTimestamp;
  readonly completedAt: IsoTimestamp | null;
  readonly items: readonly ValidationItemResult[];
}

/**
 * An operator's audited decision about a failed or inconclusive validation result.
 * Accepting risk may close the workflow but never turns the technical result into `PASS`,
 * which is why this is a separate record rather than a mutation of the execution.
 */
export interface ValidationDisposition {
  readonly executionId: ValidationExecutionId;
  readonly recordedAt: IsoTimestamp;
  readonly accountableOperator: string;
  readonly reason: string;
  /** The item results whose risk was accepted; their states stay unchanged. */
  readonly acceptedCheckIds: readonly string[];
}
