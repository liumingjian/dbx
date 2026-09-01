import type {
  DatabaseConnectionId,
  IsoTimestamp,
  MigrationDraftId,
} from './primitives';
import type { MigrationDraftScopeKind } from './migrationTask';
import type { PreflightConclusion, PreflightFindingCode } from './tableMigrationUnit';

/**
 * 执行确认 — the last thing an operator sees before a production migration starts.
 *
 * The stage exists to make one act deliberate. Confirming turns a 迁移草稿 into a
 * 迁移任务 and generates its first 迁移运行, and a 迁移运行 is 「one **immutable** execution
 * attempt」 (`CONTEXT.md`): the scope recorded at that instant is what the whole audit
 * chain afterwards hangs from, so nothing downstream may alter it.
 *
 * Everything in this module is therefore a *statement of fact assembled by the platform*
 * rather than a working set. The operator adds exactly one thing to it — the 写冻结
 * declaration below — and everything else is read.
 */

/**
 * The 写冻结 the operator commits to, as stated inside the 迁移草稿.
 *
 * `CONTEXT.md`: 「The externally enforced, time-bounded operational commitment that source
 * data covered by a migration run does not change. It has an accountable operator and
 * expiry」 — and it lists 「permanent checkbox」 under `_Avoid_`. So a bare tick is not a
 * confirmation: a named 责任人 and a 时限 are what make it one.
 *
 * The 时限 is a **duration**, not an instant, because the commitment starts when the run
 * starts. The expiry is computed by the platform from its own clock at that moment, which
 * is also what stops a stale declaration from carrying an expiry that has already passed.
 *
 * DBX records the commitment; it does not enforce it. That asymmetry is in the definition
 * — 「externally enforced」 — and the interface says so rather than implying a lock.
 */
export interface WriteFreezeDeclaration {
  /** The named person answerable for the commitment. Never blank, never a role. */
  readonly accountableOperator: string;
  /** The 时限, in hours from the moment the 迁移运行 is created. Always bounded. */
  readonly durationHours: number;
  /** The external change record the freeze was arranged under, when there is one. */
  readonly changeReference: string | null;
}

/** One table of the 迁移范围, as 执行确认 summarises it. */
export interface ExecutionSummaryTable {
  readonly sourceTable: string;
  /** Preserved character-for-character from the source identifier (ADR-0011). */
  readonly targetTable: string;
  readonly preflightConclusion: PreflightConclusion | null;
  /** The version of the 表写入契约 that would be approved, or `null` when there is none. */
  readonly contractVersion: number | null;
  /** How many columns that 表写入契约 writes. */
  readonly contractColumnCount: number;
  readonly largeRecordTable: boolean;
  readonly prunedColumnCount: number;
}

/**
 * One 预检发现 that is still on the record when the operator reaches 执行确认.
 *
 * A blocking finding cannot get this far — stage three's gate holds it — so what is listed
 * here is what was found, judged non-blocking, and never resolved. It is the single most
 * important thing on the summary: nobody should start a production migration without
 * having seen it.
 */
export interface UnresolvedFinding {
  readonly sourceTable: string;
  readonly code: PreflightFindingCode;
  readonly sourceColumn: string | null;
  readonly blocking: boolean;
  readonly detail: string;
}

/**
 * Why the platform cannot state a 结构证明 for one table.
 *
 * A closed set of codes rather than server prose, like every other reason in this
 * contract. Both members are cases ADR-0011 names outright.
 */
export type StructuralProofGap =
  /** No approved 表写入契约, so there is nothing for a catalog comparison to compare to. */
  | 'CONTRACT_NOT_APPROVED'
  /** 「A first run encountering an existing target table fails review」 (ADR-0011). */
  | 'TARGET_TABLE_EXISTS';

export interface StructuralProofGapStatement {
  readonly sourceTable: string;
  readonly gap: StructuralProofGap;
}

/**
 * What the platform can say about 结构证明 *before* anything has been written.
 *
 * 结构证明 is 「the deterministic comparison of the actual PostgreSQL table … against the
 * approved table write contract」, performed immediately after DDL and inside the run. It
 * therefore does not exist yet at 执行确认 and cannot be faked into existing: what the
 * summary reports is whether the platform is in a position to establish one for every
 * table when the time comes.
 *
 * This is the honest half of **Gate 6** (lead decision D11). The frontend cannot enforce
 * 「no structural proof, no writing to the target」 — that boundary is server-side — so it
 * states the constraint in domain language and refuses to start while `gaps` is non-empty.
 */
export interface StructuralProofReadiness {
  /** Tables DBX will prove structurally before any Sink writes to them. */
  readonly provableTableCount: number;
  /** Tables it cannot yet promise a 结构证明 for. Non-empty forbids a start. */
  readonly gaps: readonly StructuralProofGapStatement[];
}

/**
 * Everything 执行确认 shows, assembled by the platform in one read.
 *
 * One aggregate rather than six requests: this is a single global check, and a summary
 * whose halves were fetched separately could contradict itself — a contract list that no
 * longer matches the findings beside it is exactly how a migration gets started against
 * evidence that has moved on.
 */
export interface ExecutionConfirmationSummary {
  readonly draftId: MigrationDraftId;
  readonly sourceConnectionId: DatabaseConnectionId;
  readonly sourceConnectionName: string;
  readonly sourceDatabase: string;
  readonly targetConnectionId: DatabaseConnectionId;
  readonly targetConnectionName: string;
  readonly targetSchema: string;
  readonly scopeKind: MigrationDraftScopeKind;
  readonly tables: readonly ExecutionSummaryTable[];
  /** 「显式排除是可复核的例外」: an excluded table is a decision, not an omission. */
  readonly excludedTables: readonly string[];
  readonly unresolvedFindings: readonly UnresolvedFinding[];
  readonly structuralProof: StructuralProofReadiness;
  /** The platform's clock when the summary was assembled; the 写冻结 expiry starts here. */
  readonly assembledAt: IsoTimestamp;
}
