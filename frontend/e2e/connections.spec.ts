import { expect, test } from '@playwright/test';

/**
 * Seam ① (#30): the application's outer edge — a real browser, real routing, and the same
 * MSW boundary the product runs on. Assertions are in domain language, never on DOM
 * structure or Carbon's internal class names.
 *
 * The scenario parameter is what makes the state coverage reachable: each of loading,
 * empty and error is entered by opening a URL, exactly as a reviewer would.
 */

test.describe('数据源: the registry of 数据库连接', () => {
  test('lists each 数据库连接 with its 凭据版本 and 最近校验', async ({ page }) => {
    await page.goto('/connections');

    const list = page.getByRole('list', { name: '数据库连接' });
    const first = list.getByRole('listitem').first();

    await expect(first).toContainText('凭据版本');
    await expect(first).toContainText('最近校验');
    // A check reports its own outcome and the instant it was observed, in the wording
    // `CONTEXT.md` fixes for a 连接校验 outcome.
    await expect(list).toContainText('校验通过');
    await expect(list).toContainText('UTC');

    // A connection that has never been checked says so rather than implying health.
    await expect(list).toContainText('尚未校验');
    // And one whose credential no longer works says so too.
    await expect(list).toContainText('校验失败');
  });

  test('calls the area 数据源 and each endpoint a 数据库连接', async ({ page }) => {
    await page.goto('/connections');

    // `Database connection` lists `datasource` and `JDBC URL` under `_Avoid_`: the
    // navigation area is the 数据源, the row is never one.
    await expect(page.getByRole('heading', { name: '数据源' })).toBeVisible();
    await expect(page.getByRole('list', { name: '数据库连接' })).toBeVisible();
    await expect(page.locator('body')).not.toContainText('JDBC');
    await expect(page.locator('body')).not.toContainText('作业');
  });

  test('re-checking a 数据库连接 records a new 最近校验 result', async ({ page }) => {
    await page.goto('/connections');

    const failing = page.getByRole('listitem').filter({ hasText: '分析库（预发）' });
    await expect(failing).toContainText('校验失败');

    const neverChecked = page.getByRole('listitem').filter({ hasText: '计费库（生产）' });
    await expect(neverChecked).toContainText('尚未校验');
    await neverChecked.getByRole('button', { name: '重新校验' }).click();

    await expect(neverChecked).toContainText('校验通过');
    await expect(neverChecked).not.toContainText('尚未校验');
  });
});

test.describe('连接创建与凭据维护只在数据源里', () => {
  test('registers a 数据库连接 and its first 凭据版本 here', async ({ page }) => {
    await page.goto('/connections');

    await page.getByRole('button', { name: '登记数据库连接' }).click();
    await page.getByLabel('名称').fill('报表库（生产）');
    await page.getByLabel('主机').fill('mysql-report.prod.internal');
    // `exact` because the list behind the modal is labelled 数据库连接.
    await page.getByLabel('数据库', { exact: true }).fill('report');
    await page.getByLabel('用户名').fill('dbx_reader');
    await page.getByLabel('凭据版本').fill('example-secret');
    await page.getByRole('button', { name: '登记', exact: true }).click();

    const created = page.getByRole('listitem').filter({ hasText: '报表库（生产）' });
    await expect(created).toBeVisible();
    // A brand-new connection has not been checked yet, and says so.
    await expect(created).toContainText('尚未校验');
    await expect(created).toContainText('v1');
  });

  test('adds a 凭据版本 rather than editing one, and invalidates the old 最近校验', async ({
    page,
  }) => {
    await page.goto('/connections');

    const connection = page.getByRole('listitem').filter({ hasText: '订单库（生产）' });
    await expect(connection).toContainText('v3');
    await expect(connection).toContainText('校验通过');

    await connection.getByRole('button', { name: '新建凭据版本' }).click();
    await page.getByLabel('凭据版本').fill('rotated-secret');
    await page.getByRole('button', { name: '新建', exact: true }).click();

    await expect(connection).toContainText('v4');
    // The previous check authenticated with a version that is no longer current.
    await expect(connection).toContainText('尚未校验');
  });

  test('the migration wizard offers no way to type a credential', async ({ page }) => {
    // `Data source management`: connection creation and credential entry happen only in
    // 数据源, never inline inside the wizard. This is the guard for that.
    //
    // It goes through a real 迁移草稿 rather than an invented draft id: a stage that cannot
    // load a draft renders nothing, and would pass this test by having no interface at all.
    await page.goto('/tasks');
    await page.getByRole('button', { name: '新建迁移草稿' }).click();
    await expect(page).toHaveURL(/\/tasks\/new\/[^/]+\/connections$/);
    await page
      .getByRole('region', { name: '源端' })
      .getByLabel('数据库连接')
      .selectOption({ label: '订单库（生产）' });

    await expect(page.getByRole('button', { name: '登记数据库连接' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '新建凭据版本' })).toHaveCount(0);
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    // The only route to a credential from here is out to 数据源.
    await expect(page.getByRole('link', { name: '前往数据源' })).toBeVisible();
  });
});

test.describe('state coverage reached from the URL scenario parameter', () => {
  test('loading', async ({ page }) => {
    await page.goto('/connections?scenario=loading');

    await expect(page.getByText('正在读取数据库连接。')).toBeVisible();
    await expect(page.getByRole('list', { name: '数据库连接' })).toHaveCount(0);
  });

  test('empty, naming the next action rather than showing a blank page', async ({ page }) => {
    await page.goto('/connections?scenario=empty');

    await expect(page.getByText('尚未登记任何数据库连接')).toBeVisible();
    await expect(
      page.getByText(/下一步：登记源 MySQL 与目标 PostgreSQL 的数据库连接/),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '登记数据库连接' })).toBeVisible();
  });

  test('error, offering a retry rather than a blank page', async ({ page }) => {
    await page.goto('/connections?scenario=error');

    const alert = page.getByRole('alert');
    await expect(alert).toContainText('数据库连接读取失败');
    await expect(alert.getByRole('button', { name: '重试' })).toBeVisible();
    await expect(page.getByRole('list', { name: '数据库连接' })).toHaveCount(0);
  });

  test('an unknown scenario falls back to the default one', async ({ page }) => {
    await page.goto('/connections?scenario=nonesuch');

    await expect(page.getByRole('list', { name: '数据库连接' })).toBeVisible();
  });
});
