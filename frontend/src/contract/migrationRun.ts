import type {
  DatabaseConnectionId,
  IsoTimestamp,
  MigrationRunId,
  MigrationTaskId,
} from './primitives';
import type { ConnectionCheckOutcome, ConnectionRole } from './databaseConnection';
import type { PreflightConclusion } from './tableMigrationUnit';

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

/**
 * How a 迁移运行 came to exist.
 *
 * `CONTEXT.md` lists 「retry in place」 under 迁移运行's `_Avoid_` and ADR-0006 is explicit
 * that 「a rerun is always a new migration run with new table migration units, connectors,
 * topics, baselines … It may include one table, a chosen subset, or the entire task, but
 * its report names that scope and does not present a partial rerun as a new whole-task
 * success」. So a re-migration is not a state an existing run enters: it is a new run that
 * *names* the earlier one, and the earlier record is not touched at all.
 */
export type MigrationRunOrigin =
  /** The 迁移运行 生成 by 执行确认 when the 迁移任务 was approved. */
  | { readonly kind: 'INITIAL' }
  | {
      readonly kind: 'REMIGRATION';
      /**
       * The earlier 迁移运行 whose undetermined tables this one covers.
       *
       * A reference and nothing more. Reading it can tell you where this run came from;
       * nothing anywhere may use it to write back into that run's record.
       */
      readonly ofRunId: MigrationRunId;
    };

/**
 * One 数据库连接 this run tested for itself before it started (ADR-0006).
 *
 * 「A rerun freshly tests connections and capabilities」: an earlier run's successful check
 * proves nothing about this one, and carrying one forward would let a run start against a
 * connection that has since stopped answering.
 */
export interface RunConnectionCheck {
  readonly role: ConnectionRole;
  readonly connectionId: DatabaseConnectionId;
  readonly outcome: ConnectionCheckOutcome;
  /** Null exactly when the outcome is `NOT_RUN`. */
  readonly checkedAt: IsoTimestamp | null;
}

/**
 * What this run established for one of its tables before admitting it.
 *
 * The 预检 conclusion and the 表写入契约 version are recorded together because ADR-0011
 * ties them together: only a `SUPPORTED` 预检 may have its contract approved, and a
 * changed contract requires a fresh approval. A rerun 「executes preflight … regenerates
 * write contracts」, so these are this run's own readings and never the previous run's.
 */
export interface RunTableEvidence {
  readonly sourceTable: string;
  readonly preflightConclusion: PreflightConclusion;
  readonly preflightConcludedAt: IsoTimestamp;
  readonly contractVersion: number;
  readonly contractGeneratedAt: IsoTimestamp;
}

/**
 * The evidence a 迁移运行 established for itself before it started.
 *
 * The 写冻结 and the 源基线 are not repeated here — they are already fields of the run,
 * with instants of their own — so there is exactly one copy of every fact. What this adds
 * is the two readings a run has nowhere else to record: the fresh connection checks and
 * the per-table 预检 and 表写入契约 it was admitted on.
 */
export interface RunEstablishedEvidence {
  readonly connectionChecks: readonly RunConnectionCheck[];
  readonly tables: readonly RunTableEvidence[];
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
  readonly origin: MigrationRunOrigin;
  /** Established by this run, at this run's instants. Never carried over from another. */
  readonly establishedEvidence: RunEstablishedEvidence;
}
