import { beforeEach, describe, expect, it } from 'vitest';
import {
  dbxTableDensities,
  dbxTableDensityHeights,
  defaultDbxTableDensity,
  readDensityPreference,
  writeDensityPreference,
} from './density';

describe('table density', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('offers exactly the two row heights ADR-0014 allows', () => {
    // 24px is not a third option to be added later: the Chinese typography layer raises
    // `body-compact-01`'s line height, which makes 32px the smallest usable row height.
    expect(dbxTableDensities).toEqual(['condensed', 'comfortable']);
    expect(dbxTableDensityHeights).toEqual({ condensed: 32, comfortable: 40 });
    expect(Object.values(dbxTableDensityHeights)).not.toContain(24);
  });

  it('defaults to the dense reading', () => {
    expect(defaultDbxTableDensity).toBe('condensed');
  });

  it('remembers a preference per table', () => {
    writeDensityPreference('migration-tasks', 'comfortable');
    expect(readDensityPreference('migration-tasks')).toBe('comfortable');
    expect(readDensityPreference('source-tables')).toBeNull();
  });

  it('ignores a stored value it does not recognise', () => {
    window.localStorage.setItem('dbx.table.density.migration-tasks', 'xs');
    expect(readDensityPreference('migration-tasks')).toBeNull();
  });
});
