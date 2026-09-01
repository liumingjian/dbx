import { StrictMode, createElement, type ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
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
  useDbxSelection,
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

/**
 * 撤销 under a seam that invokes a state updater twice.
 *
 * React 18 StrictMode double-invokes updaters on purpose, to surface exactly the impurity
 * this covers: a second `setState` called from *inside* another updater runs once per
 * invocation, so the undo stack grew by two while the selection changed once and 撤销 only
 * half-undid. Neither of the seams DBX runs under made it visible — StrictMode is on in
 * `src/main.tsx` but seam ② renders without it — so it is asserted here directly.
 */
describe('撤销 survives a doubly-invoked updater', () => {
  const strict = ({ children }: { children: ReactNode }) => createElement(StrictMode, null, children);

  it('takes exactly one 撤销 to undo one change', () => {
    const { result } = renderHook(() => useDbxSelection(matching), { wrapper: strict });

    act(() => result.current.toggleRow('a'));
    expect(result.current.selectedCount).toBe(1);
    expect(result.current.canUndo).toBe(true);

    act(() => result.current.undo());
    expect(result.current.selectedCount).toBe(0);
    // One change, one entry on the stack. A stack that grew per invocation would still
    // have something on it here, and the operator would have to press 撤销 twice.
    expect(result.current.canUndo).toBe(false);
  });

  it('records one entry per change, not one per render', () => {
    const { result } = renderHook(() => useDbxSelection(matching), { wrapper: strict });

    act(() => result.current.selectPage(['a', 'b']));
    act(() => result.current.toggleRow('c'));
    expect(result.current.selectedCount).toBe(3);

    act(() => result.current.undo());
    expect(result.current.selectedCount).toBe(2);
    expect(result.current.isSelected('c')).toBe(false);
    act(() => result.current.undo());
    expect(result.current.selectedCount).toBe(0);
    expect(result.current.canUndo).toBe(false);
  });
});
