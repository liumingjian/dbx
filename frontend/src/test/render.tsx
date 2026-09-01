import type { ReactElement, ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryRouter } from 'react-router-dom';
import { render, type RenderResult } from '@testing-library/react';
import { routerFutureFlags, routes } from '@/routes/router';

/**
 * The shared render helper for seam 2 (lead decision D14).
 *
 * Every component test needs the same two providers, and getting either wrong is a quiet
 * source of flakiness: a shared `QueryClient` leaks one test's cache into the next, and
 * retries turn a deliberate error scenario into a slow one. Both are settled here once.
 */

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // A test that wants to see the error state must not wait for a retry first.
        retry: false,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  });
}

/**
 * Points the mock boundary at a scenario, the way a reviewer's link does.
 *
 * The scenario lives in the URL rather than in an injected option (ADR-0016), so a test
 * chooses one the same way a person does. `src/test/setup.ts` drops the resolved context
 * after every test, so one scenario never survives into the next.
 */
export function enterScenario(scenarioId: string, path = '/'): void {
  const search = scenarioId === '' ? '' : `?scenario=${encodeURIComponent(scenarioId)}`;
  window.history.replaceState({}, '', `${path}${search}`);
}

export function withProviders(ui: ReactNode, queryClient = createTestQueryClient()): ReactElement {
  return <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>;
}

/** Renders one component under the providers, without a router. */
export function renderWithProviders(ui: ReactNode): RenderResult {
  return render(withProviders(ui));
}

/** Renders the whole application at one route, the way `router.test.tsx` does. */
export function renderRoute(initialPath: string): RenderResult {
  const router = createMemoryRouter(routes, {
    initialEntries: [initialPath],
    future: routerFutureFlags,
  });
  return render(
    withProviders(<RouterProvider router={router} future={{ v7_startTransition: true }} />),
  );
}
