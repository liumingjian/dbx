import type { IsoTimestamp, MigrationRunId, TableMigrationUnitId } from './primitives';
import type { RootCauseDomain } from './runProgress';
import type { TableMigrationPhase, TableMigrationUnit } from './tableMigrationUnit';

/**
 * 单表证据: what DBX observed about one 表迁移单元, and what it makes of it (#39).
 *
 * ADR-0005 separates three things that an ordinary "error message" runs together, and the
 * separation is the whole reason this file exists:
 *
 *  - an **错误事件** is an append-only observed fact. It records when DBX saw it, in which
 *    phase, and the immutable evidence reference it can be traced back to. A later
 *    interpretation never rewrites it.
 *  - a **诊断** is a *versioned interpretation* of those facts, carrying a stable code, the
 *    catalog version it was reached under, its source kind, and exactly one 根因域.
 *  - the **workflow outcome** — the unit's `outcome` — is neither of those, and a diagnosis
 *    never changes it by itself.
 *
 * The operator-facing consequence, and the reason a DBA can act on this drawer at all, is
 * that the 根因域 answers 「源问题还是目标问题」 without naming what executes the migration:
 * `SOURCE_DATABASE` and `TARGET_DATABASE` are distinct, and the two execution-platform
 * domains are presented as the single 迁移平台 (`CONTEXT.md`, Gate 7).
 *
 * **ADR-0005's ten-value classification phase is deliberately not modelled here**, and
 * `CONTEXT.md` now says why: 「Diagnosis classification phase」 carries
 * `_Operator-facing_: Never`. One of its values — `CONNECTOR_PROVISIONING` — is named
 * after the execution platform, which Gate 7 keeps off the interface, and the rest would
 * put a second, differently-cut phase vocabulary beside 阶段 without telling the operator
 * anything they could act on. What an 错误事件 carries instead is the
 * `TableMigrationPhase` DBX observed the table in, which every value of the classification
 * maps into. The classification stays in the diagnostic evidence, for support.
 */

/** Where a diagnosis came from (ADR-0005). The catalog is shared by all three. */
export type DiagnosisSourceKind = 'STRUCTURED' | 'EXTERNAL_TRANSLATION' | 'SYSTEM_FALLBACK';

/**
 * The stable diagnosis codes v1 can reach for a single table.
 *
 * A closed union rather than a free string, for the same reason `PreflightFindingCode` is
 * one: the wording belongs in `src/messages` with the rest of the interface's copy, and
 * the code is what stops the catalog, the contract and that copy from drifting apart.
 * ADR-0005: codes are stable and **never reused**, and a historical diagnosis keeps the
 * catalog version it was reached under.
 */
export type DiagnosisCode =
  /** Rule family 3: the source database refused the read DBX is entitled to make. */
  | 'DBX-SOURCE-PERMISSION-DENIED'
  /** Rule family 7: a source value the target type cannot represent. */
  | 'DBX-SOURCE-VALUE-UNREPRESENTABLE'
  /** Rule family 18: a `NULL` written into a non-null target column. */
  | 'DBX-TARGET-NOT-NULL-VIOLATION'
  /** Rule family 19: a value longer than the target column accepts. */
  | 'DBX-TARGET-VALUE-TOO-LONG'
  /** Structured: the 卡死 diagnosis, seen from the table that stopped moving. */
  | 'DBX-NO-OBSERVABLE-PROGRESS'
  /** Structured: this table was stopped alongside another; its result is undetermined. */
  | 'DBX-STOPPED-BY-RELATED-FAILURE'
  /** ADR-0005's fallback: no rule was trustworthy. It invents no cause. */
  | 'DBX-UNKNOWN';

/**
 * One 错误事件: an immutable fact, retained with the evidence needed to explain it.
 *
 * Repeated observations with the same normalized fingerprint are **aggregated** — first
 * seen, last seen, count — rather than appended as duplicate timeline cards (ADR-0005), so
 * a table that failed the same way two hundred times reads as one fact with a count.
 */
export interface ErrorOccurrence {
  readonly id: string;
  /** The phase DBX observed the table in. Presented through 运行监控's own vocabulary. */
  readonly observedPhase: TableMigrationPhase;
  readonly firstObservedAt: IsoTimestamp;
  readonly lastObservedAt: IsoTimestamp;
  /** How many observations shared this fingerprint. Never fewer than one. */
  readonly observationCount: number;
  /** The immutable evidence reference a support engineer can retrieve. */
  readonly evidenceReference: string;
  /**
   * The redacted technical detail, server-produced and quoted verbatim into a ticket.
   *
   * Not translated copy — it is evidence, in the same category as a 预检发现's `detail`.
   * It is still subject to Gate 7: the execution platform's own vocabulary does not
   * appear in it.
   */
  readonly detail: string;
}

/**
 * The versioned interpretation of this table's 错误事件.
 *
 * `rootCauseDomain` is kept whole, including the two execution-platform domains, because
 * `CONTEXT.md` requires the specific domain to be 「retained in the diagnostic evidence for
 * support use」. What the operator is shown is `presentRootCauseDomain`'s answer.
 */
export interface Diagnosis {
  readonly code: DiagnosisCode;
  /** The catalog version this interpretation was reached under (ADR-0005). */
  readonly catalogVersion: string;
  readonly sourceKind: DiagnosisSourceKind;
  readonly rootCauseDomain: RootCauseDomain;
  /** The 错误事件 this interpretation rests on. */
  readonly occurrenceIds: readonly string[];
}

/**
 * Everything the single-table evidence drawer reads, in one aggregate.
 *
 * One read rather than three, for the same reason 运行监控's snapshot is one: occurrences,
 * diagnosis and the unit's own record fetched separately could describe different
 * instants, and evidence that disagrees with itself is worse than none. `observedAt` is
 * the instant this assembly was true.
 */
export interface TableMigrationUnitEvidence {
  readonly observedAt: IsoTimestamp;
  readonly runId: MigrationRunId;
  readonly unitId: TableMigrationUnitId;
  readonly unit: TableMigrationUnit;
  /** Most recent first. Empty when this table has nothing to explain. */
  readonly occurrences: readonly ErrorOccurrence[];
  /** Null when there is no failure to interpret. Never a placeholder verdict. */
  readonly diagnosis: Diagnosis | null;
}
