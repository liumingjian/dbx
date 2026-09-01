import type {
  AddCredentialVersionRequest,
  ExecutionConfirmationSummary,
  ExecutionSummaryTable,
  CredentialVersion,
  DatabaseConnection,
  DraftMappingRule,
  DraftPrunedColumn,
  DraftTableConfiguration,
  DraftTableWorkspace,
  MigrationDraft,
  MigrationDraftPatch,
  MigrationRun,
  MigrationRunStatus,
  MigrationTask,
  PruneColumnRequest,
  RecordMappingRuleRequest,
  RunCancellationConsequences,
  RunProgressSnapshot,
  RegisterDatabaseConnectionRequest,
  SourceBaselineEntry,
  SourceTableSummary,
  StructuralProofGapStatement,
  TableMigrationUnitEvidence,
  UnresolvedFinding,
  WriteFreezeDeclaration,
} from '@/contract';
import type { ControllableClock } from './clock';
import { seedDatabaseConnections, unreachableConnectionIds } from './fixtures/databaseConnections';
import { seedMigrationTasks } from './fixtures/migrationTasks';
import {
  OBSERVATION_INTERVAL_MOCK_MS,
  buildRunPlan,
  projectRunProgress,
  seedMonitoredRun,
  type RunPlan,
} from './fixtures/runProgress';
import { buildTableMigrationUnitEvidence } from './fixtures/tableEvidence';
import { generateSourceTables } from './fixtures/sourceTables';
import {
  draftTableConfigurationOf,
  draftTableWorkspaceOf,
  requiresZeroDateDecision,
} from './fixtures/tableWorkspace';
import { deepFreeze } from './immutable';
import type { DraftPersistence } from './persistence';
import type { ScenarioDefinition, SeedPlan } from './scenarios';

/**
 * The stateful mock store behind MSW (ADR-0016).
 *
 * Fixture constants embedded in components were rejected because they cannot express the
 * dimension that matters most here: a connection is checked and its result changes, a run
 * advances, tables fail, validation concludes. The store therefore holds mutable state and
 * reads its own time from the controllable clock, so "最近校验" is a fact that moves rather
 * than a string someone typed.
 *
 * The store is mock infrastructure, not a seam (#30): its behaviour is observed through
 * the application's outer edge, never asserted on directly.
 */
export interface MockStore {
  listDatabaseConnections(): DatabaseConnection[];
  getDatabaseConnection(id: string): DatabaseConnection | undefined;
  registerDatabaseConnection(request: RegisterDatabaseConnectionRequest): DatabaseConnection;
  addCredentialVersion(
    id: string,
    request: AddCredentialVersionRequest,
  ): DatabaseConnection | undefined;
  checkDatabaseConnection(id: string): DatabaseConnection | undefined;

  listMigrationDrafts(): MigrationDraft[];
  getMigrationDraft(id: string): MigrationDraft | undefined;
  createMigrationDraft(patch?: MigrationDraftPatch): MigrationDraft;
  updateMigrationDraft(id: string, patch: MigrationDraftPatch): MigrationDraft | undefined;
  /** Discarding a draft leaves no trace, as `CONTEXT.md` requires. */
  discardMigrationDraft(id: string): boolean;

  listMigrationTasks(): MigrationTask[];
  getMigrationTask(id: string): MigrationTask | undefined;
  /** A task's migration runs, most recent first. A rerun is a new run, never a retry. */
  listMigrationRuns(taskId: string): MigrationRun[];
  getMigrationRun(id: string): MigrationRun | undefined;
  /**
   * One 迁移运行 as it stands at this instant: units, 卡死, timeline and log (#38).
   *
   * Assembled on read from the run's plan and the controllable clock rather than
   * accumulated in a buffer, which is what makes a three-hour migration reviewable in
   * tens of seconds and the same review link reproducible.
   */
  getRunProgress(runId: string): RunProgressSnapshot | undefined;
  /**
   * The 单表证据 of one 表迁移单元: its 错误事件 and the 诊断 made of them (#39).
   *
   * Projected from the same snapshot 运行监控 reads, so the drawer and the row it was
   * opened from can never describe different instants.
   */
  getTableMigrationUnitEvidence(
    runId: string,
    unitId: string,
  ): TableMigrationUnitEvidence | undefined;
  /** What a 取消 would stop, stated before the operator commits to it. */
  describeRunCancellation(runId: string): RunCancellationConsequences | undefined;
  /**
   * Records an operator's 取消 of a running 迁移运行.
   *
   * A 取消 is 「a user-requested terminal stop … that preserves … target data and
   * diagnostic evidence」 (`CONTEXT.md`), so it records a request instant and nothing is
   * deleted, rolled back or reset. The run record itself stays frozen: what changes is
   * what the projection makes of it from that instant onwards.
   */
  requestRunCancellation(runId: string): RunProgressSnapshot | undefined;

  /**
   * The tables discovered in one source database. Generated from the scenario seed, so a
   * 1200-table production schema is reproducible rather than merely large.
   */
  listSourceTables(sourceDatabase: string): SourceTableSummary[];

