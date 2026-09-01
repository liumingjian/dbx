import { http, HttpResponse, delay } from 'msw';
import type {
  AddCredentialVersionRequest,
  MigrationDraftPatch,
  PruneColumnRequest,
  RecordMappingRuleRequest,
  RegisterDatabaseConnectionRequest,
  WriteFreezeDeclaration,
} from '@/contract';
import { API_BASE } from '@/api/http';
import { getMockContext } from './context';
import type { MockResource, TransportFault } from './scenarios';

/**
 * MSW handlers: the HTTP boundary the application talks to (ADR-0016).
 *
 * Every DBX request goes through here in this phase, which is why an unhandled request is
 * treated as a failure rather than passed through to the network — see `browser.ts`.
 */

interface ApiErrorBody {
  readonly error: { readonly code: string; readonly detail: string };
}

function apiError(status: number, code: string, detail: string): HttpResponse<ApiErrorBody> {
  return HttpResponse.json({ error: { code, detail } }, { status });
}

/**
 * Applies the scenario's transport plan for one resource.
 *
 * Returns a response when the scenario says this request must not succeed, and `undefined`
 * when the handler should carry on. `pending` returns a promise that never settles, which
 * is how a loading state is reached deterministically instead of by racing a timer.
 */
async function applyTransportFault(resource: MockResource): Promise<Response | undefined> {
  const fault: TransportFault | undefined =
    getMockContext().scenario.definition.transport[resource];
  if (fault === undefined) {
    return undefined;
  }
  switch (fault.kind) {
    case 'pending':
      await new Promise(() => {});
      return undefined;
    case 'slow':
      await delay(fault.realMilliseconds);
      return undefined;
    case 'failure':
      return apiError(fault.status, 'SCENARIO_TRANSPORT_FAILURE', 'Scenario transport fault.');
  }
}

const notFound = () => apiError(404, 'NOT_FOUND', 'No such record in the mock store.');

