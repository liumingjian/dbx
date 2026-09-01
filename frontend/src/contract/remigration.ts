import type {
  DatabaseConnectionId,
  IsoTimestamp,
  MigrationRunId,
  MigrationTaskId,
  TableMigrationUnitId,
} from './primitives';
import type { RunConnectionCheck } from './migrationRun';
import type { PreflightConclusion, TableMigrationOutcome } from './tableMigrationUnit';
import type { ScopeExclusion, ValidationConclusion } from './validation';
import type { WriteFreezeDeclaration } from './executionConfirmation';

/**
 * 重新迁移 (#41): migrating again the tables an earlier 迁移运行 left undetermined.
 *
 * The whole module exists to keep one sentence true — 「A rerun is a new migration run」
 * (`CONTEXT.md`), and 「retry in place」 is under 迁移运行's `_Avoid_`. So there is
 * deliberately **no** request here that names a run to retry, no field that could carry a
 * new outcome into an old unit, and no response that reports an old run as changed. What a
 * re-migration produces is a second, independent record whose scope is its own.
 *
 * ADR-0006 states what that new record has to establish for itself: 「A rerun freshly tests
 * connections and capabilities, reads source metadata, executes preflight, obtains a new
 * write-freeze commitment and source baseline, regenerates write contracts and automatic
 * rules」. None of those may be inherited, because every one of them is a statement about
 * an instant, and the instant has moved.
 */

/**
 * One table of an earlier 迁移运行 that a 重新迁移 may cover.
 *
 * Which tables those are is settled by the 校验报告 and nothing else: a row concluding
 * `FAIL`, `INCONCLUSIVE` or `NOT_RUN`, or a 表迁移单元 whose own result is undetermined
 * because another unit it was scheduled alongside failed. A table whose 校验执行 concluded
 * `PASS` has a result, and re-migrating it would put a settled table back at risk.
 *
 * 预检排除项 are **not** candidates and never appear here: they never migrated, so they
 * have no technical conclusion at all, and offering them as though they had failed would
 * invent a result the run never produced.
 */
export interface RemigrationCandidate {
  readonly unitId: TableMigrationUnitId;
  readonly sourceTable: string;
  readonly targetTable: string;
  /** The earlier 校验执行's technical conclusion. Quoted, never rewritten. */
  readonly conclusion: ValidationConclusion;
  /** The earlier 表迁移单元's workflow outcome, when it reached one. */
  readonly unitOutcome: TableMigrationOutcome | null;
  /**
   * The 预检 conclusion read again, now, for this table.
   *
   * `SUPPORTED` is the only conclusion that may proceed (`CONTEXT.md`), so a candidate
   * whose fresh 预检 says anything else cannot be part of a new 迁移运行 — and says so
   * here rather than being silently dropped from the list.
   */
  readonly preflightConclusion: PreflightConclusion;
  readonly preflightConcludedAt: IsoTimestamp;
  /** The 表写入契约 version regenerated for it, or null when there is none to approve. */
  readonly contractVersion: number | null;
}

/**
 * What a 重新迁移 of one 迁移运行 could cover, assembled by the platform in one read.
 *
 * One aggregate rather than several, for the reason 执行确认's summary is one: the
 * candidates, the tables that may not be offered, and the freshly read 预检 behind each of
 * them are one statement about one instant, and halves fetched separately could disagree.
 */
export interface RemigrationOffer {
  readonly runId: MigrationRunId;
  readonly taskId: MigrationTaskId;
  readonly sourceConnectionId: DatabaseConnectionId;
  readonly sourceDatabase: string;
  readonly targetConnectionId: DatabaseConnectionId;
  readonly targetSchema: string;
  /** The 迁移任务's own selected scope, so a partial rerun cannot read as the whole task. */
  readonly taskSelectedTableCount: number;
  /** The earlier run's selected scope, for the same reason. */
  readonly runSelectedTableCount: number;
  /** Candidates whose fresh 预检 concluded `SUPPORTED` and that carry a 表写入契约. */
  readonly candidates: readonly RemigrationCandidate[];
  /**
   * Candidates a new 迁移运行 cannot admit, with the fresh reading that refuses them.
   *
   * Listed rather than hidden: a table that failed and now cannot be re-migrated is
   * exactly the table an operator is looking for, and dropping it from the list would say
   * it was fine.
   */
  readonly ineligible: readonly RemigrationCandidate[];
  /** The earlier run's 预检排除项, restated so nobody looks for them among the candidates. */
  readonly exclusions: readonly ScopeExclusion[];
  /** The last connection checks on record; the new run runs its own before it starts. */
  readonly connectionChecks: readonly RunConnectionCheck[];
  readonly assembledAt: IsoTimestamp;
}

/**
 * What an operator sends to start a 重新迁移.
 *
 * The 写冻结 is declared again from scratch, and is not optional: 「it must remain valid
 * from source-baseline capture until every selected table reaches a validation terminal
 * state」, and this run captures a new 源基线, so the earlier commitment cannot cover it.
 */
export interface StartRemigrationRequest {
  readonly unitIds: readonly TableMigrationUnitId[];
  readonly writeFreeze: WriteFreezeDeclaration;
}