  /**
   * The per-table configuration of one 迁移草稿, for every table in its 迁移范围.
   *
   * Draft-scoped rather than run-scoped: `CONTEXT.md` puts per-table configuration inside
   * the definition of a 迁移草稿, and a 表迁移单元 belongs to a 迁移运行 that does not
   * exist yet. Returns `undefined` when there is no such draft.
   */
  listDraftTableConfigurations(draftId: string): DraftTableConfiguration[] | undefined;
  getDraftTableWorkspace(draftId: string, sourceTable: string): DraftTableWorkspace | undefined;
  /**
   * Records one user 映射规则, replacing any rule already in force for that coordinate.
   *
   * ADR-0011: a mapping change reassembles the 表写入契约 and reruns every affected
   * 预检. Both follow from the rule being stored — the contract and the DDL are derived
   * from it on every read, so there is no second copy that can go stale.
   */
  recordMappingRule(
    draftId: string,
    request: RecordMappingRuleRequest,
  ): DraftTableWorkspace | undefined;

  /**
   * Cuts one column out of a table's selected columns, or puts it back (ADR-0003).
   *
   * The second of the three exits from a blocked 预检. Like a 映射规则, it changes what
   * the contract would write, so it reruns the table's 预检 rather than editing a
   * conclusion — 「DBX reruns preflight against the approved selected columns」.
   */
  pruneColumn(draftId: string, request: PruneColumnRequest): DraftTableWorkspace | undefined;

  /**
   * Runs one table's 预检 again.
   *
   * The first of the three exits: the operator fixes the source — data, a permission, a
   * timeout — outside DBX and asks for a fresh reading. It reruns the scan and reports
   * whatever it finds; there is deliberately no argument that could make it conclude
   * anything else.
   */
  rerunPreflight(draftId: string, sourceTable: string): DraftTableWorkspace | undefined;

  /**
   * Everything 执行确认 shows for one 迁移草稿, assembled in one read.
   *
   * Assembled server-side rather than added up in the browser, because two of its
   * statements are not the frontend's to make: whether the platform can establish a
   * 结构证明 for every table, and which 预检发现 are still on the record. Returns
   * `undefined` when there is no such draft, or when the draft has no source/target pair
   * yet — there is nothing to summarise before that.
   */
  summariseExecutionConfirmation(draftId: string): ExecutionConfirmationSummary | undefined;

  /**
   * Turns a 迁移草稿 into a 迁移任务 and generates its first 迁移运行.
   *
   * This is the hinge of the whole audit chain. `CONTEXT.md` makes approval part of what a
   * 迁移任务 *is* and a 迁移运行 「one immutable execution attempt」, so the transition is
   * one step: the draft is consumed, the task is recorded as approved by the 写冻结's
   * accountable operator, and the run is frozen with the scope and 源基线 captured at this
   * instant. Nothing afterwards can edit it — see `./immutable.ts`.
   *
   * The refusals below are the same constraints the wizard's gate states. The gate is what
   * the operator sees; this is what makes the constraint true even for a request that
   * never went through it.
   */
  startMigrationRun(draftId: string, freeze: WriteFreezeDeclaration): StartMigrationRunResult;
}

/** Why a start was refused, or the 迁移任务 and 迁移运行 it produced. */
export type StartMigrationRunResult =
  | { readonly ok: true; readonly task: MigrationTask; readonly run: MigrationRun }
  | {
      readonly ok: false;
      readonly code:
        | 'NOT_FOUND'
        /** Gate 5: 「没有写冻结确认就无法启动」. */
        | 'WRITE_FREEZE_NOT_CONFIRMED'
        /** Gate 6: 「没有结构证明就不会开始写入目标」. */
        | 'STRUCTURAL_PROOF_MISSING'
        /** A table in the 迁移范围 that stage three's gate would not have let through. */
        | 'SCOPE_NOT_APPROVABLE';
    };

export interface MockStoreOptions {
  readonly scenario: ScenarioDefinition;
  readonly clock: ControllableClock;
  readonly draftPersistence: DraftPersistence;
}

/** The identifier of the 迁移草稿 seeded at 逐表配置与预检; see `SeedPlan.migrationDrafts`. */
export const SEEDED_DRAFT_ID = 'draft-ready-for-tables';

/**
 * The identifier of the 迁移草稿 seeded at 执行确认 (lead decision D22).
 *
 * Stage four is four client-side navigations and a 1200-table selection away from the
 * start of the wizard, and its whole subject is what an operator may *not* do once they
 * are standing in front of the start button. A fixed-id seed is what lets a review link —
 * and #42's coverage matrix — land there directly, in a chosen scenario, on first paint.
 */
export const CONFIRM_DRAFT_ID = 'draft-ready-for-confirm';

/**
 * How long a rerun of one table's 预检 takes, in **mock** milliseconds.
 *
 * ADR-0003 makes preflight an exact source-side scan that 「may require a full source-table
 * scan and can delay review」, so a rerun that settled inside one request would have taught
 * the interface nothing: 「预检进行中」 has to be a state a view can actually be caught in,
 * or nobody would find out that it renders as a running scan rather than as a frozen
 * screen. Expressed in mock time so the controllable clock governs it like everything else
 * — at the default rate it is a few seconds of real waiting.
 */
export const PREFLIGHT_RERUN_MOCK_MS = 180_000;

