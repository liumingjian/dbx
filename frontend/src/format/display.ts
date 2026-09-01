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

/**
 * A byte count, in the units a DBA quotes.
 *
 * Binary units, computed by hand rather than through `Intl`, for the same reason as the
 * timestamp above: the same value has to produce the same characters on CI's Linux and on
 * a reviewer's mac. `CONTEXT.md` states the large-record boundaries in MiB, so the units
 * here are the units the domain already uses.
 */
export function formatBytes(bytes: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'] as const;
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const rounded = unit === 0 ? String(Math.round(value)) : value.toFixed(1);
  return `${rounded} ${units[unit]}`;
}

/** A row count with thousands separators, grouped by hand so every platform agrees. */
export function formatCount(count: number): string {
  const digits = String(Math.trunc(Math.abs(count)));
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return count < 0 ? `-${grouped}` : grouped;
}
