import { describe, expect, it } from 'vitest';
import {
  allMatchingFilterSelection,
  clearPage,
  emptySelection,
  excludeRow,
  isRowSelected,
  selectPage,
  selectedCount,
  selectedIdsWithin,
  selectionSnapshot,
  toggleRow,
} from './selection';

const matching = ['a', 'b', 'c', 'd'];

describe('cross-page selection model', () => {
  it('starts empty', () => {
    expect(selectedCount(emptySelection, matching)).toBe(0);
    expect(isRowSelected(emptySelection, 'a')).toBe(false);
  });

  it('selects the current page without claiming anything about other pages', () => {
    // The substrate's own select-all toggles the current page only, which is exactly the
    // ambiguity ADR-0015 tells DBX to resolve: 「当前页全选」 means this page.
    const scope = selectPage(emptySelection, ['a', 'b']);
    expect(selectedIdsWithin(scope, matching)).toEqual(['a', 'b']);
    expect(isRowSelected(scope, 'c')).toBe(false);
  });

  it('keeps a page selection while other pages are visited', () => {
    const page1 = selectPage(emptySelection, ['a', 'b']);
    const page2 = selectPage(page1, ['c']);
    expect(selectedCount(page2, matching)).toBe(3);
  });

  it('selects everything matching the filter as a scope, not as a snapshot', () => {
    const scope = allMatchingFilterSelection;
    expect(selectedCount(scope, matching)).toBe(4);
    // The filter widening later must not leave newly matching rows unselected: the scope
    // said "everything matching", and it still does.
    expect(selectedCount(scope, [...matching, 'e'])).toBe(5);
  });

  it('records an untick inside an all-matching selection as an exclusion', () => {
    const scope = toggleRow(allMatchingFilterSelection, 'c');
    expect(scope).toEqual({ kind: 'allMatchingFilter', excludedIds: ['c'] });
    expect(selectedCount(scope, matching)).toBe(3);
    expect(isRowSelected(scope, 'c')).toBe(false);
  });

  it('can take an exclusion back', () => {
    const excluded = excludeRow(allMatchingFilterSelection, 'c');
    expect(toggleRow(excluded, 'c')).toEqual({ kind: 'allMatchingFilter', excludedIds: [] });
  });

  it('never excludes a row that was not selected in the first place', () => {
    const scope = selectPage(emptySelection, ['a']);
    expect(excludeRow(scope, 'b')).toBe(scope);
  });

  it('clears one page out of an all-matching selection by excluding it', () => {
    const scope = clearPage(allMatchingFilterSelection, ['a', 'b']);
    expect(selectedIdsWithin(scope, matching)).toEqual(['c', 'd']);
  });

  it('hands a batch action the rows that are selected right now', () => {
    const snapshot = selectionSnapshot(toggleRow(allMatchingFilterSelection, 'a'), matching);
    expect(snapshot.selectedCount).toBe(3);
    expect(snapshot.selectedIds).toEqual(['b', 'c', 'd']);
    expect(snapshot.scope.kind).toBe('allMatchingFilter');
  });
});
