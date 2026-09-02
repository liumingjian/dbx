import { fileURLToPath } from 'node:url';
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
      alias: {
        // See `src/test/stubs/lottie-web.ts`: the table substrate's package entry draws to
        // a canvas at import time, which jsdom does not implement.
        'lottie-web': fileURLToPath(new URL('./src/test/stubs/lottie-web.ts', import.meta.url)),
      },
      css: false,
      include: ['src/**/*.test.{ts,tsx}', 'vite/**/*.test.ts'],
      // Seam 2's central claim — that `DbxTable`'s public interface does not leak
      // `Datagrid` or `react-table` types (ADR-0015) — is a type-level claim that no
      // runtime assertion can make. `*.test-d.ts` files make it, and enabling typecheck
      // here is what makes `npm test` actually run them.
      typecheck: {
        enabled: true,
        include: ['src/**/*.test-d.ts'],
        tsconfig: './tsconfig.app.json',
      },
    },
  }),
);
