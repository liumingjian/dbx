/**
 * Shared primitives of the DBX frontend contract.
 *
 * ADR-0016: the boundary between this frontend and the backend that has not been built
 * yet is a hand-written TypeScript contract. There is deliberately no OpenAPI document —
 * writing one for an unbuilt backend would let the frontend fix server-side architecture
 * by implication. Field names come from `CONTEXT.md`, so the backend inherits the domain
 * language rather than a second, parallel vocabulary.
 */

/** An RFC 3339 instant in UTC, e.g. `2026-09-01T08:30:00.000Z`. */
export type IsoTimestamp = string;

export type DatabaseConnectionId = string;
export type CredentialVersionId = string;
export type MigrationTaskId = string;
export type MigrationDraftId = string;
export type MigrationRunId = string;
export type TableMigrationUnitId = string;
export type ValidationExecutionId = string;

/** The v1 database pair (ADR-0008): the only supported conversion relationship. */
export type SourceDialect = 'MYSQL_8_0';
export type TargetDialect = 'POSTGRESQL_15';

export interface DatabasePair {
  readonly sourceDialect: SourceDialect;
  readonly targetDialect: TargetDialect;
}

/** Who caused a recorded transition (ADR-0004). */
export type Actor = 'USER' | 'PLATFORM' | 'RECONCILER';
