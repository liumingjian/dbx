import { defineConfig, devices } from '@playwright/test';

/**
 * The suite serves the app on its own port, chosen per run.
 *
 * A fixed port is not safe here: an editor with automatic port forwarding will claim any
 * `http://127.0.0.1:<port>` it sees go past in terminal output, and then listen on it.
 * When that happened the suite adopted the editor's listener and waited on it for
 * eighteen minutes. Picking a fresh port per run keeps runs out of each other's way and
 * out of a squatter's way.
 *
 * The choice is written back into the environment because Playwright re-evaluates this
 * config in every worker process: without that, each worker would pick its own port and
 * none of them would match the server. `DBX_E2E_PORT` also lets a caller pin it.
 */
const port = Number(process.env.DBX_E2E_PORT) || 40000 + Math.floor(Math.random() * 20000);
process.env.DBX_E2E_PORT = String(port);
const baseURL = `http://127.0.0.1:${port}`;

// Seam 1 (see #30): the application's outer edge — real browser, real routing.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : [['list']],
  use: {
    baseURL,
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // Never adopt a server this suite did not start, and fail loudly rather than hang if
  // the port is taken.
  webServer: {
    command: `npm run dev -- --host 127.0.0.1 --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
