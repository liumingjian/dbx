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
    const scope = allMatchingFilterSelection('');
    expect(selectedCount(scope, matching)).toBe(4);
    // The filter widening later must not leave newly matching rows unselected: the scope
    // said "everything matching", and it still does.
    expect(selectedCount(scope, [...matching, 'e'])).toBe(5);
  });

  it('records an untick inside an all-matching selection as an exclusion', () => {
    const scope = toggleRow(allMatchingFilterSelection(''), 'c');
    expect(scope).toEqual({ kind: 'allMatchingFilter', filterKey: '', excludedIds: ['c'] });
    expect(selectedCount(scope, matching)).toBe(3);
    expect(isRowSelected(scope, 'c')).toBe(false);
  });

  it('can take an exclusion back', () => {
    const excluded = excludeRow(allMatchingFilterSelection(''), 'c');
    expect(toggleRow(excluded, 'c')).toEqual({
      kind: 'allMatchingFilter',
      filterKey: '',
      excludedIds: [],
    });
  });

  it('never excludes a row that was not selected in the first place', () => {
    const scope = selectPage(emptySelection, ['a']);
    expect(excludeRow(scope, 'b')).toBe(scope);
  });

  it('clears one page out of an all-matching selection by excluding it', () => {
    const scope = clearPage(allMatchingFilterSelection(''), ['a', 'b']);
    expect(selectedIdsWithin(scope, matching)).toEqual(['c', 'd']);
  });

  it('hands a batch action the rows that are selected right now', () => {
    const snapshot = selectionSnapshot(toggleRow(allMatchingFilterSelection(''), 'a'), matching);
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
  const strict = ({ children }: { children: ReactNode }) =>
    createElement(StrictMode, null, children);

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

/**
 * 「符合当前筛选的全部」 is bound to the filter it was stated under.
 *
 * The scope names a filter, so it stops meaning anything once that filter moves. Left
 * unbound it went on selecting whatever matched next, and clearing a search silently
 * widened a recorded 迁移范围 from the 71 tables the operator chose to all 1200 — the set a
 * production migration would then write.
 */
describe('an all-matching scope does not outlive its filter', () => {
  it('freezes into the rows it covered when the filter is cleared', () => {
    const { result, rerender } = renderHook(
      ({ ids, key }: { ids: readonly string[]; key: string }) =>
        useDbxSelection(ids, emptySelection, key),
      { initialProps: { ids: ['a', 'b'], key: 'ab' } },
    );

    act(() => result.current.selectAllMatchingFilter());
    expect(result.current.selectedCount).toBe(2);
    expect(result.current.scope.kind).toBe('allMatchingFilter');

    // The filter widens: four rows match now where two did before.
    rerender({ ids: ['a', 'b', 'c', 'd'], key: '' });

    expect(result.current.selectedCount).toBe(2);
    expect(result.current.isSelected('c')).toBe(false);
    // Frozen into the decision that was actually made, rather than re-evaluated.
    expect(result.current.scope).toEqual({ kind: 'rows', selectedIds: ['a', 'b'] });

    // The freeze is not itself a decision, so 撤销 goes back past it to the state before
    // the operator pressed 全选 — never to a scope that now names a different set of rows.
    act(() => result.current.undo());
    expect(result.current.selectedCount).toBe(0);
    expect(result.current.canUndo).toBe(false);
  });

  it('keeps the exclusions it carried while the filter stands still', () => {
    const { result, rerender } = renderHook(
      ({ ids, key }: { ids: readonly string[]; key: string }) =>
        useDbxSelection(ids, emptySelection, key),
      { initialProps: { ids: matching, key: 'x' } },
    );

    act(() => result.current.selectAllMatchingFilter());
    act(() => result.current.exclude('b'));
    expect(result.current.excludedIds).toEqual(['b']);

    // Same filter, different row identities: the scope still applies.
    rerender({ ids: [...matching], key: 'x' });
    expect(result.current.scope.kind).toBe('allMatchingFilter');
    expect(result.current.excludedIds).toEqual(['b']);
  });
});
