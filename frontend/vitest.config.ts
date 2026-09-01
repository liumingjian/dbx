import { mergeConfig } from 'vite';
import { defineConfig } from 'vitest/config';
import viteConfig from './vite.config';

// Seam 2 (see #30): unit tests run against module interfaces, not the browser.
// Everything journey-shaped belongs in the Playwright suite under `e2e/`.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      include: ['src/**/*.test.{ts,tsx}', 'vite/**/*.test.ts'],
    },
  }),
);
