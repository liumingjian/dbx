import type {
  DatabaseConnectionId,
  DatabasePair,
  IsoTimestamp,
  MigrationDraftId,
  MigrationRunId,
  MigrationTaskId,
} from './primitives';
import type { MigrationRunStatus } from './migrationRun';
import type { MappingRule } from './tableConfiguration';
import type { WriteFreezeDeclaration } from './executionConfirmation';

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

/*
 * A 迁移草稿 deliberately records **no** completed-stage high-water mark. Lead decision
 * D18: 「Stage reachability is derived, never stored」 — walk the stages in journey order
 * and the first whose gate blocks is as far as the draft goes. A stored mark is a second
 * source of truth that a deep link walks straight past, and every stage is deep-linkable
 * by design.
 */

/**
 * How the operator stated the 迁移范围.
 *
 * ADR-0015 leaves cross-page selection semantics to DBX, and the difference the model
 * turns on has to survive a browser refresh as well as a page change: 「我逐张勾选了这些」
 * and 「我要符合当前筛选的全部，除了这几张」 are different decisions, and only the second
 * one makes an unticked table a recorded exception rather than an oversight. Deriving the
 * kind back from the two lists is not possible — a fully ticked selection and an
 * all-matching selection with no exclusions look identical — so the draft says which it is.
 */
export type MigrationDraftScopeKind = 'SELECTED_TABLES' | 'ALL_TABLES_EXCEPT';

/** A user 映射规则 together with the table it applies to. */
export interface DraftMappingRule extends MappingRule {
  readonly sourceTable: string;
}

/**
 * One column the operator cut out of a table's selected columns.
 *
 * ADR-0003's second exit from a blocked 预检. Part of the draft's per-table configuration,
 * so it survives a refresh with the rest of it and so 执行确认 can summarise it: a table
 * migrating with fewer columns than its source has is a decision, not an implementation
 * detail.
 */
export interface DraftPrunedColumn {
  readonly sourceTable: string;
  readonly sourceColumn: string;
}

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
  readonly scopeKind: MigrationDraftScopeKind;
  /** The source tables in the 迁移范围 right now, by name. */
  readonly selectedTables: readonly string[];
  /** Tables the operator explicitly took out of an `ALL_TABLES_EXCEPT` scope. */
  readonly excludedTables: readonly string[];
  /**
   * The user-authored 映射规则 of the draft's per-table configuration, keyed by table.
   *
   * `CONTEXT.md` puts 「per-table configuration」 inside the definition of a 迁移草稿, and
   * a user rule overrides the automatic one, so this is the operator's decision and
   * nothing else — the rules DBX proposes are derived from source metadata and are not
   * stored here. It is also why these survive a refresh with the rest of the draft.
   */
  readonly mappingRules: readonly DraftMappingRule[];
  /** The columns cut out of the draft's tables, keyed by table (ADR-0003). */
  readonly prunedColumns: readonly DraftPrunedColumn[];
  /**
   * The 写冻结 the operator has committed to at 执行确认, or `null` while there is none.
   *
   * It belongs to the draft rather than to a component's state for the same reason the
   * 映射规则 do: it is a decision the operator made, it has to survive a refresh, and the
   * wizard's gate is a pure function of the draft. It is a *declaration* and not yet the
   * run's 写冻结 — the accountable operator and the 时限 are stated here, and the platform
   * stamps `confirmedAt` and `expiresAt` from its own clock when the 迁移运行 is created.
   */
  readonly writeFreeze: WriteFreezeDeclaration | null;
}

/** Everything a draft holds is optional while it is being filled in. */
export type MigrationDraftPatch = Partial<Omit<MigrationDraft, 'id' | 'createdAt' | 'updatedAt'>>;
