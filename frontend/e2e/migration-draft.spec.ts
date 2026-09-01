import { expect, test, type Page } from '@playwright/test';

/**
 * Seam ① (#30): a real browser, real routing, and the same MSW boundary the product runs
 * on. Assertions are in domain language, never on DOM structure or Carbon class names.
 *
 * What this file is about is the 迁移草稿 as a concept of its own: it is created, listed
 * apart from 迁移任务, survives a refresh, and can be discarded without leaving anything
 * behind. None of those verbs exist for a migration task, which is exactly why a draft is
 * not a task with an 「未批准」 flag.
 */

const wizardUrl = /\/tasks\/new\/[^/]+\/connections$/;

async function startDraft(page: Page): Promise<void> {
  await page.goto('/tasks');
  await page.getByRole('button', { name: '新建迁移草稿' }).click();
  await expect(page).toHaveURL(wizardUrl);
}

async function chooseSource(page: Page): Promise<void> {
  const source = page.getByRole('region', { name: '源端' });
  await source.getByLabel('数据库连接').selectOption({ label: '订单库（生产）' });
  await source.getByLabel('源 MySQL database').selectOption({ label: 'orders' });
}

async function chooseTarget(page: Page, connectionName = '分析库（生产）'): Promise<void> {
  const target = page.getByRole('region', { name: '目标端' });
  await target.getByLabel('数据库连接').selectOption({ label: connectionName });
  await target.getByLabel('目标 PostgreSQL schema').fill('orders_migrated');
}

test.describe('迁移草稿 is its own thing, not an unapproved 迁移任务', () => {
  test('is created from the task list and appears in a list of its own', async ({ page }) => {
    await startDraft(page);
    await page.getByRole('link', { name: '迁移任务' }).click();

    const drafts = page.getByRole('region', { name: '迁移草稿' });
    const tasks = page.getByRole('region', { name: '迁移任务' });

    await expect(page.getByRole('heading', { name: '迁移草稿' })).toBeVisible();
    await expect(drafts).toContainText('未命名迁移草稿');
    await expect(drafts).toContainText('尚未选择');
    // A draft is never a row among the approved tasks, and carries none of their columns.
    await expect(tasks).not.toContainText('未命名迁移草稿');
    await expect(drafts).not.toContainText('批准时间');
    await expect(drafts).not.toContainText('最近运行状态');
  });

  test('says what a 迁移草稿 is, in the words CONTEXT.md uses', async ({ page }) => {
    await page.goto('/tasks');

    const lead = page.getByText(/迁移草稿尚未批准/);
    await expect(lead).toContainText('不产生迁移运行');
    await expect(lead).toContainText('丢弃后不留痕迹');
    await expect(lead).toContainText('经执行确认后才成为迁移任务');
  });

  /**
   * The durability seam (#32's persistence adapter, lead decision D3), proved where it can
   * actually be proved: a real browser reload, not a re-render. #32 deliberately left this
   * case to #34 because there was nothing yet that could put a draft into it.
   */
  test('survives a real browser refresh with everything chosen so far', async ({ page }) => {
    await startDraft(page);
    const url = page.url();
    await chooseSource(page);
    await chooseTarget(page);
    await expect(page.getByText('本次迁移落点')).toBeVisible();

    await page.reload();

    await expect(page).toHaveURL(url);
    const source = page.getByRole('region', { name: '源端' });
    await expect(source.getByLabel('数据库连接')).toHaveValue('conn-mysql-orders');
    await expect(source.getByLabel('源 MySQL database')).toHaveValue('orders');
    const target = page.getByRole('region', { name: '目标端' });
    await expect(target.getByLabel('目标 PostgreSQL schema')).toHaveValue('orders_migrated');

    // And it is still in the list a DBA comes back to.
    await page.goto('/tasks');
    await expect(page.getByRole('region', { name: '迁移草稿' })).toContainText('orders_migrated');
  });

  test('is discarded without leaving a trace, and the refresh does not bring it back', async ({
    page,
  }) => {
    await startDraft(page);
    await chooseSource(page);
    await page.goto('/tasks');

    const drafts = page.getByRole('region', { name: '迁移草稿' });
    await expect(drafts).toContainText('未命名迁移草稿');
    await drafts.getByRole('button', { name: '丢弃' }).first().click();

    // Discarding is confirmed once, and the consequence is stated rather than implied.
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('迁移草稿丢弃后不留痕迹');
    await dialog.getByRole('button', { name: '丢弃', exact: true }).click();

    await expect(drafts).toContainText('当前没有迁移草稿');
    await page.reload();
    await expect(page.getByRole('region', { name: '迁移草稿' })).toContainText('当前没有迁移草稿');
    // The approved tasks are untouched: a draft never had anything to do with them.
    await expect(page.getByRole('region', { name: '迁移任务' })).toContainText('MySQL 8.0');
  });
});

test.describe('阶段一：连接与数据库', () => {
  test('shows 源方言 and 目标方言 so the 数据库对 can be confirmed', async ({ page }) => {
    await startDraft(page);
    await chooseSource(page);
    await chooseTarget(page);

    await expect(page.getByRole('region', { name: '源端' })).toContainText('源方言');
    await expect(page.getByRole('region', { name: '源端' })).toContainText('MySQL 8.0');
    await expect(page.getByRole('region', { name: '目标端' })).toContainText('目标方言');
    await expect(page.getByRole('region', { name: '目标端' })).toContainText('PostgreSQL 15');
  });

  test('will not move on while the pair and the databases are incomplete', async ({ page }) => {
    await startDraft(page);
    const url = page.url();

    await expect(page.getByText(/请先选择源与目标的数据库连接/)).toBeVisible();
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(url);

    // Half of it is not enough either.
    await chooseSource(page);
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(url);

    await chooseTarget(page);
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(/\/scope$/);
  });

  test('reports a chosen 数据库连接 that is no longer usable straight away', async ({ page }) => {
    await startDraft(page);
    await chooseSource(page);
    // 分析库（预发）'s 最近校验 is FAILED in the seeded fixture.
    await chooseTarget(page, '分析库（预发）');

    const target = page.getByRole('region', { name: '目标端' });
    await expect(target).toContainText('这个数据库连接现在不可用');
    await expect(target).toContainText('FAILED');

    const url = page.url();
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(url);
    await expect(page.getByText(/不能用于迁移/)).toBeVisible();
  });
});
