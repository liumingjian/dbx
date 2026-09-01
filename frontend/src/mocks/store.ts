import type {
  AddCredentialVersionRequest,
  CredentialVersion,
  DatabaseConnection,
  MigrationDraft,
  MigrationDraftPatch,
  RegisterDatabaseConnectionRequest,
} from '@/contract';
import type { ControllableClock } from './clock';
import { seedDatabaseConnections, unreachableConnectionIds } from './fixtures/databaseConnections';
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
}

export interface MockStoreOptions {
  readonly scenario: ScenarioDefinition;
  readonly clock: ControllableClock;
  readonly draftPersistence: DraftPersistence;
}

export function createMockStore({
  scenario,
  clock,
  draftPersistence,
}: MockStoreOptions): MockStore {
  const connections = new Map<string, DatabaseConnection>(
    seedDatabaseConnections(scenario.seedPlan, clock).map((entry) => [entry.id, entry]),
  );

  let drafts: MigrationDraft[] = [...(draftPersistence.read() ?? [])];
  let sequence = 0;

  const nextId = (prefix: string): string => {
    sequence += 1;
    return `${prefix}-${scenario.seed}-${sequence}`;
  };

  const flushDrafts = (): void => {
    draftPersistence.write(drafts);
  };

  const reachable = (connection: DatabaseConnection): boolean =>
    !unreachableConnectionIds.includes(connection.id);

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
        selectedTables: [],
        excludedTables: [],
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
  };
}
