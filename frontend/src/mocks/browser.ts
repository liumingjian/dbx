import { setupWorker } from 'msw/browser';
import { API_BASE } from '@/api/http';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);

/**
 * Starts the mock boundary and resolves only once it is intercepting.
 *
 * Rendering before the worker is ready would let the first queries race it, which shows up
 * later as an intermittently blank first paint. `onUnhandledRequest` is strict for API
 * requests and silent for everything else: in this phase every DBX request is mocked, so
 * an unhandled one is always a bug, while application assets legitimately go to the server.
 */
export async function startMockBoundary(): Promise<void> {
  await worker.start({
    onUnhandledRequest(request, print) {
      if (new URL(request.url).pathname.startsWith(`${API_BASE}/`)) {
        print.error();
      }
    },
  });
}
