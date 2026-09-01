import type { IsoTimestamp, MigrationRunId, TableMigrationUnitId } from './primitives';
import type { ValidationExecutionId } from './primitives';

/**
 * A table migration unit is the durable, independently observable record for one source
 * table and its target table within one migration run (`CONTEXT.md`). Its state is
 * orthogonal: a `phase` while it is running, and exactly one `outcome` once terminal
 * (ADR-0004) — never one enum combining stage, failure kind and validation result.
 */

export type TableMigrationPhase =
  | 'DISCOVERED'
  | 'PREFLIGHTING'
  | 'AWAITING_APPROVAL'
  | 'READY'
  | 'CREATING_TARGET'
  | 'WAITING_FOR_BOX'
  | 'TRANSFERRING'
  | 'VALIDATING'
  | 'TERMINAL';

export type TableMigrationOutcome =
  | 'SUCCEEDED'
  | 'FAILED'
  | 'BLOCKED_BY_BOX_FAILURE'
  | 'SKIPPED'
  | 'CANCELLED'
  | 'COMPLETED_WITH_ACCEPTED_RISK';

/** Preflight concludes exactly one of these; only `SUPPORTED` may proceed (`CONTEXT.md`). */
export type PreflightConclusion = 'SUPPORTED' | 'UNSUPPORTED' | 'INCONCLUSIVE';

/**
 * The closed set of things an exact preflight can find.
 *
 * A closed union rather than a free string, for the same reason `MappingExceptionReason`
 * is one: the wording belongs in `src/messages` with the rest of the interface's copy, and
 * a code is what keeps the mock, the contract and that copy from drifting apart. Every
 * member is a case ADR-0003 or ADR-0011 names explicitly.
 */
export type PreflightFindingCode =
  /** ADR-0003: one source value above the 20 MiB 大记录包络, measured exactly. */
  | 'LARGE_RECORD_VALUE'
  /** ADR-0003: the row's total pre-serialization payload. Pruning a field never waives it. */
  | 'LARGE_RECORD_ROW'
  /** A source value domain that does not fit the target type the contract would write. */
  | 'VALUE_DOMAIN_OUT_OF_RANGE'
  /** ADR-0011: a zero-date value under a `NOT NULL` rule the operator chose. */
  | 'ZERO_DATE_VALUE_REJECTED'
  /**
   * ADR-0003: timeout, cancellation or missing permission — 「any other inability to prove
   * the envelope is an `INCONCLUSIVE` preflight and cannot be overridden into a runnable
   * table」.
   */
  | 'ENVELOPE_SCAN_INCONCLUSIVE';

export interface PreflightFinding {
  /** Stable code; the interface renders wording for it, never the raw code alone. */
  readonly code: PreflightFindingCode;
  /**
   * The source coordinate the finding names, or `null` when it is about the whole table.
   *
   * This is what decides whether ADR-0003's second exit — 「裁剪超限字段后重新预检」 — is
   * available at all: a finding that names no column cannot be resolved by cutting one.
   */
  readonly sourceColumn: string | null;
  /** Whether this finding on its own prevents approval. */
  readonly blocking: boolean;
  readonly detail: string;
}

/**
 * The source-side proof required before a table write contract may be approved. It states
 * exact value-domain and transport facts — never an estimate and never a warning that can
 * be acknowledged away.
 */
export interface Preflight {
  /**
   * The conclusion reached, or `null` while the exact scan is still running.
   *
   * `null` is 「still running」 and never 「fine so far」. ADR-0003 makes preflight an exact
   * scan that can take real time, so a view has to be able to say 「进行中」 rather than
   * show a stale judgement or an empty pane that reads as a frozen interface; and every
   * gate treats an absent conclusion the way it treats an unsatisfied one, because an
   * unknown safety fact is not a satisfied one.
   */
  readonly conclusion: PreflightConclusion | null;
  /** When the current conclusion was reached. Null while it is being re-established. */
  readonly evaluatedAt: IsoTimestamp | null;
  readonly findings: readonly PreflightFinding[];
  /** ADR-0003: a source value or row larger than 1 MiB makes this a 大记录表. */
  readonly largeRecordTable: boolean;
  readonly largestValueBytes: number | null;
  readonly largestRowBytes: number | null;
}

export interface TableWriteContractColumn {
  readonly sourceColumn: string;
  readonly sourceType: string;
  readonly targetColumn: string;
  readonly targetType: string;
  /** Set when a mapping rule — automatic or user-authored — produced this column. */
  readonly mappingRuleId: string | null;
}

/**
 * The immutable single-table write intent DBX must prove before starting a Sink
 * (ADR-0011). The DDL is one rendering of the contract, not an editable configuration.
 */
export interface TableWriteContract {
  readonly version: number;
  readonly generatedAt: IsoTimestamp;
  readonly approvedAt: IsoTimestamp | null;
  readonly columns: readonly TableWriteContractColumn[];
  readonly targetDdl: string;
  /** Post-migration script for structures outside the v1 writable-table contract. */
  readonly supplementalSql: string | null;
}

/**
 * The deterministic comparison of the actual PostgreSQL table against the approved table
 * write contract. Only zero difference permits the Sink to start (`CONTEXT.md`).
 */
export interface StructuralProof {
  readonly provenAt: IsoTimestamp | null;
  readonly matchesContract: boolean;
  readonly differences: readonly string[];
}

/**
 * A coalesced progress observation. ADR-0004 makes these the only asynchronous writes and
 * allows them to be coalesced, so a view must never render them as a smooth, monotonic
 * advance: they can jump and they can lag, and the observation carries its own time.
 */
export interface TableProgressObservation {
  readonly observedAt: IsoTimestamp;
  readonly sourceRowsRead: number;
  readonly targetRowsWritten: number;
  readonly lastProgressAt: IsoTimestamp | null;
}

export interface TableMigrationUnit {
  readonly id: TableMigrationUnitId;
  readonly runId: MigrationRunId;
  readonly sourceTable: string;
  readonly targetTable: string;
  readonly phase: TableMigrationPhase;
  /** Exactly one outcome once the phase is `TERMINAL`, null before that. */
  readonly outcome: TableMigrationOutcome | null;
  readonly preflight: Preflight;
  readonly tableWriteContract: TableWriteContract | null;
  readonly structuralProof: StructuralProof | null;
  /** The exact row count captured in the run's source baseline. */
  readonly sourceBaselineRowCount: number | null;
  readonly progress: TableProgressObservation | null;
  readonly latestValidationExecutionId: ValidationExecutionId | null;
}
