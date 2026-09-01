import { useCallback, useMemo, useState } from 'react';
import type { DbxRowId, DbxSelectionModel, DbxSelectionScope, DbxSelectionSnapshot } from './types';

/**
 * The cross-page selection model DBX owns (ADR-0015).
 *
 * `useSelectAllWithToggle` in the substrate only ever toggles the current page, and Carbon
 * publishes no semantics for anything wider. So the model, its wording and its undo path
 * are DBX's, and they live here as plain functions over a scope value: a selection is
 * either the rows an operator picked, or 「符合当前筛选的全部」 minus the rows they
 * explicitly excluded.
 *
 * Holding "all matching" as a *scope* rather than as a materialised list of ids is the
 * decision that matters. A materialised list silently goes stale when the filter moves,
 * and it cannot tell 「我没选它」 apart from 「我把它排除了」 — which is the difference
 * between an oversight and a reviewable exception.
 */

export const emptySelection: DbxSelectionScope = { kind: 'rows', selectedIds: [] };

/**
 * 「选中符合当前筛选的全部」, stated under one named filter.
 *
 * The filter travels with the scope because the scope is *about* it. An all-matching scope
 * that outlived its filter would keep selecting whatever happened to match next — which is
 * how clearing a search widened a recorded 迁移范围 from 71 tables to 1200.
 */
export function allMatchingFilterSelection(filterKey: string): DbxSelectionScope {
  return { kind: 'allMatchingFilter', filterKey, excludedIds: [] };
}

export function isRowSelected(scope: DbxSelectionScope, id: DbxRowId): boolean {
  return scope.kind === 'rows' ? scope.selectedIds.includes(id) : !scope.excludedIds.includes(id);
}

export function toggleRow(scope: DbxSelectionScope, id: DbxRowId): DbxSelectionScope {
  if (scope.kind === 'rows') {
    return scope.selectedIds.includes(id)
      ? { kind: 'rows', selectedIds: scope.selectedIds.filter((entry) => entry !== id) }
      : { kind: 'rows', selectedIds: [...scope.selectedIds, id] };
  }
  // Unticking a row inside an all-matching selection is an exclusion, not a deselection:
  // the operator is recording an exception to a scope they already stated.
  return scope.excludedIds.includes(id)
    ? {
        kind: 'allMatchingFilter',
        filterKey: scope.filterKey,
        excludedIds: scope.excludedIds.filter((entry) => entry !== id),
      }
    : {
        kind: 'allMatchingFilter',
        filterKey: scope.filterKey,
        excludedIds: [...scope.excludedIds, id],
      };
}

export function excludeRow(scope: DbxSelectionScope, id: DbxRowId): DbxSelectionScope {
  return isRowSelected(scope, id) ? toggleRow(scope, id) : scope;
}

export function selectPage(
  scope: DbxSelectionScope,
  idsOnPage: readonly DbxRowId[],
): DbxSelectionScope {
  if (scope.kind === 'allMatchingFilter') {
    return {
      kind: 'allMatchingFilter',
      filterKey: scope.filterKey,
      excludedIds: scope.excludedIds.filter((entry) => !idsOnPage.includes(entry)),
    };
  }
  const added = idsOnPage.filter((id) => !scope.selectedIds.includes(id));
  return { kind: 'rows', selectedIds: [...scope.selectedIds, ...added] };
}

export function clearPage(
  scope: DbxSelectionScope,
  idsOnPage: readonly DbxRowId[],
): DbxSelectionScope {
  if (scope.kind === 'allMatchingFilter') {
    const added = idsOnPage.filter((id) => !scope.excludedIds.includes(id));
    return {
      kind: 'allMatchingFilter',
      filterKey: scope.filterKey,
      excludedIds: [...scope.excludedIds, ...added],
    };
  }
  return { kind: 'rows', selectedIds: scope.selectedIds.filter((id) => !idsOnPage.includes(id)) };
}

/** The ids that are selected right now, given the rows matching the current filter. */
export function selectedIdsWithin(
  scope: DbxSelectionScope,
  matchingIds: readonly DbxRowId[],
): readonly DbxRowId[] {
  return scope.kind === 'rows'
    ? matchingIds.filter((id) => scope.selectedIds.includes(id))
    : matchingIds.filter((id) => !scope.excludedIds.includes(id));
}

export function selectedCount(scope: DbxSelectionScope, matchingIds: readonly DbxRowId[]): number {
  return selectedIdsWithin(scope, matchingIds).length;
}

export function excludedIdsOf(scope: DbxSelectionScope): readonly DbxRowId[] {
  return scope.kind === 'allMatchingFilter' ? scope.excludedIds : [];
}

export function selectionSnapshot(
  scope: DbxSelectionScope,
  matchingIds: readonly DbxRowId[],
): DbxSelectionSnapshot {
  const selectedIds = selectedIdsWithin(scope, matchingIds);
  return { scope, selectedCount: selectedIds.length, selectedIds };
}

