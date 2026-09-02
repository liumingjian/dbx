/**
 * The HTTP edge of the DBX frontend.
 *
 * Everything the application knows about the backend goes through the hand-written
 * contract types and this module (ADR-0016). Replacing the mocks with a real backend is a
 * change here and in `src/mocks/`, not a rewrite of the views — provided no view reaches
 * around either.
 */

/** The one place the API prefix is written down. */
export const API_BASE = '/api';

/** A request that reached the server and came back refused. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string) {
    super(`DBX API responded ${status} (${code})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

interface ApiErrorBody {
  readonly error?: { readonly code?: string };
}

async function readErrorCode(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as ApiErrorBody;
    return body.error?.code ?? 'UNKNOWN';
  } catch {
    return 'UNKNOWN';
  }
}

async function send(path: string, init: RequestInit): Promise<Response> {
  const response = await fetch(`${API_BASE}${path}`, init);
  if (!response.ok) {
    throw new ApiError(response.status, await readErrorCode(response));
  }
  return response;
}

export async function getJson<T>(path: string): Promise<T> {
  const response = await send(path, { method: 'GET', headers: { Accept: 'application/json' } });
  return (await response.json()) as T;
}

export async function patchJson<T>(path: string, body: unknown): Promise<T> {
  const response = await send(path, {
    method: 'PATCH',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return (await response.json()) as T;
}

/** A deletion that leaves no trace has nothing to return but the fact that it happened. */
export async function remove(path: string): Promise<void> {
  await send(path, { method: 'DELETE' });
}

export async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const response = await send(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  });
  return (await response.json()) as T;
}
