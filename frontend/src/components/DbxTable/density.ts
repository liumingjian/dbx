import { useCallback, useState } from 'react';
import type { DbxTableDensity } from './types';

/**
 * Row height and its remembered preference (ADR-0014, ADR-0015).
 *
 * Carbon supplies row-height sizes but no switcher and no persistence, so both are DBX's.
 * The type has exactly two values: the Chinese typography layer raises `body-compact-01`'s
 * line height to 1.45, which makes 32px the smallest row height Chinese body text is
 * readable at, and 24px is therefore treated as unavailable rather than as a third option
 * someone can reach for later.
 */

export const dbxTableDensities: readonly DbxTableDensity[] = ['condensed', 'comfortable'];

export const defaultDbxTableDensity: DbxTableDensity = 'condensed';

/** Pixel row heights, restated for tests and for the substrate translation in one place. */
export const dbxTableDensityHeights: Readonly<Record<DbxTableDensity, number>> = {
  condensed: 32,
  comfortable: 40,
};

const storagePrefix = 'dbx.table.density.';

function isDensity(value: unknown): value is DbxTableDensity {
  return value === 'condensed' || value === 'comfortable';
}

export function readDensityPreference(key: string): DbxTableDensity | null {
  try {
    const stored = window.localStorage.getItem(`${storagePrefix}${key}`);
    return isDensity(stored) ? stored : null;
  } catch {
    // A browser with storage denied still gets a working table; it just forgets.
    return null;
  }
}

export function writeDensityPreference(key: string, density: DbxTableDensity): void {
  try {
    window.localStorage.setItem(`${storagePrefix}${key}`, density);
  } catch {
    // Ignored for the same reason.
  }
}

export function useDbxTableDensity(
  preferenceKey: string | undefined,
): readonly [DbxTableDensity, (next: DbxTableDensity) => void] {
  const [density, setDensity] = useState<DbxTableDensity>(
    () =>
      (preferenceKey === undefined ? null : readDensityPreference(preferenceKey)) ??
      defaultDbxTableDensity,
  );

  const change = useCallback(
    (next: DbxTableDensity) => {
      setDensity(next);
      if (preferenceKey !== undefined) {
        writeDensityPreference(preferenceKey, next);
      }
    },
    [preferenceKey],
  );

  return [density, change];
}