/**
 * Binds the model above to React state, with an undo stack.
 *
 * Undo covers every selection change, not only the destructive-looking ones: on a
 * 1200-row selector the expensive mistake is a stray 「当前页全选」 after twenty minutes of
 * individual ticking, and there is no other way back from it.
 */
/**
 * The selection and the way back from it, held as **one** value.
 *
 * Two `useState` cells would mean writing the second from inside the first's updater, and
 * an updater must be pure: React 18 StrictMode (`src/main.tsx`) double-invokes them, and
 * `v7_startTransition` (`src/routes/router.tsx`) can re-invoke one belonging to a render
 * that was discarded. Either way the undo stack would gain an entry per invocation while
 * the scope gained one change, and 撤销 would only half-undo — silently, and only under
 * the seams that actually double-invoke.
 */
interface SelectionState {
  readonly scope: DbxSelectionScope;
  readonly history: readonly DbxSelectionScope[];
  /**
   * The filter in force when this state was last adjusted, and the rows it was matching.
   *
   * Held in state rather than in a ref because it is read during render: a ref written
   * during render is exactly the impurity StrictMode's double invocation eats, and the
   * rows the *previous* filter matched exist nowhere else once the new ones have replaced
   * them.
   */
  readonly filterKey: string;
  readonly matchingIds: readonly DbxRowId[];
}

export function useDbxSelection(
  matchingIds: readonly DbxRowId[],
  /**
   * Where the selection starts. It exists because a selection can outlive the component:
   * a 迁移草稿 restored after a browser refresh (#34) has to come back as the same
   * decision the operator made, and 「符合当前筛选的全部，除了这几张」 cannot be rebuilt
   * from a list of ticked rows.
   */
  initialScope: DbxSelectionScope = emptySelection,
  /**
   * What identifies the filter currently in force. The empty string means 「没有筛选」.
   *
   * 「符合当前筛选的全部」 names a filter, and a scope that outlived its filter would go on
   * selecting whatever matched next. So when the filter moves, an all-matching scope is
   * **frozen into the rows it actually covered** — the operator keeps the 迁移范围 they
   * chose, and it never widens behind their back.
   */
  filterKey = '',
): DbxSelectionModel {
  const [state, setState] = useState<SelectionState>({
    scope: initialScope,
    history: [],
    filterKey,
    matchingIds,
  });

  // Adjusted during render — the documented React pattern — rather than in an effect: an
  // effect would let one commit go out with the widened scope, and stage two writes its
  // 迁移范围 through to the 迁移草稿 on every change.
  if (state.filterKey !== filterKey || state.matchingIds !== matchingIds) {
    setState((current) => {
      if (current.filterKey === filterKey && current.matchingIds === matchingIds) {
        return current;
      }
      const staleScope =
        current.filterKey !== filterKey &&
        current.scope.kind === 'allMatchingFilter' &&
        current.scope.filterKey === current.filterKey;
      // The freeze does not go on the undo stack: it is not a decision the operator made,
      // it is the same decision written down in the only form that still means what they
      // chose. Putting it there would offer 撤销 as a way back to a scope that now names a
      // different set of tables.
      return staleScope
        ? {
            scope: {
              kind: 'rows',
              selectedIds: selectedIdsWithin(current.scope, current.matchingIds),
            },
            history: current.history,
            filterKey,
            matchingIds,
          }
        : { ...current, filterKey, matchingIds };
    });
  }
  const { scope, history } = state;

  const change = useCallback((next: (current: DbxSelectionScope) => DbxSelectionScope) => {
    // One pure updater over one value: invoking it twice produces the same result as
    // invoking it once.
    setState((current) => {
      const updated = next(current.scope);
      return updated === current.scope
        ? current
        : { ...current, scope: updated, history: [...current.history, current.scope] };
    });
  }, []);

  const count = useMemo(() => selectedCount(scope, matchingIds), [scope, matchingIds]);

  return useMemo<DbxSelectionModel>(
    () => ({
      scope,
      selectedCount: count,
      excludedIds: excludedIdsOf(scope),
      isSelected: (id) => isRowSelected(scope, id),
      toggleRow: (id) => change((current) => toggleRow(current, id)),
      selectPage: (idsOnPage) => change((current) => selectPage(current, idsOnPage)),
      clearPage: (idsOnPage) => change((current) => clearPage(current, idsOnPage)),
      selectAllMatchingFilter: () => change(() => allMatchingFilterSelection(filterKey)),
      exclude: (id) => change((current) => excludeRow(current, id)),
      clear: () => change(() => emptySelection),
      canUndo: history.length > 0,
      undo: () => {
        setState((current) => {
          const previous = current.history[current.history.length - 1];
          return previous === undefined
            ? current
            : { ...current, scope: previous, history: current.history.slice(0, -1) };
        });
      },
    }),
    [scope, count, history, change, filterKey],
  );
}
