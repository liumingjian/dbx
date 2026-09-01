import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  DraftTableConfiguration,
  DraftTableWorkspace,
  PruneColumnRequest,
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
 *
 * The same holds for the 预检 the change invalidates (#36). A rerun takes time, so both
 * reads poll while any conclusion is missing and stop the moment they are all back. That
 * is the whole mechanism behind 「旧结论不会留在屏幕上」: the conclusion is absent while the
 * scan runs, so there is nothing stale left to render.
 */

/** How often an unconcluded 预检 is asked about again, in real milliseconds. */
const PREFLIGHT_POLL_MS = 400;

interface ListResponse<T> {
  readonly items: readonly T[];
}

export const draftTableKeys = {
  configurations: (draftId: string) => dbxQueryKey('migration-drafts', draftId, 'tables'),
  workspace: (draftId: string, sourceTable: string) =>
    dbxQueryKey('migration-drafts', draftId, 'tables', sourceTable),
};

const draftPath = (draftId: string) => `/migration-drafts/${encodeURIComponent(draftId)}`;

/**
 * Every table in the draft's 迁移范围, summarised.
 *
 * `enabled` is a real parameter rather than a convenience: assembling these summaries runs
 * the same column assembly the single-table workspace does, for **every** table in the
 * 迁移范围 (D27). At 1200 tables that is not something the first two stages should pay for
 * — neither of their gates reads this list. The polarity is unchanged where it is off:
 * `null` still blocks, because an unknown safety fact is not a satisfied one (D22).
 */
export function useDraftTableConfigurations(draftId: string, enabled = true) {
  return useQuery({
    queryKey: draftTableKeys.configurations(draftId),
    queryFn: async () =>
      (
        await getJson<ListResponse<DraftTableConfiguration>>(
          `${draftPath(draftId)}/table-configurations`,
        )
      ).items,
    enabled: enabled && draftId !== '',
    // A missing conclusion means a scan is still running, and the stage's gate reads this
    // list: leaving it stale would leave the wizard reporting a fact that has expired.
    refetchInterval: (query) =>
      (query.state.data ?? []).some((configuration) => configuration.preflightConclusion === null)
        ? PREFLIGHT_POLL_MS
        : false,
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
    refetchInterval: (query) =>
      query.state.data?.preflight.conclusion === null ? PREFLIGHT_POLL_MS : false,
  });
}

/**
 * The three writes stage three can make, all of the same shape.
 *
 * Each one changes what the 表写入契约 would write and therefore invalidates the evidence
 * that was reached against the previous one. They share this hook so that no caller can
 * make one of the three without the reassembled workspace landing in the cache and the
 * per-table summaries — the stage's gate among them — being invalidated.
 */
function useWorkspaceMutation<TRequest>(
  draftId: string,
  send: (request: TRequest) => Promise<DraftTableWorkspace>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    // Serialised: two changes at once are two partial writes against one draft, and
    // landing out of order would quietly restore a decision the operator had replaced.
    scope: { id: 'migration-draft' },
    mutationFn: send,
    onSuccess: (workspace: DraftTableWorkspace) => {
      // The regenerated contract and the rerunning 预检, straight into the cache: the pane
      // is showing the evidence this response carries, not a promise that some is coming.
      queryClient.setQueryData(draftTableKeys.workspace(draftId, workspace.sourceTable), workspace);
      // ADR-0011: a mapping change reruns every affected 预检, so the summaries the object
      // tree and the stage's gate read are no longer to be trusted.
      void queryClient.invalidateQueries({ queryKey: draftTableKeys.configurations(draftId) });
    },
  });
}

export function useRecordMappingRule(draftId: string) {
  return useWorkspaceMutation(draftId, (request: RecordMappingRuleRequest) =>
    postJson<DraftTableWorkspace>(`${draftPath(draftId)}/mapping-rules`, request),
  );
}

/** ADR-0003's second exit: cut an offending column, and rerun the 预检 without it. */
export function usePruneColumn(draftId: string) {
  return useWorkspaceMutation(draftId, (request: PruneColumnRequest) =>
    postJson<DraftTableWorkspace>(`${draftPath(draftId)}/pruned-columns`, request),
  );
}

/** ADR-0003's first exit: the source was fixed outside DBX; read the facts again. */
export function useRerunPreflight(draftId: string) {
  return useWorkspaceMutation(draftId, (sourceTable: string) =>
    postJson<DraftTableWorkspace>(
      `${draftPath(draftId)}/tables/${encodeURIComponent(sourceTable)}/preflight-runs`,
      {},
    ),
  );
}
