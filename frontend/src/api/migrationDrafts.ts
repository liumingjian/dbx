import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { MigrationDraft, MigrationDraftPatch } from '@/contract';
import { getJson, patchJson, postJson, remove } from './http';
import { dbxQueryKey } from './queryKeys';

/**
 * The lifecycle of a 迁移草稿.
 *
 * A draft is its own entity, not a migration task carrying an "unapproved" flag: approval
 * is part of what a migration task *is* (`CONTEXT.md`), so a task with 已批准 = false would
 * make the defining property optional. What follows from that is the whole of this module —
 * a draft can be created empty, patched a field at a time, and discarded outright, and none
 * of those verbs exist for a migration task.
 *
 * `discard` is a real deletion rather than a status change, because 「丢弃后不留痕迹」 is
 * the requirement: a draft produces no migration run and is never audit evidence, so there
 * is nothing for a tombstone to be evidence of.
 */

interface ListResponse<T> {
  readonly items: readonly T[];
}

export const migrationDraftKeys = {
  all: () => dbxQueryKey('migration-drafts'),
  one: (draftId: string) => dbxQueryKey('migration-drafts', draftId),
};

const draftPath = (draftId: string) => `/migration-drafts/${encodeURIComponent(draftId)}`;

export function useMigrationDrafts() {
  return useQuery({
    queryKey: migrationDraftKeys.all(),
    queryFn: async () => (await getJson<ListResponse<MigrationDraft>>('/migration-drafts')).items,
  });
}

export function useMigrationDraft(draftId: string) {
  return useQuery({
    queryKey: migrationDraftKeys.one(draftId),
    queryFn: () => getJson<MigrationDraft>(draftPath(draftId)),
  });
}

export function useCreateMigrationDraft() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (patch: MigrationDraftPatch = {}) =>
      postJson<MigrationDraft>('/migration-drafts', patch),
    onSuccess: (draft) => {
      queryClient.setQueryData(migrationDraftKeys.one(draft.id), draft);
      void queryClient.invalidateQueries({ queryKey: migrationDraftKeys.all() });
    },
  });
}

/**
 * Every wizard edit is written straight through to the draft.
 *
 * The alternative — holding the working set in component state and saving at the end —
 * would make 「刷新浏览器后草稿仍在」 a lie for everything the operator had not yet
 * finished. The store's persistence adapter (#32) is what makes the write durable; this
 * hook is what makes it happen at the moment the operator decides something.
 */
export function useUpdateMigrationDraft() {
  const queryClient = useQueryClient();
  return useMutation({
    // Serialised rather than concurrent. Every edit is a partial write against one record,
    // so two in flight at once can land out of order and quietly restore a value the
    // operator had already replaced.
    scope: { id: 'migration-draft' },
    mutationFn: (variables: { draftId: string; patch: MigrationDraftPatch }) =>
      patchJson<MigrationDraft>(draftPath(variables.draftId), variables.patch),
    onSuccess: (draft) => {
      queryClient.setQueryData(migrationDraftKeys.one(draft.id), draft);
      void queryClient.invalidateQueries({ queryKey: migrationDraftKeys.all() });
    },
  });
}

export function useDiscardMigrationDraft() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (draftId: string) => remove(draftPath(draftId)),
    onSuccess: (_result, draftId) => {
      queryClient.removeQueries({ queryKey: migrationDraftKeys.one(draftId) });
      void queryClient.invalidateQueries({ queryKey: migrationDraftKeys.all() });
    },
  });
}
