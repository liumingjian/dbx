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

export const allMatchingFilterSelection: DbxSelectionScope = {
  kind: 'allMatchingFilter',
  excludedIds: [],
};

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
    ? { kind: 'allMatchingFilter', excludedIds: scope.excludedIds.filter((entry) => entry !== id) }
    : { kind: 'allMatchingFilter', excludedIds: [...scope.excludedIds, id] };
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
    return { kind: 'allMatchingFilter', excludedIds: [...scope.excludedIds, ...added] };
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
export function useDbxSelection(
  matchingIds: readonly DbxRowId[],
  /**
   * Where the selection starts. It exists because a selection can outlive the component:
   * a 迁移草稿 restored after a browser refresh (#34) has to come back as the same
   * decision the operator made, and 「符合当前筛选的全部，除了这几张」 cannot be rebuilt
   * from a list of ticked rows.
   */
  initialScope: DbxSelectionScope = emptySelection,
): DbxSelectionModel {
  const [scope, setScope] = useState<DbxSelectionScope>(initialScope);
  const [history, setHistory] = useState<readonly DbxSelectionScope[]>([]);

  const change = useCallback((next: (current: DbxSelectionScope) => DbxSelectionScope) => {
    setScope((current) => {
      const updated = next(current);
      if (updated !== current) {
        setHistory((entries) => [...entries, current]);
      }
      return updated;
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
      selectAllMatchingFilter: () => change(() => allMatchingFilterSelection),
      exclude: (id) => change((current) => excludeRow(current, id)),
      clear: () => change(() => emptySelection),
      canUndo: history.length > 0,
      undo: () => {
        setHistory((entries) => {
          const previous = entries[entries.length - 1];
          if (previous === undefined) {
            return entries;
          }
          setScope(previous);
          return entries.slice(0, -1);
        });
      },
    }),
    [scope, count, history, change],
  );
}
