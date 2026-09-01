import { describe, expect, it } from 'vitest';
import {
  SOURCE_TABLE_FIXTURE_SIZE,
  generateSourceTables,
  sourceTableCategory,
  summariseSourceTables,
} from './sourceTables';

const seed = 20260901;

describe('1200-table source fixture', () => {
  const tables = generateSourceTables({ seed });

  it('produces byte-identical output for the same seed', () => {
    // The hard requirement from #30: without it, two screenshots cannot be compared and a
    // review link cannot reproduce the state someone is asking about.
    const again = generateSourceTables({ seed });
    expect(JSON.stringify(again)).toBe(JSON.stringify(tables));
  });

  it('produces different output for a different seed', () => {
    expect(JSON.stringify(generateSourceTables({ seed: seed + 1 }))).not.toBe(
      JSON.stringify(tables),
    );
  });

  it('is production scale', () => {
    expect(tables).toHaveLength(SOURCE_TABLE_FIXTURE_SIZE);
  });

  it('gives every table a distinct source identifier and a deterministic order', () => {
    const names = tables.map((table) => table.name);
    expect(new Set(names).size).toBe(names.length);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'en')));
  });

  it('matches the mix #30 fixed', () => {
    const mix = summariseSourceTables(tables);
    expect(mix).toMatchObject({
      total: 1200,
      automaticMapping: 1020, // 85%
      mappingException: 96, // 8%
      largeRecord: 48, // 4%
      preflightBlocked: 36, // 3%
    });
  });

  it('contains tables that hit several conditions at once', () => {
    // User story 47: a table that is both a 大记录表 and carries mapping exceptions must be
    // expressible as both, not reduced to the more dramatic half.
    const mix = summariseSourceTables(tables);
    expect(mix.multiCondition).toBeGreaterThan(0);

    const bothLargeAndMapped = tables.filter(
      (table) => table.largeRecordTable && table.mappingExceptionCount > 0,
    );
    expect(bothLargeAndMapped.length).toBeGreaterThan(0);

    const allThree = tables.filter(
      (table) =>
        table.largeRecordTable &&
        table.mappingExceptionCount > 0 &&
        table.preflightConclusion !== 'SUPPORTED',
    );
    expect(allThree.length).toBeGreaterThan(0);
  });

  it('blocks with both UNSUPPORTED and INCONCLUSIVE', () => {
    // `INCONCLUSIVE` has to appear on its own, because it is the conclusion the interface
    // is most likely to quietly fold into "warning" (ADR-0004, #30).
    const conclusions = new Set(tables.map((table) => table.preflightConclusion));
    expect(conclusions).toEqual(new Set(['SUPPORTED', 'UNSUPPORTED', 'INCONCLUSIVE']));
  });

  it('never calls an unsupported table automatically mapped', () => {
    for (const table of tables) {
      if (table.preflightConclusion !== 'SUPPORTED') {
        expect(sourceTableCategory(table)).toBe('preflightBlocked');
      }
      if (table.largeRecordTable) {
        expect(table.largestValueBytes).toBeGreaterThanOrEqual(1_048_576);
      } else {
        expect(table.largestValueBytes).toBeNull();
      }
    }
  });

  it('scales the same proportions to a smaller database', () => {
    const smaller = summariseSourceTables(generateSourceTables({ seed, count: 100 }));
    expect(smaller).toMatchObject({
      total: 100,
      automaticMapping: 85,
      mappingException: 8,
      largeRecord: 4,
      preflightBlocked: 3,
    });
  });
});
