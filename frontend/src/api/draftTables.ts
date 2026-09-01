import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  DraftTableConfiguration,
  DraftTableWorkspace,
  RecordMappingRuleRequest,
} from '@/contract';
import { getJson, postJson } from './http';
import { dbxQueryKey } from './queryKeys';

/**
 * 逐表配置与预检, read from the 迁移草稿 that owns it.
 *
 * Two reads and one write, and the write is the interesting one. ADR-0011 makes the
 * 表写入契约 and its DDL **derived** from the 映射规则 in force, so the only way to change
 * a contract is to record a rule; there is no endpoint that accepts DDL, and adding one
 * would be the 「editable DDL」 the ADR rejects outright.
 *
 * Regeneration is therefore not something a component arranges. Recording a rule returns
 * the reassembled workspace and invalidates the per-table summaries, so the DDL on screen,
 * the exception list beside it and the stage's gate all move together — 「修改映射后契约与
 * DDL 自动重新生成」 falls out of the cache rather than out of a callback.
 */

interface ListResponse<T> {
  readonly items: readonly T[];
}

export const draftTableKeys = {
  configurations: (draftId: string) => dbxQueryKey('migration-drafts', draftId, 'tables'),
  workspace: (draftId: string, sourceTable: string) =>
    dbxQueryKey('migration-drafts', draftId, 'tables', sourceTable),
};

const draftPath = (draftId: string) => `/migration-drafts/${encodeURIComponent(draftId)}`;

/** Every table in the draft's 迁移范围, summarised. Small enough to hold 1200 of them. */
export function useDraftTableConfigurations(draftId: string) {
  return useQuery({
    queryKey: draftTableKeys.configurations(draftId),
    queryFn: async () =>
      (
        await getJson<ListResponse<DraftTableConfiguration>>(
          `${draftPath(draftId)}/table-configurations`,
        )
      ).items,
    enabled: draftId !== '',
  });
}

/**
 * One table's workspace: the object tree, both DDLs and the findings, in one read.
 *
 * One aggregate rather than three requests, because the three panes are one judgement
 * (story 38). Fetched separately they could disagree — a DDL rendered from a contract the
 * exception list beside it has already superseded.
 */
export function useDraftTableWorkspace(draftId: string, sourceTable: string | null) {
  return useQuery({
    queryKey: draftTableKeys.workspace(draftId, sourceTable ?? ''),
    queryFn: () =>
      getJson<DraftTableWorkspace>(
        `${draftPath(draftId)}/tables/${encodeURIComponent(sourceTable ?? '')}`,
      ),
    enabled: draftId !== '' && sourceTable !== null && sourceTable !== '',
  });
}

export function useRecordMappingRule(draftId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    // Serialised: two rules recorded at once are two partial writes against one draft, and
    // landing out of order would quietly restore a decision the operator had replaced.
    scope: { id: 'migration-draft' },
    mutationFn: (request: RecordMappingRuleRequest) =>
      postJson<DraftTableWorkspace>(`${draftPath(draftId)}/mapping-rules`, request),
    onSuccess: (workspace) => {
      // The regenerated contract, straight into the cache: the DDL pane is showing the
      // contract this response carries, not a promise that one is coming.
      queryClient.setQueryData(draftTableKeys.workspace(draftId, workspace.sourceTable), workspace);
      // ADR-0011: a mapping change reruns every affected 预检, so the summaries the object
      // tree and the stage's gate read are no longer to be trusted.
      void queryClient.invalidateQueries({ queryKey: draftTableKeys.configurations(draftId) });
    },
  });
}
