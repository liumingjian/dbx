import { expect, test } from '@playwright/test';

// Seam 1 (#30): the application's outer edge. Batch 1 only has the shell, so this file
// is the smoke case the later journey gates will grow from.
test.describe('product shell', () => {
  test('opens on the migration task list and names the three destinations', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/tasks$/);

    const nav = page.getByRole('navigation', { name: '主导航' });
    await expect(nav.getByRole('link', { name: '迁移任务' })).toBeVisible();
    await expect(nav.getByRole('link', { name: '数据源' })).toBeVisible();
    await expect(nav.getByRole('link', { name: '系统设置' })).toBeVisible();

    // `Migration task` lists Job under `_Avoid_`; 「作业」 must not appear anywhere.
    await expect(page.locator('body')).not.toContainText('作业');
  });

  test('navigates to data-source management', async ({ page }) => {
    await page.goto('/tasks');
    await page.getByRole('link', { name: '数据源' }).click();

    await expect(page).toHaveURL(/\/connections$/);
    await expect(page.getByRole('heading', { name: '数据源' })).toBeVisible();
  });
});

test.describe('Chinese typography layer (ADR-0014)', () => {
  test('applies the fixed override values', async ({ page }) => {
    await page.goto('/design/density');

    const body = page.locator('body');
    await expect(body).toHaveCSS('letter-spacing', 'normal');
    await expect(body).toHaveCSS(
      'font-family',
      /^"IBM Plex Sans", "IBM Plex Sans SC", -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif$/,
    );

    // label-01 and helper-text-01 are 13px, not Carbon's 12px — asserted on Carbon's own
    // markup, because the point of overriding the tokens rather than patching selected
    // classes is that every Carbon component inherits the change.
    await expect(page.getByText(/源表名称/)).toHaveCSS('font-size', '13px');
    await expect(page.locator('.cds--form__helper-text')).toHaveCSS('font-size', '13px');
    await expect(page.locator('.cds--form__helper-text')).toHaveCSS('letter-spacing', 'normal');

    // body-compact-01 line height is 1.45; at 14px that is 20.3px.
    const cell = page.locator('[data-testid="density-sample-condensed"] tbody td').first();
    await expect(cell).toHaveCSS('line-height', '20.3px');
  });

  test('renders the 32px condensed row height that the density plan rests on', async ({ page }) => {
    await page.goto('/design/density');

    const condensedRow = page.locator('[data-testid="density-sample-condensed"] tbody tr').first();
    await expect(condensedRow).toHaveJSProperty('offsetHeight', 32);

    const comfortableRow = page
      .locator('[data-testid="density-sample-comfortable"] tbody tr')
      .first();
    await expect(comfortableRow).toHaveJSProperty('offsetHeight', 40);
  });
});
