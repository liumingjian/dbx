/**
 * Captures the Chinese density sample as a PNG for human review.
 *
 * Issue #31 stops here for a human decision: is Chinese body text readable at a 32px row
 * height? Every later batch is planned against the answer, so the artifact is produced by
 * a script rather than a screenshot someone took by hand.
 *
 *   npm run build && node scripts/capture-density-sample.mjs [outfile]
 */
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from '@playwright/test';

const outFile = process.argv[2] ?? 'density-sample.png';
const port = 4173;
const url = `http://127.0.0.1:${port}/design/density`;

const preview = spawn('npx', ['vite', 'preview', '--port', String(port), '--strictPort'], {
  stdio: 'ignore',
});

try {
  await delay(2000);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: outFile, fullPage: true });
  await browser.close();
  console.log(`wrote ${outFile}`);
} finally {
  preview.kill();
}
