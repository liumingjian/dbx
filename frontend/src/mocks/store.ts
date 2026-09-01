import type {
  AddCredentialVersionRequest,
  CredentialVersion,
  DatabaseConnection,
  DraftMappingRule,
  DraftPrunedColumn,
  DraftTableConfiguration,
  DraftTableWorkspace,
  MigrationDraft,
  MigrationDraftPatch,
  MigrationRun,
  MigrationTask,
  PruneColumnRequest,
  RecordMappingRuleRequest,
  RegisterDatabaseConnectionRequest,
  SourceTableSummary,
} from '@/contract';
import type { ControllableClock } from './clock';
import { seedDatabaseConnections, unreachableConnectionIds } from './fixtures/databaseConnections';
import { seedMigrationTasks } from './fixtures/migrationTasks';
import { generateSourceTables } from './fixtures/sourceTables';
import {
  draftTableConfigurationOf,
  draftTableWorkspaceOf,
  requiresZeroDateDecision,
} from './fixtures/tableWorkspace';
import type { DraftPersistence } from './persistence';
import type { ScenarioDefinition } from './scenarios';

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
}

export interface MockStoreOptions {
  readonly scenario: ScenarioDefinition;
  readonly clock: ControllableClock;
  readonly draftPersistence: DraftPersistence;
}

/** The identifier of the seeded 迁移草稿; see `SeedPlan.migrationDrafts`. */
export const SEEDED_DRAFT_ID = 'draft-ready-for-tables';

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
        id: SEEDED_DRAFT_ID,
        name: '',
        createdAt: now,
        updatedAt: now,
        sourceConnectionId: 'conn-mysql-orders',
        sourceDatabase: 'orders',
        targetConnectionId: 'conn-pg-analytics',
        targetSchema: 'orders_migrated',
        scopeKind: 'SELECTED_TABLES',
        // Generation order, so the 迁移范围 opens the same way twice.
        selectedTables: generated
          .filter((table) => scope.has(table.name))
          .map((table) => table.name),
        excludedTables: [],
        mappingRules: [],
        prunedColumns: [],
        completedStages: ['connections', 'scope'],
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
      return [...tasks.values()].sort(
        (a, b) => b.approvedAt.localeCompare(a.approvedAt, 'en') || a.id.localeCompare(b.id, 'en'),
      );
    },

    getMigrationTask(id) {
      return tasks.get(id);
    },

    listMigrationRuns(taskId) {
      return [...runs.values()]
        .filter((run) => run.taskId === taskId)
        .sort((a, b) => b.startedAt.localeCompare(a.startedAt, 'en'));
    },

    getMigrationRun(id) {
      return runs.get(id);
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
  };
}
