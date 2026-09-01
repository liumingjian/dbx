import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';
import { resetMockContext } from '@/mocks/context';
import { server } from '@/mocks/node';

// The table substrate's package entry pulls in `lottie-web`, which probes a canvas the
// moment it is imported, for its illustrated empty states. jsdom implements no canvas, and
// the probe aborts the whole module — so this has to run before any test file is imported,
// not inside `beforeAll`. DBX renders its own empty states (`src/components/ViewState.tsx`),
// so nothing under test depends on this beyond its being importable.
if (typeof HTMLCanvasElement !== 'undefined') {
  HTMLCanvasElement.prototype.getContext = (() =>
    new Proxy(
      {},
      {
        get: () => () => {},
        set: () => true,
      },
    )) as unknown as HTMLCanvasElement['getContext'];
}

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

  // jsdom in this configuration exposes no `localStorage`, and the density preference is
  // deliberately a browser-owned fact rather than an injected dependency (the operator's
  // choice of row height belongs to their machine, not to a component tree). A Map-backed
  // stand-in keeps that design intact under test.
  if (!('localStorage' in window) || window.localStorage === undefined) {
    const entries = new Map<string, string>();
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        get length() {
          return entries.size;
        },
        getItem: (key: string) => entries.get(key) ?? null,
        setItem: (key: string, value: string) => void entries.set(key, String(value)),
        removeItem: (key: string) => void entries.delete(key),
        clear: () => entries.clear(),
        key: (index: number) => [...entries.keys()][index] ?? null,
      } satisfies Storage,
    });
  }

  // The table substrate observes its own width so it can keep a sticky column aligned.
  // jsdom implements no layout and therefore no ResizeObserver; reporting nothing keeps
  // the components on the branch that does not depend on measurement.
  if (!('ResizeObserver' in globalThis)) {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
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