export function createMockStore({
  scenario,
  clock,
  draftPersistence,
}: MockStoreOptions): MockStore {
  const seedPlan = scenario.seedPlan;
  const connections = new Map<string, DatabaseConnection>(
    seedDatabaseConnections(scenario.seedPlan, clock).map((entry) => [entry.id, entry]),
  );

  const seededTasks = seedMigrationTasks(scenario.seedPlan, clock);
  const tasks = new Map<string, MigrationTask>(seededTasks.tasks.map((task) => [task.id, task]));
  const runs = new Map<string, MigrationRun>(seededTasks.runs.map((run) => [run.id, run]));

  /**
   * The 迁移运行 that 运行监控 is entered through, and the plans behind every run.
   *
   * A plan is what gives a run its time dimension: phase, progress, timeline and log are
   * all projections of one plan and the clock. Plans are built lazily and remembered, so a
   * run does not re-draw its own shape between two polls.
   */
  const monitored = seedMonitoredRun(scenario.seedPlan, scenario.seed, clock);
  const runPlans = new Map<string, RunPlan>();
  const cancellationRequests = new Map<string, number>();
  if (monitored !== null) {
    runs.set(monitored.run.id, deepFreeze(monitored.run));
    tasks.set(monitored.task.id, deepFreeze(monitored.task));
    runPlans.set(monitored.run.id, monitored.plan);
    if (monitored.plan.seededCancellationAt !== null) {
      // 「操作员取消」 is a scenario, so the request is part of the seeded world rather
      // than something a reviewer has to perform before they can look at it.
      cancellationRequests.set(
        monitored.run.id,
        Date.parse(monitored.run.startedAt) +
          monitored.plan.seededCancellationAt * OBSERVATION_INTERVAL_MOCK_MS,
      );
    }
  }

  /**
   * Which shape a run's plan takes.
   *
   * A run that has already ended states what became of it, and the plan is chosen to agree
   * with that record: a run whose status says `COMPLETED_WITH_FAILURES` must not project
   * twelve successful tables. A run still in flight follows the scenario's own plan.
   */
  function runPlanShapeOf(run: MigrationRun): SeedPlan['runPlan'] {
    switch (run.status) {
      case 'COMPLETED_WITH_FAILURES':
        return 'partial-table-failure';
      case 'COMPLETED_WITH_ACCEPTED_RISK':
        return 'accepted-risk';
      case 'ATTENTION_REQUIRED':
        return 'stuck-table';
      case 'CANCELLED':
      case 'CANCELLING':
        return 'operator-cancellation';
      default:
        return seedPlan.runPlan;
    }
  }

  function planOf(run: MigrationRun): RunPlan {
    const existing = runPlans.get(run.id);
    if (existing !== undefined) {
      return existing;
    }
    const created = buildRunPlan({
      seed: scenario.seed,
      runPlan: runPlanShapeOf(run),
      sourceDatabase: run.sourceDatabase,
      tables: run.sourceBaseline.entries.map((entry) => ({
        name: entry.sourceTable,
        exactRowCount: entry.exactRowCount,
      })),
    });
    runPlans.set(run.id, created);
    return created;
  }

  /**
   * One run, projected at the instant that is meaningful for it.
   *
   * A run still in flight is projected at *now*, which is what gives 运行监控 its time
   * dimension. A run that has ended is projected at its own end and keeps the status and
   * end time it was recorded with: a 迁移运行 is an immutable execution attempt, and the
   * clock moving on does not make a finished one start again.
   */
  function snapshotOf(run: MigrationRun): RunProgressSnapshot {
    const ended = run.endedAt === null ? null : Date.parse(run.endedAt);
    const projected = projectRunProgress({
      run,
      plan: planOf(run),
      nowMs: ended ?? clock.now(),
      cancellationRequestedAtMs:
        cancellationRequests.get(run.id) ??
        (run.cancellationRequestedAt === null ? null : Date.parse(run.cancellationRequestedAt)),
      unitTotalCount: Math.max(run.selectedTableCount, planOf(run).units.length),
    });
    // Frozen like the record it describes: a 迁移运行 is 「one immutable execution
    // attempt」, and a projection of one that could be written to would be a second,
    // editable copy of audit evidence.
    return deepFreeze(ended === null ? projected : { ...projected, run });
  }

  /** A run as it is observed now: its status is a projection of its units (ADR-0004). */
  function observedRun(run: MigrationRun): MigrationRun {
    return run.endedAt === null ? snapshotOf(run).run : run;
  }

  /** A task's 最近运行状态 follows the run it names rather than a copy taken at approval. */
  function observedTask(task: MigrationTask): MigrationTask {
    const latest = task.latestRunId === null ? undefined : runs.get(task.latestRunId);
    if (latest === undefined || latest.endedAt !== null) {
      return task;
    }
    const status: MigrationRunStatus = observedRun(latest).status;
    return status === task.latestRunStatus ? task : { ...task, latestRunStatus: status };
  }
  const sourceTablesByDatabase = new Map<string, SourceTableSummary[]>();
  const sourceTableIndex = new Map<string, Map<string, SourceTableSummary>>();

  let drafts: MigrationDraft[] = [...(draftPersistence.read() ?? [])];
  let sequence = 0;

  /**
   * A 迁移草稿 already parked at 逐表配置与预检, with a fixed identifier.
   *
   * It exists so stage three is reachable *on first paint* under a faulted scenario: the
   * scenario lives in the URL, and client-side navigation drops it, so a stage the
   * operator would normally walk to cannot be reached in a faulted scenario at all unless
   * the draft is already there. `SEEDED_DRAFT_ID` is what a review link and #42's coverage
   * matrix address.
   */
  /** The shape every seeded 迁移草稿 shares: the pair, the databases, nothing decided. */
  const seededDraftBase = (id: string, now: string) => ({
    id,
    name: '',
    createdAt: now,
    updatedAt: now,
    sourceConnectionId: 'conn-mysql-orders',
    sourceDatabase: 'orders',
    targetConnectionId: 'conn-pg-analytics',
    targetSchema: 'orders_migrated',
    scopeKind: 'SELECTED_TABLES' as const,
    excludedTables: [] as readonly string[],
    mappingRules: [],
    prunedColumns: [],
    writeFreeze: null,
    completedStages: ['connections', 'scope'] as const,
  });

  if (seedPlan.migrationDrafts === 'ready-for-tables' && drafts.length === 0) {
    const now = clock.nowIso();
    const generated = generateSourceTables({ seed: scenario.seed, sourceDatabase: 'orders' });
    // Forty tables, plus one of every condition stage three has to be able to express.
    //
    // Taking a prefix alone would leave which conditions are present to luck, and the
    // conditions are the whole point of the stage: a blocked table, a table nothing can be
    // concluded about, a table hitting several conditions at once, and a table whose
    // mapping DBX refuses to decide. Each is named by what it *is*, so the seed can change
    // without the scope quietly losing a case.
    const scope = new Set(generated.slice(0, 40).map((table) => table.name));
    const include = (table: SourceTableSummary | undefined): void => {
      if (table !== undefined) scope.add(table.name);
    };
    include(generated.find((table) => table.preflightConclusion === 'UNSUPPORTED'));
    include(generated.find((table) => table.preflightConclusion === 'INCONCLUSIVE'));
    include(
      generated.find(
        (table) =>
          table.preflightConclusion !== 'SUPPORTED' &&
          table.largeRecordTable &&
          table.mappingExceptionCount > 0,
      ),
    );
    // A table blocked *because* one value is over the 20 MiB 大记录包络. This is the one
    // condition ADR-0003's second exit can actually act on, so without it the interface
    // could only ever describe 「裁剪超限字段后重新预检」 rather than let it be taken.
    include(
      generated.find(
        (table) => table.preflightConclusion === 'UNSUPPORTED' && table.largeRecordTable,
      ),
    );
    include(generated.find((table) => requiresZeroDateDecision(scenario.seed, table)));

    drafts = [
      {
        ...seededDraftBase(SEEDED_DRAFT_ID, now),
        // Generation order, so the 迁移范围 opens the same way twice.
        selectedTables: generated
          .filter((table) => scope.has(table.name))
          .map((table) => table.name),
      },
    ];
  }

  /**
   * A 迁移草稿 already standing at 执行确认, with a fixed identifier.
   *
   * Every table in its 迁移范围 satisfies stage three's gate, because that is the only way
   * a draft reaches stage four at all: a `SUPPORTED` 预检 with no blocking finding and a
   * complete 表写入契约. The scope is chosen by *asking the same assembly the stage asks*
   * rather than by trusting the generator's own label, so the seed cannot drift away from
   * the gate and leave stage four unreachable.
   *
   * Two conditions are deliberately present. Some tables are 大记录表 whose 预检 found
   * values above 1 MiB and judged them non-blocking — those are the 未解决的发现 the
   * summary has to put in front of the operator. And two `UNSUPPORTED` tables are recorded
   * as 显式排除 rather than merely absent, because 「显式排除是可复核的例外」.
   */
  if (seedPlan.migrationDrafts === 'ready-for-confirm' && drafts.length === 0) {
    const now = clock.nowIso();
    const generated = generateSourceTables({ seed: scenario.seed, sourceDatabase: 'orders' });
    const approvable = (table: SourceTableSummary): boolean => {
      const configuration = draftTableConfigurationOf({
        seed: scenario.seed,
        table,
        userRules: [],
        prunedColumns: [],
        preflightInFlight: false,
        generatedAt: now,
      });
      return (
        configuration.preflightConclusion === 'SUPPORTED' &&
        configuration.blockingFindingCount === 0 &&
        configuration.contractVersion !== null
      );
    };

    const candidates = generated.filter(approvable);
    const withFindings = candidates.filter((table) => table.largeRecordTable);
    const scope = new Set<string>(withFindings.slice(0, 2).map((table) => table.name));
    for (const table of candidates) {
      if (scope.size >= 12) break;
      scope.add(table.name);
    }

    drafts = [
      {
        ...seededDraftBase(CONFIRM_DRAFT_ID, now),
        selectedTables: generated
          .filter((table) => scope.has(table.name))
          .map((table) => table.name),
        excludedTables: generated
          .filter((table) => table.preflightConclusion === 'UNSUPPORTED')
          .slice(0, 2)
          .map((table) => table.name),
        completedStages: ['connections', 'scope', 'tables'],
      },
    ];
  }

  const nextId = (prefix: string): string => {
    sequence += 1;
    return `${prefix}-${scenario.seed}-${sequence}`;
  };

  const flushDrafts = (): void => {
    draftPersistence.write(drafts);
  };

  const reachable = (connection: DatabaseConnection): boolean =>
    !unreachableConnectionIds.includes(connection.id);

  const sourceTablesOf = (draft: MigrationDraft): ReadonlyMap<string, SourceTableSummary> => {
    const sourceDatabase = draft.sourceDatabase ?? '';
    const cached = sourceTableIndex.get(sourceDatabase);
    if (cached !== undefined) {
      return cached;
    }
    const index = new Map<string, SourceTableSummary>();
    for (const table of generateSourceTables({ seed: scenario.seed, sourceDatabase })) {
      index.set(table.name, table);
    }
    sourceTableIndex.set(sourceDatabase, index);
    return index;
  };

  const rulesOfTable = (draft: MigrationDraft, sourceTable: string): DraftMappingRule[] =>
    draft.mappingRules.filter((rule) => rule.sourceTable === sourceTable);

  const prunedOfTable = (draft: MigrationDraft, sourceTable: string): string[] =>
    draft.prunedColumns
      .filter((column) => column.sourceTable === sourceTable)
      .map((column) => column.sourceColumn);

  /**
   * The tables whose 预检 is running again, and the mock instant each finishes at.
   *
   * Held here rather than on the draft because a scan in progress is a server-side fact
   * about work, not part of the operator's unapproved working set — persisting it would
   * resurrect a running scan on a page nobody has open.
   */
  const preflightRerunUntil = new Map<string, number>();
  const rerunKey = (draftId: string, sourceTable: string): string =>
    `${draftId}\u0000${sourceTable}`;

  const markPreflightRerun = (draftId: string, sourceTable: string): void => {
    preflightRerunUntil.set(rerunKey(draftId, sourceTable), clock.now() + PREFLIGHT_RERUN_MOCK_MS);
  };

  const preflightInFlight = (draftId: string, sourceTable: string): boolean => {
    const key = rerunKey(draftId, sourceTable);
    const until = preflightRerunUntil.get(key);
    if (until === undefined) {
      return false;
    }
    if (clock.now() >= until) {
      preflightRerunUntil.delete(key);
      return false;
    }
    return true;
  };

  /**
   * One table's entry in the 源基线.
   *
   * `CONTEXT.md` lists 「estimated row count」 under 源基线's `_Avoid_`: a baseline is an
   * exact count captured while source writes are frozen, and the discovery estimate is a
   * different number reached a different way. The mock therefore derives a figure that is
   * deterministic but deliberately *not* the estimate, so nothing downstream can quietly
   * treat one as the other.
   */
  const baselineEntryOf = (table: SourceTableSummary | undefined): SourceBaselineEntry => {
    const estimate = table?.estimatedRowCount ?? 0;
    const exactRowCount = Math.max(0, estimate - (estimate % 97));
    return {
      sourceTable: table?.name ?? '',
      exactRowCount,
      terminalPrimaryKeyValue: exactRowCount === 0 ? null : String(exactRowCount),
    };
  };

  /**
   * 执行确认's summary, assembled from the same workspace every other stage reads.
   *
   * Derived rather than stored, and derived from the identical assembly stage three shows,
   * for the reason `draftTableConfigurationOf` gives: a cheaper approximation is how a
   * summary and the screen behind it end up disagreeing about what is about to be run.
   */
  const summaryOf = (
    draft: MigrationDraft | undefined,
  ): ExecutionConfirmationSummary | undefined => {
    if (
      draft === undefined ||
      draft.sourceConnectionId === null ||
      draft.sourceDatabase === null ||
      draft.targetConnectionId === null ||
      draft.targetSchema === null
    ) {
      return undefined;
    }
    const source = connections.get(draft.sourceConnectionId);
    const target = connections.get(draft.targetConnectionId);
    if (source === undefined || target === undefined) {
      return undefined;
    }

    const byName = sourceTablesOf(draft);
    const tables: ExecutionSummaryTable[] = [];
    const unresolvedFindings: UnresolvedFinding[] = [];
    const gaps: StructuralProofGapStatement[] = [];

    for (const name of draft.selectedTables) {
      const table = byName.get(name);
      if (table === undefined) {
        continue;
      }
      const workspace = workspaceOf(draft, table);
      const contract = workspace.tableWriteContract;
      tables.push({
        sourceTable: workspace.sourceTable,
        targetTable: workspace.targetTable,
        preflightConclusion: workspace.preflight.conclusion,
        contractVersion: contract?.version ?? null,
        contractColumnCount: contract?.columns.length ?? 0,
        largeRecordTable: workspace.preflight.largeRecordTable,
        prunedColumnCount: workspace.prunedColumns.length,
      });
      for (const finding of workspace.preflight.findings) {
        unresolvedFindings.push({
          sourceTable: workspace.sourceTable,
          code: finding.code,
          sourceColumn: finding.sourceColumn,
          blocking: finding.blocking,
          detail: finding.detail,
        });
      }
      // A table with no approved 表写入契约 has nothing for a catalog comparison to compare
      // against, so DBX cannot promise it a 结构证明 (ADR-0011).
      if (contract === null) {
        gaps.push({ sourceTable: name, gap: 'CONTRACT_NOT_APPROVED' });
      }
    }

    // ADR-0011: 「A first run encountering an existing target table fails review rather
    // than reusing, truncating, or replacing it」 — ownership and structural history are
    // unproven, so no 结构证明 can be established for it. Which target coordinates are
    // already occupied is a server-side fact, which is exactly why the scenario seeds it.
    if (seedPlan.targetSchema === 'occupied') {
      const first = tables[0];
      if (first !== undefined) {
        gaps.push({ sourceTable: first.sourceTable, gap: 'TARGET_TABLE_EXISTS' });
      }
    }

    return {
      draftId: draft.id,
      sourceConnectionId: source.id,
      sourceConnectionName: source.name,
      sourceDatabase: draft.sourceDatabase,
      targetConnectionId: target.id,
      targetConnectionName: target.name,
      targetSchema: draft.targetSchema,
      scopeKind: draft.scopeKind,
      tables,
      excludedTables: draft.excludedTables,
      unresolvedFindings,
      structuralProof: {
        // Counted over distinct tables: one table can be short of a 结构证明 for more than
        // one reason, and a count that double-subtracted would understate the scope.
        provableTableCount: tables.length - new Set(gaps.map((entry) => entry.sourceTable)).size,
        gaps,
      },
      assembledAt: clock.nowIso(),
    };
  };

  const workspaceOf = (draft: MigrationDraft, table: SourceTableSummary): DraftTableWorkspace =>
    draftTableWorkspaceOf({
      seed: scenario.seed,
      table,
      targetSchema: draft.targetSchema ?? '',
      userRules: rulesOfTable(draft, table.name),
      prunedColumns: prunedOfTable(draft, table.name),
      preflightInFlight: preflightInFlight(draft.id, table.name),
      // The moment the contract was assembled is the moment the draft last changed, not
      // the moment it was read: 「重新生成于」 has to move when a 映射规则 is recorded and
      // stay still when the same table is merely opened again.
      generatedAt: draft.updatedAt,
    });

  return {
    listDatabaseConnections() {
      // Deterministic ordering: a DBA comparing two screenshots must see the same rows in
      // the same places. Sorting on a fixed collator rather than the ambient locale keeps
      // that true on CI's Linux as well as on a reviewer's mac.
      return [...connections.values()].sort((a, b) => a.id.localeCompare(b.id, 'en'));
    },

    getDatabaseConnection(id) {
      return connections.get(id);
    },

    registerDatabaseConnection(request) {
      const id = nextId('conn');
      const now = clock.nowIso();
      const credentialVersion: CredentialVersion = {
        id: `${id}-cred-1`,
        connectionId: id,
        version: 1,
        username: request.username,
        createdAt: now,
        destroyedAt: null,
      };
      const connection: DatabaseConnection = {
        id,
        name: request.name,
        role: request.role,
        dialect: request.role === 'SOURCE' ? 'MYSQL_8_0' : 'POSTGRESQL_15',
        host: request.host,
        port: request.port,
        database: request.database,
        // A freshly registered connection has only been told about one database; the rest
        // are discovered when it is checked.
        databases: [request.database],
        tls: request.tls,
        currentCredentialVersion: credentialVersion,
        credentialVersionCount: 1,
        // ADR-0006 runs a lightweight check when a connection is saved. It has not run at
        // the moment the record appears, and the interface says so rather than implying a
        // health it has not observed.
        latestCheck: {
          outcome: 'NOT_RUN',
          checkedAt: null,
          credentialVersionId: null,
          serverVersion: null,
          failureReason: null,
        },
        archived: false,
        createdAt: now,
        updatedAt: now,
      };
      connections.set(id, connection);
      return connection;
    },

    addCredentialVersion(id, request) {
      const existing = connections.get(id);
      if (!existing) {
        return undefined;
      }
      const now = clock.nowIso();
      const version = existing.currentCredentialVersion.version + 1;
      const updated: DatabaseConnection = {
        ...existing,
        currentCredentialVersion: {
          id: `${id}-cred-${version}`,
          connectionId: id,
          version,
          username: request.username,
          createdAt: now,
          destroyedAt: null,
        },
        credentialVersionCount: existing.credentialVersionCount + 1,
        // The previous check authenticated with a version that is no longer current, so
        // its result says nothing about this one. Reporting it as still valid would be the
        // dishonest option.
        latestCheck: {
          outcome: 'NOT_RUN',
          checkedAt: null,
          credentialVersionId: null,
          serverVersion: null,
          failureReason: null,
        },
        updatedAt: now,
      };
      connections.set(id, updated);
      return updated;
    },

    checkDatabaseConnection(id) {
      const existing = connections.get(id);
      if (!existing) {
        return undefined;
      }
      const now = clock.nowIso();
      const succeeded = reachable(existing);
      const updated: DatabaseConnection = {
        ...existing,
        latestCheck: {
          outcome: succeeded ? 'SUCCEEDED' : 'FAILED',
          checkedAt: now,
          credentialVersionId: existing.currentCredentialVersion.id,
          serverVersion: succeeded
            ? existing.dialect === 'MYSQL_8_0'
              ? 'MySQL 8.0.36'
              : 'PostgreSQL 15.6'
            : null,
          failureReason: succeeded ? null : 'AUTHENTICATION_FAILED',
        },
        updatedAt: now,
      };
      connections.set(id, updated);
      return updated;
    },

    listMigrationDrafts() {
      return [...drafts].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt, 'en'));
    },

    getMigrationDraft(id) {
      return drafts.find((draft) => draft.id === id);
    },

    createMigrationDraft(patch = {}) {
      const now = clock.nowIso();
      const draft: MigrationDraft = {
        id: nextId('draft'),
        name: '',
        createdAt: now,
        updatedAt: now,
        sourceConnectionId: null,
        sourceDatabase: null,
        targetConnectionId: null,
        targetSchema: null,
        scopeKind: 'SELECTED_TABLES',
        selectedTables: [],
        excludedTables: [],
        mappingRules: [],
        prunedColumns: [],
        writeFreeze: null,
        completedStages: [],
        ...patch,
      };
      drafts = [...drafts, draft];
      flushDrafts();
      return draft;
    },

    updateMigrationDraft(id, patch) {
      const index = drafts.findIndex((draft) => draft.id === id);
      const existing = drafts[index];
      if (existing === undefined) {
        return undefined;
      }
      const updated: MigrationDraft = { ...existing, ...patch, updatedAt: clock.nowIso() };
      drafts = [...drafts.slice(0, index), updated, ...drafts.slice(index + 1)];
      flushDrafts();
      return updated;
    },

    discardMigrationDraft(id) {
      const remaining = drafts.filter((draft) => draft.id !== id);
      if (remaining.length === drafts.length) {
        return false;
      }
      drafts = remaining;
      flushDrafts();
      return true;
    },

    listMigrationTasks() {
      // Most recently approved first, with the identifier as the tie-break, so the list
      // reads the same way twice.
      return [...tasks.values()]
        .sort(
          (a, b) =>
            b.approvedAt.localeCompare(a.approvedAt, 'en') || a.id.localeCompare(b.id, 'en'),
        )
        .map(observedTask);
    },

    getMigrationTask(id) {
      const task = tasks.get(id);
      return task === undefined ? undefined : observedTask(task);
    },

    listMigrationRuns(taskId) {
      return [...runs.values()]
        .filter((run) => run.taskId === taskId)
        .sort((a, b) => b.startedAt.localeCompare(a.startedAt, 'en'))
        .map(observedRun);
    },

    getMigrationRun(id) {
      const run = runs.get(id);
      return run === undefined ? undefined : observedRun(run);
    },

    getRunProgress(runId) {
      const run = runs.get(runId);
      return run === undefined ? undefined : snapshotOf(run);
    },

    getTableMigrationUnitEvidence(runId, unitId) {
      const run = runs.get(runId);
      if (run === undefined) {
        return undefined;
      }
      const evidence = buildTableMigrationUnitEvidence(snapshotOf(run), unitId);
      return evidence === undefined ? undefined : deepFreeze(evidence);
    },

    describeRunCancellation(runId) {
      const run = runs.get(runId);
      if (run === undefined) {
        return undefined;
      }
      const snapshot = snapshotOf(run);
      const terminal = snapshot.units.filter((unit) => unit.phase === 'TERMINAL');
      return {
        runId,
        inFlightUnitCount: snapshot.units.length - terminal.length,
        terminalUnitCount: terminal.length,
        alreadyRequested:
          cancellationRequests.has(runId) || snapshot.run.cancellationRequestedAt !== null,
      };
    },

    requestRunCancellation(runId) {
      const run = runs.get(runId);
      if (run === undefined) {
        return undefined;
      }
      // A run that has already ended is not cancellable, and saying so by returning it
      // unchanged is more honest than recording a request that stops nothing.
      if (run.endedAt === null && !cancellationRequests.has(runId)) {
        cancellationRequests.set(runId, clock.now());
      }
      return snapshotOf(run);
    },

    listSourceTables(sourceDatabase) {
      const cached = sourceTablesByDatabase.get(sourceDatabase);
      if (cached !== undefined) {
        return cached;
      }
      const generated = [...generateSourceTables({ seed: scenario.seed, sourceDatabase })];
      sourceTablesByDatabase.set(sourceDatabase, generated);
      return generated;
    },

    listDraftTableConfigurations(draftId) {
      const draft = drafts.find((entry) => entry.id === draftId);
      if (draft === undefined) {
        return undefined;
      }
      const byName = sourceTablesOf(draft);
      return draft.selectedTables.flatMap((name) => {
        const table = byName.get(name);
        return table === undefined
          ? []
          : [
              draftTableConfigurationOf({
                seed: scenario.seed,
                table,
                userRules: rulesOfTable(draft, name),
                prunedColumns: prunedOfTable(draft, name),
                preflightInFlight: preflightInFlight(draftId, name),
                generatedAt: draft.updatedAt,
              }),
            ];
      });
    },

    getDraftTableWorkspace(draftId, sourceTable) {
      const draft = drafts.find((entry) => entry.id === draftId);
      if (draft === undefined) {
        return undefined;
      }
      const table = sourceTablesOf(draft).get(sourceTable);
      // A table outside the 迁移范围 has no per-table configuration to show: the draft
      // never asked for it to be migrated.
      if (table === undefined || !draft.selectedTables.includes(sourceTable)) {
        return undefined;
      }
      return workspaceOf(draft, table);
    },

    recordMappingRule(draftId, request) {
      const index = drafts.findIndex((entry) => entry.id === draftId);
      const draft = drafts[index];
      if (draft === undefined) {
        return undefined;
      }
      const recorded: DraftMappingRule = {
        id: `${request.sourceColumn}:${request.action}`,
        sourceTable: request.sourceTable,
        sourceColumn: request.sourceColumn,
        action: request.action,
        targetValue: request.targetValue,
        // A rule the operator authored. `CONTEXT.md`: user rules override automatic ones.
        origin: 'USER',
      };
      const updated: MigrationDraft = {
        ...draft,
        mappingRules: [
          ...draft.mappingRules.filter(
            (rule) =>
              rule.sourceTable !== recorded.sourceTable ||
              rule.sourceColumn !== recorded.sourceColumn ||
              rule.action !== recorded.action,
          ),
          recorded,
        ],
        updatedAt: clock.nowIso(),
      };
      drafts = [...drafts.slice(0, index), updated, ...drafts.slice(index + 1)];
      flushDrafts();
      const table = sourceTablesOf(updated).get(request.sourceTable);
      if (table === undefined) {
        return undefined;
      }
      // ADR-0011: 「A mapping change creates a new draft and reruns every affected
      // preflight」. The rerun starts here rather than being something a view remembers to
      // ask for, which is what stops a superseded conclusion outliving the rule that
      // produced it.
      markPreflightRerun(draftId, request.sourceTable);
      return workspaceOf(updated, table);
    },

    pruneColumn(draftId, request) {
      const index = drafts.findIndex((entry) => entry.id === draftId);
      const draft = drafts[index];
      if (draft === undefined) {
        return undefined;
      }
      const table = sourceTablesOf(draft).get(request.sourceTable);
      if (table === undefined || !draft.selectedTables.includes(request.sourceTable)) {
        return undefined;
      }
      const without = draft.prunedColumns.filter(
        (column) =>
          column.sourceTable !== request.sourceTable ||
          column.sourceColumn !== request.sourceColumn,
      );
      const pruned: DraftPrunedColumn = {
        sourceTable: request.sourceTable,
        sourceColumn: request.sourceColumn,
      };
      const updated: MigrationDraft = {
        ...draft,
        prunedColumns: request.pruned ? [...without, pruned] : without,
        updatedAt: clock.nowIso(),
      };
      drafts = [...drafts.slice(0, index), updated, ...drafts.slice(index + 1)];
      flushDrafts();
      // ADR-0003: 「Excluding one field does not waive the row check: DBX reruns preflight
      // against the approved selected columns.」
      markPreflightRerun(draftId, request.sourceTable);
      return workspaceOf(updated, table);
    },

    rerunPreflight(draftId, sourceTable) {
      const draft = drafts.find((entry) => entry.id === draftId);
      if (draft === undefined) {
        return undefined;
      }
      const table = sourceTablesOf(draft).get(sourceTable);
      if (table === undefined || !draft.selectedTables.includes(sourceTable)) {
        return undefined;
      }
      markPreflightRerun(draftId, sourceTable);
      return workspaceOf(draft, table);
    },

    summariseExecutionConfirmation(draftId) {
      return summaryOf(drafts.find((entry) => entry.id === draftId));
    },

    startMigrationRun(draftId, freeze) {
      const index = drafts.findIndex((entry) => entry.id === draftId);
      const draft = drafts[index];
      const summary = summaryOf(draft);
      if (draft === undefined || summary === undefined) {
        return { ok: false, code: 'NOT_FOUND' };
      }

      // Gate 5, restated where it cannot be walked around. A 写冻结 with no accountable
      // operator or no 时限 is the 「permanent checkbox」 `CONTEXT.md` rules out.
      if (
        freeze.accountableOperator.trim() === '' ||
        !Number.isFinite(freeze.durationHours) ||
        freeze.durationHours <= 0
      ) {
        return { ok: false, code: 'WRITE_FREEZE_NOT_CONFIRMED' };
      }

      // Only a `SUPPORTED` 预检 may be approved, and a table with no 表写入契约 has
      // nothing a 结构证明 could compare against (ADR-0011).
      if (
        summary.tables.length === 0 ||
        summary.tables.some(
          (table) => table.preflightConclusion !== 'SUPPORTED' || table.contractVersion === null,
        )
      ) {
        return { ok: false, code: 'SCOPE_NOT_APPROVABLE' };
      }

      // Gate 6. The refusal is the server's, which is where it actually lives.
      if (summary.structuralProof.gaps.length > 0) {
        return { ok: false, code: 'STRUCTURAL_PROOF_MISSING' };
      }

      const now = clock.nowIso();
      const expiresAt = new Date(clock.now() + freeze.durationHours * 60 * 60 * 1000).toISOString();
      const taskId = nextId('task');
      const runId = `${taskId}-run-1`;
      const byName = sourceTablesOf(draft);

      const run: MigrationRun = {
        id: runId,
        taskId,
        status: 'PREPARING',
        startedAt: now,
        endedAt: null,
        // Snapshotted rather than referenced: 「A migration run freezes the database,
        // schema, effective connection semantics … it uses」 (`CONTEXT.md`), so a later
        // edit to either 数据库连接 does not reach backwards into this execution.
        sourceConnectionId: summary.sourceConnectionId,
        sourceDatabase: summary.sourceDatabase,
        targetConnectionId: summary.targetConnectionId,
        targetSchema: summary.targetSchema,
        writeFreeze: {
          accountableOperator: freeze.accountableOperator.trim(),
          confirmedAt: now,
          expiresAt,
          // 「source data covered by a migration run」: the commitment covers the source
          // database this run reads, and says so rather than leaving it implied.
          scope: summary.sourceDatabase,
          changeReference: freeze.changeReference,
          declaredBrokenAt: null,
        },
        sourceBaseline: {
          capturedAt: now,
          entries: summary.tables.map((table) => baselineEntryOf(byName.get(table.sourceTable))),
        },
        selectedTableCount: summary.tables.length,
        excludedTableCount: summary.excludedTables.length,
        cancellationRequestedAt: null,
      };

      const task: MigrationTask = {
        id: taskId,
        // The task is named by the pair it moves, in the identifiers the operator chose.
        name: `${summary.sourceDatabase} → ${summary.targetSchema}`,
        databasePair: { sourceDialect: 'MYSQL_8_0', targetDialect: 'POSTGRESQL_15' },
        sourceConnectionId: summary.sourceConnectionId,
        sourceDatabase: summary.sourceDatabase,
        targetConnectionId: summary.targetConnectionId,
        targetSchema: summary.targetSchema,
        // Approval *is* what a 迁移任务 is, and the accountable operator of the 写冻结 is
        // the person who made it.
        approvedAt: now,
        approvedBy: run.writeFreeze.accountableOperator,
        selectedTableCount: run.selectedTableCount,
        runCount: 1,
        latestRunId: run.id,
        latestRunStatus: run.status,
      };

      // Frozen before it is reachable, so there is no instant in which the record exists
      // and is still writable. A 迁移运行 is 「one immutable execution attempt」, and this
      // is where that stops being a comment.
      runs.set(run.id, deepFreeze(run));
      tasks.set(task.id, deepFreeze(task));

      // The 迁移草稿 is consumed rather than kept beside its task: a draft is 「unapproved
      // … and may be deleted without trace」, and one that outlived its approval would be
      // a second, editable copy of a scope that is now audit evidence.
      drafts = [...drafts.slice(0, index), ...drafts.slice(index + 1)];
      flushDrafts();

      return { ok: true, task, run };
    },
  };
}
