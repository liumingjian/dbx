import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';
import { resetMockContext } from '@/mocks/context';
import { server } from '@/mocks/node';

beforeAll(() => {
  // Carbon's UI Shell reads `matchMedia`, which jsdom does not implement. Reporting a
  // non-matching query keeps components on their default, non-responsive branch.
  if (!window.matchMedia) {
    window.matchMedia = (query: string): MediaQueryList =>
      ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList;
  }
});

// The same MSW handlers the browser uses (ADR-0016). `error` rather than `bypass`: in
// this phase every DBX request is mocked, so an unhandled one is always a bug.
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  // A scenario's store must not survive into the next test.
  resetMockContext();
});

afterAll(() => {
  server.close();
});