export const handlers = [
  http.get(`${API_BASE}/database-connections`, async () => {
    const faulted = await applyTransportFault('databaseConnections');
    if (faulted) return faulted;
    return HttpResponse.json({ items: getMockContext().store.listDatabaseConnections() });
  }),

  http.post(`${API_BASE}/database-connections`, async ({ request }) => {
    const faulted = await applyTransportFault('databaseConnections');
    if (faulted) return faulted;
    const body = (await request.json()) as RegisterDatabaseConnectionRequest;
    const created = getMockContext().store.registerDatabaseConnection(body);
    return HttpResponse.json(created, { status: 201 });
  }),

  // Credentials are immutable versions, so maintaining one appends rather than edits.
  http.post(
    `${API_BASE}/database-connections/:id/credential-versions`,
    async ({ params, request }) => {
      const faulted = await applyTransportFault('databaseConnections');
      if (faulted) return faulted;
      const body = (await request.json()) as AddCredentialVersionRequest;
      const updated = getMockContext().store.addCredentialVersion(String(params.id), body);
      return updated ? HttpResponse.json(updated) : notFound();
    },
  ),

  http.post(`${API_BASE}/database-connections/:id/checks`, async ({ params }) => {
    const faulted = await applyTransportFault('databaseConnections');
    if (faulted) return faulted;
    const updated = getMockContext().store.checkDatabaseConnection(String(params.id));
    return updated ? HttpResponse.json(updated) : notFound();
  }),

  http.get(`${API_BASE}/migration-drafts`, async () => {
    const faulted = await applyTransportFault('migrationDrafts');
    if (faulted) return faulted;
    return HttpResponse.json({ items: getMockContext().store.listMigrationDrafts() });
  }),

  http.post(`${API_BASE}/migration-drafts`, async ({ request }) => {
    const faulted = await applyTransportFault('migrationDrafts');
    if (faulted) return faulted;
    const body = (await request.json().catch(() => ({}))) as MigrationDraftPatch;
    return HttpResponse.json(getMockContext().store.createMigrationDraft(body), { status: 201 });
  }),

  http.get(`${API_BASE}/migration-drafts/:id`, async ({ params }) => {
    const faulted = await applyTransportFault('migrationDrafts');
    if (faulted) return faulted;
    const draft = getMockContext().store.getMigrationDraft(String(params.id));
    return draft ? HttpResponse.json(draft) : notFound();
  }),

  http.patch(`${API_BASE}/migration-drafts/:id`, async ({ params, request }) => {
    const faulted = await applyTransportFault('migrationDrafts');
    if (faulted) return faulted;
    const body = (await request.json()) as MigrationDraftPatch;
    const updated = getMockContext().store.updateMigrationDraft(String(params.id), body);
    return updated ? HttpResponse.json(updated) : notFound();
  }),

  // 逐表配置 belongs to the 迁移草稿, so it hangs off the draft rather than off a
  // 表迁移单元 — there is no 迁移运行 yet, and inventing one would put audit-grade
  // records behind an unapproved working set.
  http.get(`${API_BASE}/migration-drafts/:id/table-configurations`, async ({ params }) => {
    const faulted = await applyTransportFault('draftTableConfigurations');
    if (faulted) return faulted;
    const items = getMockContext().store.listDraftTableConfigurations(String(params.id));
    return items ? HttpResponse.json({ items }) : notFound();
  }),

  http.get(`${API_BASE}/migration-drafts/:id/tables/:sourceTable`, async ({ params }) => {
    const faulted = await applyTransportFault('draftTableConfigurations');
    if (faulted) return faulted;
    const workspace = getMockContext().store.getDraftTableWorkspace(
      String(params.id),
      String(params.sourceTable),
    );
    return workspace ? HttpResponse.json(workspace) : notFound();
  }),

  // A mapping change is a structured 映射规则, never SQL (`CONTEXT.md`). Recording one
  // reassembles the 表写入契约 and reruns the affected 预检 (ADR-0011), which is why the
  // response is the regenerated workspace rather than an acknowledgement.
  http.post(`${API_BASE}/migration-drafts/:id/mapping-rules`, async ({ params, request }) => {
    const faulted = await applyTransportFault('draftTableConfigurations');
    if (faulted) return faulted;
    const body = (await request.json()) as RecordMappingRuleRequest;
    const workspace = getMockContext().store.recordMappingRule(String(params.id), body);
    return workspace ? HttpResponse.json(workspace) : notFound();
  }),

  // ADR-0003's second exit: cutting an offending column out of the selected columns. It
  // is a change to what the contract would write, so it reruns the table's 预检 rather
  // than editing a conclusion — there is no endpoint anywhere that accepts one.
  http.post(`${API_BASE}/migration-drafts/:id/pruned-columns`, async ({ params, request }) => {
    const faulted = await applyTransportFault('draftTableConfigurations');
    if (faulted) return faulted;
    const body = (await request.json()) as PruneColumnRequest;
    const workspace = getMockContext().store.pruneColumn(String(params.id), body);
    return workspace ? HttpResponse.json(workspace) : notFound();
  }),

  // ADR-0003's first exit: the operator fixed the source outside DBX and asks for a fresh
  // reading. A rerun is a new evaluation, which is why it is a POST to a collection of
  // runs and not a PATCH of the conclusion.
  http.post(
    `${API_BASE}/migration-drafts/:id/tables/:sourceTable/preflight-runs`,
    async ({ params }) => {
      const faulted = await applyTransportFault('draftTableConfigurations');
      if (faulted) return faulted;
      const workspace = getMockContext().store.rerunPreflight(
        String(params.id),
        String(params.sourceTable),
      );
      return workspace ? HttpResponse.json(workspace) : notFound();
    },
  ),

  // 执行确认's summary: one aggregate rather than six reads, because it is one global
  // check and halves fetched separately could contradict each other.
  http.get(`${API_BASE}/migration-drafts/:id/execution-confirmation`, async ({ params }) => {
    const faulted = await applyTransportFault('executionConfirmation');
    if (faulted) return faulted;
    const summary = getMockContext().store.summariseExecutionConfirmation(String(params.id));
    return summary ? HttpResponse.json(summary) : notFound();
  }),

  /**
   * Starting the migration: the 迁移草稿 becomes a 迁移任务 and its first 迁移运行.
   *
   * A POST to the runs of a draft rather than a PATCH of the draft, because nothing is
   * being edited — an immutable record is being created, and the draft ceases to exist.
   * The refusals are the server's own: Gate 5 and Gate 6 are constraints on the system,
   * not on the button, so a request that never went past the wizard is refused here too.
   */
  http.post(`${API_BASE}/migration-drafts/:id/migration-runs`, async ({ params, request }) => {
    const faulted = await applyTransportFault('executionConfirmation');
    if (faulted) return faulted;
    const body = (await request.json()) as WriteFreezeDeclaration;
    const result = getMockContext().store.startMigrationRun(String(params.id), body);
    if (result.ok) {
      return HttpResponse.json({ task: result.task, run: result.run }, { status: 201 });
    }
    return result.code === 'NOT_FOUND'
      ? notFound()
      : apiError(409, result.code, 'The migration draft may not be started.');
  }),

  http.get(`${API_BASE}/migration-tasks`, async () => {
    const faulted = await applyTransportFault('migrationTasks');
    if (faulted) return faulted;
    return HttpResponse.json({ items: getMockContext().store.listMigrationTasks() });
  }),

  http.get(`${API_BASE}/migration-tasks/:id`, async ({ params }) => {
    const faulted = await applyTransportFault('migrationTasks');
    if (faulted) return faulted;
    const task = getMockContext().store.getMigrationTask(String(params.id));
    return task ? HttpResponse.json(task) : notFound();
  }),

  // A rerun is a new migration run rather than a retry in place (`CONTEXT.md`), so a
  // task's runs are a history and never a single mutable record.
  http.get(`${API_BASE}/migration-tasks/:id/runs`, async ({ params }) => {
    const faulted = await applyTransportFault('migrationRuns');
    if (faulted) return faulted;
    return HttpResponse.json({
      items: getMockContext().store.listMigrationRuns(String(params.id)),
    });
  }),

  /**
   * 运行监控's one read: the whole 迁移运行 at one instant (#38).
   *
   * One aggregate rather than four reads, for the same reason 执行确认's summary is one:
   * units, 卡死, timeline and log fetched separately could disagree with each other, and a
   * monitoring screen whose halves describe different instants is worse than no screen.
   * The response carries its own `observedAt` so a view can say when it was true.
   *
   * A GET that the client repeats is deliberately all this endpoint is. ADR-0007 leaves
   * the live-update transport undecided, so the mock offers the plainest thing a polling,
   * an SSE or a WebSocket implementation could all deliver, and picks none of them.
   */
  http.get(`${API_BASE}/migration-runs/:id/progress`, async ({ params }) => {
    const faulted = await applyTransportFault('tableMigrationUnits');
    if (faulted) return faulted;
    const snapshot = getMockContext().store.getRunProgress(String(params.id));
    return snapshot ? HttpResponse.json(snapshot) : notFound();
  }),

  // What a 取消 would stop, read before it is requested rather than described in a dialog.
  http.get(`${API_BASE}/migration-runs/:id/cancellation`, async ({ params }) => {
    const faulted = await applyTransportFault('migrationRuns');
    if (faulted) return faulted;
    const consequences = getMockContext().store.describeRunCancellation(String(params.id));
    return consequences ? HttpResponse.json(consequences) : notFound();
  }),

  /**
   * Requesting a 取消: a terminal stop that preserves target data and diagnostic evidence.
   *
   * A POST to the run's cancellation rather than a PATCH of the run, because a 迁移运行 is
   * immutable: what is created is a request, and what changes is what the platform makes
   * of the run from that instant onwards. Nothing is deleted or rolled back — 「discard」
   * and 「rollback」 are under 取消's `_Avoid_`, and 丢弃 is a separate operation.
   */
  http.post(`${API_BASE}/migration-runs/:id/cancellation`, async ({ params }) => {
    const faulted = await applyTransportFault('migrationRuns');
    if (faulted) return faulted;
    const snapshot = getMockContext().store.requestRunCancellation(String(params.id));
    return snapshot ? HttpResponse.json(snapshot) : notFound();
  }),

  http.get(`${API_BASE}/source-tables`, async ({ request }) => {
    const faulted = await applyTransportFault('tableMigrationUnits');
    if (faulted) return faulted;
    const sourceDatabase = new URL(request.url).searchParams.get('sourceDatabase') ?? 'orders';
    return HttpResponse.json({ items: getMockContext().store.listSourceTables(sourceDatabase) });
  }),

  // Discarding a draft leaves no trace; there is nothing to return but the fact of it.
  http.delete(`${API_BASE}/migration-drafts/:id`, async ({ params }) => {
    const faulted = await applyTransportFault('migrationDrafts');
    if (faulted) return faulted;
    const discarded = getMockContext().store.discardMigrationDraft(String(params.id));
    return discarded ? new HttpResponse(null, { status: 204 }) : notFound();
  }),
];
