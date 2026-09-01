import type {
  CredentialVersionId,
  DatabaseConnectionId,
  IsoTimestamp,
  SourceDialect,
  TargetDialect,
} from './primitives';

/**
 * A database connection is a reusable endpoint and identity whose access semantics are
 * versioned independently from migration tasks (`CONTEXT.md`, ADR-0006). It is never a
 * 数据源: that word names the navigation area this entity is managed on.
 */

/** Which side of a database pair a connection may be used as. */
export type ConnectionRole = 'SOURCE' | 'TARGET';

/** ADR-0006: v1 supports username/password, server-authenticated TLS, and mutual TLS. */
export type TlsMode = 'DISABLED' | 'SERVER_AUTHENTICATED' | 'MUTUAL';

/**
 * The outcome of the lightweight connectivity and identity check ADR-0006 runs when a
 * connection is saved and re-run on demand.
 *
 * `CONTEXT.md` carries no `_中文_` wording for these values, so — following the precedent
 * batch 1 set for preflight conclusions — the interface renders the literal rather than
 * inventing a translation.
 */
export type ConnectionCheckOutcome = 'SUCCEEDED' | 'FAILED' | 'NOT_RUN';

export interface ConnectionCheck {
  readonly outcome: ConnectionCheckOutcome;
  /** Null exactly when the outcome is `NOT_RUN`. */
  readonly checkedAt: IsoTimestamp | null;
  /** The credential version the check authenticated with. */
  readonly credentialVersionId: CredentialVersionId | null;
  /** Observed server product version, e.g. `MySQL 8.0.36`. Null when the check failed. */
  readonly serverVersion: string | null;
  /** Stable reason code for a failed check; null otherwise. */
  readonly failureReason: string | null;
}

/**
 * An immutable version of secret authentication material (`CONTEXT.md`). Only the
 * metadata crosses this boundary — the secret itself never leaves the backend.
 */
export interface CredentialVersion {
  readonly id: CredentialVersionId;
  readonly connectionId: DatabaseConnectionId;
  /** Monotonic within one connection; rendered as `v3`. */
  readonly version: number;
  readonly username: string;
  readonly createdAt: IsoTimestamp;
  /** Set once the ciphertext has been destroyed; the version stays auditable. */
  readonly destroyedAt: IsoTimestamp | null;
}

export interface DatabaseConnection {
  readonly id: DatabaseConnectionId;
  readonly name: string;
  readonly role: ConnectionRole;
  readonly dialect: SourceDialect | TargetDialect;
  readonly host: string;
  readonly port: number;
  /** The MySQL database or PostgreSQL database this endpoint defaults to. */
  readonly database: string;
  readonly tls: TlsMode;
  readonly currentCredentialVersion: CredentialVersion;
  /** Historical versions remain auditable, so this only grows. */
  readonly credentialVersionCount: number;
  readonly latestCheck: ConnectionCheck;
  /** ADR-0006: a connection referenced by a task or run is archived, never deleted. */
  readonly archived: boolean;
  readonly createdAt: IsoTimestamp;
  readonly updatedAt: IsoTimestamp;
}

/** Registering a connection also creates its first credential version. */
export interface RegisterDatabaseConnectionRequest {
  readonly name: string;
  readonly role: ConnectionRole;
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly username: string;
  readonly tls: TlsMode;
  /** Write-only: the secret material of the first credential version. */
  readonly secret: string;
}

/** Credentials are never edited in place; maintaining one adds a version. */
export interface AddCredentialVersionRequest {
  readonly username: string;
  readonly secret: string;
}
