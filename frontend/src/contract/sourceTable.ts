import type { PreflightConclusion } from './tableMigrationUnit';

/**
 * One table discovered in the source MySQL database, as the 迁移范围 stage lists it.
 *
 * These are discovery-time facts, deliberately not a 源基线: a source baseline is exact,
 * is captured while source writes are frozen, and belongs to a migration run
 * (`CONTEXT.md`, which lists "estimated row count" under its `_Avoid_`). Nothing here may
 * be presented as one.
 */
export interface SourceTableSummary {
  /** The source identifier, character for character (ADR-0011). */
  readonly name: string;
  /** The MySQL database the table was discovered in. */
  readonly sourceDatabase: string;
  readonly columnCount: number;
  /** Discovery estimate. Never rendered as a 源基线 row count. */
  readonly estimatedRowCount: number;
  readonly estimatedBytes: number;
  /**
   * How many 映射规则 DBX proposes for this table — that is, how many structured
   * exceptions to its automatic mapping the operator will have to review.
   */
  readonly mappingExceptionCount: number;
  /** ADR-0003: an individual source value or row larger than 1 MiB makes it a 大记录表. */
  readonly largeRecordTable: boolean;
  readonly largestValueBytes: number | null;
  readonly preflightConclusion: PreflightConclusion;
  readonly preflightBlockingFindingCount: number;
}
