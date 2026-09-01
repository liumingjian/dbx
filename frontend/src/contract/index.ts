/**
 * The DBX frontend contract: the hand-written boundary between this frontend and the
 * backend that has not been built yet (ADR-0016). No OpenAPI document is generated from
 * or into it in this phase; if the backend later adopts OpenAPI, these types are its
 * input rather than a competing definition.
 */
export type * from './primitives';
export type * from './databaseConnection';
export type * from './migrationTask';
export type * from './migrationRun';
export type * from './sourceTable';
export type * from './tableMigrationUnit';
export type * from './runProgress';
export type * from './validation';
export type * from './tableConfiguration';
export type * from './executionConfirmation';
