import '@testing-library/jest-dom/vitest';
import { afterEach, beforeAll } from 'vitest';
import { cleanup } from '@testing-library/react';

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

afterEach(() => {
  cleanup();
});
