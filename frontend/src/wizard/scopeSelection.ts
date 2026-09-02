import type { DbxSelectionScope } from '@/components/DbxTable';
import type { MigrationDraft, MigrationDraftPatch, SourceTableSummary } from '@/contract';

/**
 * The 迁移范围 as the draft records it, and as the table selection model expresses it.
 *
 * These are two views of one decision, and the translation lives here rather than in the
 * stage component so that neither side has to know the other's shape. The draft stores what
 * the operator decided; `DbxTable`'s model is how that decision behaves while they are
 * still making it.
 */

/**
 * A restored draft comes back as the decision it recorded.
 *
 * `ALL_TABLES_EXCEPT` is only ever written for a scope stated with **no filter in force**
 * (see `draftPatchOfSelection`), so it is restored under the empty filter key — which is
 * what makes the restored scope mean the same thing it meant when it was written. A scope
 * stated under a search was frozen into its rows before it ever reached the draft.
 */
export function selectionScopeOfDraft(draft: MigrationDraft): DbxSelectionScope {
  return draft.scopeKind === 'ALL_TABLES_EXCEPT'
    ? { kind: 'allMatchingFilter', filterKey: '', excludedIds: [...draft.excludedTables] }
    : { kind: 'rows', selectedIds: [...draft.selectedTables] };
}

/**
 * What to write back to the draft.
 *
 * The selected names are materialised even for an all-matching scope, because the draft has
 * to survive a refresh and the later stages need the actual list. The scope kind travels
 * with them so the distinction between 「我没勾它」 and 「我把它排除了」 comes back intact.
 */
export function draftPatchOfSelection(
  scope: DbxSelectionScope,
  selectedNames: readonly string[],
): Required<Pick<MigrationDraftPatch, 'scopeKind' | 'selectedTables' | 'excludedTables'>> {
  // `ALL_TABLES_EXCEPT` means every table of the source database except the recorded ones,
  // so only a scope stated with no filter in force is one. 「符合 order 的全部」 is a
  // different, narrower decision, and recording it as 「整库除外」 is exactly how a
  // 迁移范围 silently grows to the whole database the next time the filter moves.
  return scope.kind === 'allMatchingFilter' && scope.filterKey === ''
    ? {
        scopeKind: 'ALL_TABLES_EXCEPT',
        selectedTables: [...selectedNames],
        excludedTables: [...scope.excludedIds],
      }
    : { scopeKind: 'SELECTED_TABLES', selectedTables: [...selectedNames], excludedTables: [] };
}

/**
 * Deterministic ordering by name.
 *
 * Code-point comparison rather than `localeCompare`: user story 31 wants the same database
 * to open in the same order twice so two screenshots can be compared, and CI runs on Linux
 * while reviewers run on macOS. Collation data differs between those ICU builds; code
 * points do not differ anywhere.
 */
export function compareSourceTableNames(left: string, right: string): number {
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

/** Case-insensitive substring match on the table name, which is what 「按名称搜索」 means. */
export function matchesSearch(table: SourceTableSummary, search: string): boolean {
  const trimmed = search.trim().toLowerCase();
  return trimmed === '' || table.name.toLowerCase().includes(trimmed);
}

export function sortedSourceTables(
  tables: readonly SourceTableSummary[],
): readonly SourceTableSummary[] {
  return [...tables].sort((left, right) => compareSourceTableNames(left.name, right.name));
}
