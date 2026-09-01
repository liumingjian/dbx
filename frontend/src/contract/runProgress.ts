import type {
  IsoTimestamp,
  MigrationRunId,
  TableMigrationUnitId,
} from './primitives';
import type { MigrationRun, MigrationRunStatus } from './migrationRun';
import type {
  TableMigrationOutcome,
  TableMigrationPhase,
  TableMigrationUnit,
} from './tableMigrationUnit';

/**
 * What 运行监控 observes: one 迁移运行 as it stands at one instant (#38).
 *
 * The whole file exists because a 迁移运行 has a time dimension and nothing else in the
 * contract does. Two properties of that dimension are load-bearing and are stated in the
 * types rather than left to a view's good intentions:
 *
 *  - **an observation carries its own time.** ADR-0004 makes progress observations the
 *    only asynchronous writes and permits them to be coalesced, so what a view holds is
 *    always 「what was observed at T」 and never 「where the migration is now」. Every
 *    snapshot and every per-table progress observation therefore names its instant, and
 *    the interface renders that instant beside the number.
 *  - **卡死 is a diagnosis, not a degree of slowness.** `CONTEXT.md` gives it a terminal
 *    definition — no observable progress for the configured hard threshold — and lists
 *    「slow」 and 「timed out」 under its `_Avoid_`. It is a separate field with its own
 *    evidence, so no view can arrive at it by comparing two numbers.
 */

/**
 * The single primary domain assigned to a diagnosis (`CONTEXT.md`).
 *
 * Kept whole in the contract, including the two execution-platform domains, because the
 * glossary says the specific domain 「is retained in the diagnostic evidence for support
 * use」. What the operator is shown is a *presentation* of it — `Kafka Connect` and
 * `Kafka` are presented as the single 迁移平台 domain — and that translation is the
 * interface's, not the contract's.
 */
export type RootCauseDomain =
  | 'USER_INPUT'
  | 'SOURCE_DATABASE'
  | 'TARGET_DATABASE'
  | 'KAFKA_CONNECT'
  | 'KAFKA'
  | 'RUNTIME_ENVIRONMENT'
  | 'PLATFORM';

/**
 * 卡死: a terminal diagnosis reached when a scheduling group shows no observable progress
 * for the configured hard threshold while everything under it still reports healthy.
 *
 * ADR-0004 is explicit that `STUCK` is **not** a table outcome — 「DBX never invents
 * per-table blame merely to populate an outcome」 — so it is modelled here as a fact about
 * the run, naming the units it stopped. The unit that stopped advancing keeps its phase
 * and gains no outcome; the units that were stopped alongside it, through no fault of
 * their own, take `BLOCKED_BY_BOX_FAILURE`, which the interface renders as
 * 因关联失败而阻塞.
 */
export interface StuckDiagnosis {
  readonly diagnosedAt: IsoTimestamp;
  /** The last instant at which any of the stalled units moved. */
  readonly lastProgressAt: IsoTimestamp;
  /** The configured hard threshold that was exceeded, in milliseconds. */
  readonly thresholdMs: number;
  /** How long there had been no observable progress when the diagnosis was reached. */
  readonly noProgressForMs: number;
  /** The units that stopped moving. They have no outcome, and that is deliberate. */
  readonly stalledUnitIds: readonly TableMigrationUnitId[];
  /** The units stopped with them: `BLOCKED_BY_BOX_FAILURE`, candidates for re-migration. */
  readonly blockedUnitIds: readonly TableMigrationUnitId[];
  /** Retained for support. Presented to the operator through `presentRootCauseDomain`. */
  readonly rootCauseDomain: RootCauseDomain;
}

/**
 * What happened, as an entry in the run's timeline.
 *
 * The vocabulary is the state machine's own (ADR-0004): a unit entered a phase, a unit
 * reached its one outcome, the run's projected status changed, a 卡死 was diagnosed, an
 * operator asked for a 取消. Nothing here is a box, a connector or a topic — ADR-0004's
 * requirement that 「one table's timeline is readable without treating box history as that
 * table's business state」 is the same requirement as Gate 7, seen from the other side.
 */
export type RunEventType =
  | 'PHASE_ENTERED'
  | 'OUTCOME_RECORDED'
  | 'RUN_STATUS_CHANGED'
  | 'STUCK_DIAGNOSED'
  | 'CANCELLATION_REQUESTED';

export interface RunEvent {
  readonly id: string;
  readonly occurredAt: IsoTimestamp;
  /** Null when the event is about the whole 迁移运行 rather than one 表迁移单元. */
  readonly unitId: TableMigrationUnitId | null;
  readonly sourceTable: string | null;
  readonly type: RunEventType;
  readonly phase: TableMigrationPhase | null;
  readonly outcome: TableMigrationOutcome | null;
  readonly runStatus: MigrationRunStatus | null;
}

/**
 * One line of the run's technical log.
 *
 * Deliberately not translated copy: it is server-produced evidence a DBA quotes into a
 * ticket, in the same category as a 预检发现's `detail`. It is still subject to Gate 7 —
 * the execution platform's own vocabulary does not appear in it.
 */
export interface RunLogLine {
  readonly at: IsoTimestamp;
  readonly sourceTable: string | null;
  readonly text: string;
}

/**
 * One observation of a whole 迁移运行.
 *
 * `observedAt` is the instant the *platform* assembled this snapshot, which is not the
 * instant any particular table last moved: units lag individually, and the interface shows
 * both times rather than implying one.
 *
 * The event stream and the log are bounded by the server and report their true totals, so
 * a bounded rendering can say what it is bounded to instead of truncating silently.
 */
export interface RunProgressSnapshot {
  readonly observedAt: IsoTimestamp;
  readonly run: MigrationRun;
  readonly units: readonly TableMigrationUnit[];
  /**
   * How many 表迁移单元 this 迁移运行 has in total.
   *
   * `units` may be a bounded window of them. A bounded rendering states its bound and the
   * true total rather than truncating silently, so the total travels with the window
   * instead of being inferred from its length.
   */
  readonly unitTotalCount: number;
  /** The 卡死 diagnosis in force, or null. Never inferred from the numbers above. */
  readonly stuck: StuckDiagnosis | null;
  /** Most recent first, bounded. */
  readonly events: readonly RunEvent[];
  readonly eventTotalCount: number;
  /** Most recent first, bounded. */
  readonly log: readonly RunLogLine[];
  readonly logTotalCount: number;
}

/**
 * What a 取消 does, as the platform states it before the operator commits to it.
 *
 * `CONTEXT.md`: a 取消 is 「a user-requested terminal stop of a migration run that
 * preserves … target data and diagnostic evidence」, with 「discard」, 「delete」 and
 * 「rollback」 under its `_Avoid_`. The consequences are read from the server rather than
 * written into a dialog, because which tables are still in flight — and therefore what the
 * operator is actually stopping — is a fact about the run at this instant.
 */
export interface RunCancellationConsequences {
  readonly runId: MigrationRunId;
  /** Units that would be stopped: still in flight when the consequences were assembled. */
  readonly inFlightUnitCount: number;
  /** Units that have already reached a terminal outcome and are unaffected. */
  readonly terminalUnitCount: number;
  /** Whether a cancellation has already been requested for this run. */
  readonly alreadyRequested: boolean;
}
