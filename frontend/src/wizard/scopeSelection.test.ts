import { describe, expect, it } from 'vitest';
import type { MigrationDraft } from '@/contract';
import { generateSourceTables } from '@/mocks/fixtures/sourceTables';
import {
  compareSourceTableNames,
  draftPatchOfSelection,
  matchesSearch,
  selectionScopeOfDraft,
  sortedSourceTables,
} from './scopeSelection';

function draft(overrides: Partial<MigrationDraft> = {}): MigrationDraft {
  return {
    id: 'draft-1',
    name: '',
    createdAt: '2026-09-01T00:00:00.000Z',
    updatedAt: '2026-09-01T00:00:00.000Z',
    sourceConnectionId: 'conn-source',
    sourceDatabase: 'orders',
    targetConnectionId: 'conn-target',
    targetSchema: 'orders',
    scopeKind: 'SELECTED_TABLES',
    selectedTables: [],
    excludedTables: [],
    completedStages: [],
    ...overrides,
  };
}

describe('迁移范围 selection', () => {
  it('orders table names by code point, so two openings of one database agree', () => {
    // User story 31 wants the same database to open the same way twice. `localeCompare`
    // would not guarantee that: CI runs on Linux and reviewers run on macOS, and their
    // collation data disagrees about underscores and case.
    const names = ['order_item', 'Order_item', 'order_Item', '_order', 'order-item'];
    const sorted = [...names].sort(compareSourceTableNames);
    expect(sorted).toEqual(['Order_item', '_order', 'order-item', 'order_Item', 'order_item']);
  });

  it('sorts the whole production fixture the same way every time', () => {
    const tables = generateSourceTables({ seed: 20260901 });
    const once = sortedSourceTables(tables).map((table) => table.name);
    const again = sortedSourceTables([...tables].reverse()).map((table) => table.name);
    expect(again).toEqual(once);
    expect(once).toHaveLength(1200);
  });

  it('searches by name, ignoring case, and matches everything on an empty search', () => {
    const [table] = generateSourceTables({ seed: 20260901 });
    expect(table).toBeDefined();
    const subject = table as NonNullable<typeof table>;
    expect(matchesSearch(subject, '')).toBe(true);
    expect(matchesSearch(subject, subject.name.slice(1, 4).toUpperCase())).toBe(true);
    expect(matchesSearch(subject, 'no_such_table')).toBe(false);
  });

  it('restores a row-by-row 迁移范围 as the same decision', () => {
    const restored = selectionScopeOfDraft(
      draft({ scopeKind: 'SELECTED_TABLES', selectedTables: ['a', 'b'] }),
    );
    expect(restored).toEqual({ kind: 'rows', selectedIds: ['a', 'b'] });
  });

  it('restores 「符合当前筛选的全部，除了这几张」 as an exclusion, not as a missing tick', () => {
    // The difference is the point of the model: an unticked row is an oversight, an
    // excluded one is a recorded exception. A refresh must not turn one into the other.
    const restored = selectionScopeOfDraft(
      draft({
        scopeKind: 'ALL_TABLES_EXCEPT',
        selectedTables: ['a', 'b'],
        excludedTables: ['c'],
      }),
    );
    expect(restored).toEqual({ kind: 'allMatchingFilter', excludedIds: ['c'] });
  });

  it('writes both the materialised tables and which decision produced them', () => {
    expect(draftPatchOfSelection({ kind: 'rows', selectedIds: ['a'] }, ['a'])).toEqual({
      scopeKind: 'SELECTED_TABLES',
      selectedTables: ['a'],
      excludedTables: [],
    });
    expect(
      draftPatchOfSelection({ kind: 'allMatchingFilter', excludedIds: ['c'] }, ['a', 'b']),
    ).toEqual({
      scopeKind: 'ALL_TABLES_EXCEPT',
      selectedTables: ['a', 'b'],
      excludedTables: ['c'],
    });
  });
});
