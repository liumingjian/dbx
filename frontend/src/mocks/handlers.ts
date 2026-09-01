import { http, HttpResponse, delay } from 'msw';
import type {
  AddCredentialVersionRequest,
  MigrationDraftPatch,
  RegisterDatabaseConnectionRequest,
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
