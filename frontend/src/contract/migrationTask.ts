import type {
  DatabaseConnectionId,
  DatabasePair,
  IsoTimestamp,
  MigrationDraftId,
  MigrationRunId,
  MigrationTaskId,
} from './primitives';
import type { MigrationRunStatus } from './migrationRun';

/**
 * A migration task is a *user-approved* migration of one source MySQL database into one
 * PostgreSQL schema (`CONTEXT.md`). Approval is part of the definition, which is why an
 * unapproved working set is a separate entity — see `MigrationDraft` below — rather than
 * a task carrying an "unapproved" flag that would make 已批准 a property that can be false.
 */
export interface MigrationTask {
  readonly id: MigrationTaskId;
  readonly name: string;
  readonly databasePair: DatabasePair;
  readonly sourceConnectionId: DatabaseConnectionId;
  readonly sourceDatabase: string;
  readonly targetConnectionId: DatabaseConnectionId;
  readonly targetSchema: string;
  readonly approvedAt: IsoTimestamp;
  readonly approvedBy: string;
  readonly selectedTableCount: number;
  readonly runCount: number;
  /** A task presents the projection of its latest run plus immutable history. */
  readonly latestRunId: MigrationRunId | null;
  readonly latestRunStatus: MigrationRunStatus | null;
}

/**
 * The wizard stages a draft can have completed. Only the four configuration stages appear
 * here: 运行监控 and 校验报告 belong to a migration run, not to a draft. The gate that
 * stops a draft skipping ahead is #34's, not this module's.
 */
export type MigrationDraftStage = 'connections' | 'scope' | 'tables' | 'confirm';

/**
 * An unapproved, discardable working set of wizard selections and per-table configuration
 * that has not yet become a migration task (`CONTEXT.md`). It produces no migration run,
 * is never referenced as audit evidence, and may be deleted without trace.
 *
 * Because a draft is client-side work in progress until it is approved, it is the one
 * entity the mock store persists across a browser refresh (see `src/mocks/persistence.ts`).
 */
export interface MigrationDraft {
  readonly id: MigrationDraftId;
  readonly name: string;
  readonly createdAt: IsoTimestamp;
  readonly updatedAt: IsoTimestamp;
  readonly sourceConnectionId: DatabaseConnectionId | null;
  readonly sourceDatabase: string | null;
  readonly targetConnectionId: DatabaseConnectionId | null;
  readonly targetSchema: string | null;
  readonly selectedTables: readonly string[];
  readonly excludedTables: readonly string[];
  readonly completedStages: readonly MigrationDraftStage[];
}

/** Everything a draft holds is optional while it is being filled in. */
export type MigrationDraftPatch = Partial<Omit<MigrationDraft, 'id' | 'createdAt' | 'updatedAt'>>;
