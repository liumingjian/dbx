import type { IsoTimestamp, SourceDialect, TargetDialect } from '@/contract';

/**
 * Rendering helpers for values that must read identically everywhere.
 *
 * Timestamps are formatted by hand in UTC rather than through `Intl`: CI runs on Linux and
 * reviewers run on macOS, and their ICU data and default time zones do not agree. A DBA
 * comparing a screenshot with a colleague's needs the same characters on both screens, and
 * an explicit UTC stamp is also what a migration ticket wants to quote.
 */
export function formatTimestamp(value: IsoTimestamp): string {
  return `${value.slice(0, 10)} ${value.slice(11, 16)} UTC`;
}

const dialectNames: Record<SourceDialect | TargetDialect, string> = {
  MYSQL_8_0: 'MySQL 8.0',
  POSTGRESQL_15: 'PostgreSQL 15',
};

export function formatDialect(dialect: SourceDialect | TargetDialect): string {
  return dialectNames[dialect];
}

/** A credential version reads as `v3`; the number is what an operator quotes. */
export function formatCredentialVersion(version: number): string {
  return `v${version}`;
}
