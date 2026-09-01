import type {
  DatabaseConnectionId,
  IsoTimestamp,
  MigrationRunId,
  MigrationTaskId,
} from './primitives';

/**
 * A migration run is one immutable execution attempt over all or part of a migration
 * task (`CONTEXT.md`). A rerun is a new run — never a retry in place.
 */

/** Deterministic projection of the run's units (ADR-0004); not separately editable. */
export type MigrationRunStatus =
  | 'PREPARING'
  | 'RUNNING'
  | 'ATTENTION_REQUIRED'
  | 'CANCELLING'
  | 'COMPLETED'
  | 'COMPLETED_WITH_FAILURES'
  | 'COMPLETED_WITH_ACCEPTED_RISK'
  | 'CANCELLED';

/**
 * The externally enforced, time-bounded commitment that source data does not change.
 * It has an accountable operator and an expiry — `CONTEXT.md` lists "permanent checkbox"
 * under `_Avoid_`, so the confirmation can never be a bare tickbox.
 */
export interface WriteFreeze {
  readonly accountableOperator: string;
  readonly confirmedAt: IsoTimestamp;
  readonly expiresAt: IsoTimestamp;
  /** The source database the commitment covers. */
  readonly scope: string;
  readonly changeReference: string | null;
  readonly declaredBrokenAt: IsoTimestamp | null;
}

export interface SourceBaselineEntry {
  readonly sourceTable: string;
  /** Exact, not estimated. */
  readonly exactRowCount: number;
  /** Terminal value of a monotonic primary key, where the table has one. */
  readonly terminalPrimaryKeyValue: string | null;
}

/**
 * The immutable boundary of a run, captured while source writes are frozen. A new freeze
 * and a new baseline require a new run; a baseline is never refreshed in place.
 */
export interface SourceBaseline {
  readonly capturedAt: IsoTimestamp;
  readonly entries: readonly SourceBaselineEntry[];
}

export interface MigrationRun {
  readonly id: MigrationRunId;
  readonly taskId: MigrationTaskId;
  readonly status: MigrationRunStatus;
  readonly startedAt: IsoTimestamp;
  readonly endedAt: IsoTimestamp | null;
  /** Snapshotted from the task at start; the run never follows later connection edits. */
  readonly sourceConnectionId: DatabaseConnectionId;
  readonly sourceDatabase: string;
  readonly targetConnectionId: DatabaseConnectionId;
  readonly targetSchema: string;
  readonly writeFreeze: WriteFreeze;
  readonly sourceBaseline: SourceBaseline;
  /** The run's own selected scope: a rerun covers fewer tables than its task. */
  readonly selectedTableCount: number;
  readonly excludedTableCount: number;
  readonly cancellationRequestedAt: IsoTimestamp | null;
}
