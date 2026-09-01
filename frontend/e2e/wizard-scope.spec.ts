import { expect, test, type Page } from '@playwright/test';

/**
 * Seam ① — 阶段二 迁移范围, and **Gate 1** of the nine journey gates (#30 §15.4).
 *
 * A gate is a constraint, so the case that matters is the one proving it **blocked**
 * something. Walking the happy path would not distinguish a gate from a button that happens
 * to be wired up. Both routes into the next stage are closed here: pressing 下一步, and
 * typing the next stage's URL.
 */

async function draftAtScope(page: Page): Promise<string> {
  await page.goto('/tasks');
  await page.getByRole('button', { name: '新建迁移草稿' }).click();
  await expect(page).toHaveURL(/\/tasks\/new\/[^/]+\/connections$/);
  const draftId = page.url().split('/tasks/new/')[1]?.split('/')[0] ?? '';

  const source = page.getByRole('region', { name: '源端' });
  await source.getByLabel('数据库连接').selectOption({ label: '订单库（生产）' });
  await source.getByLabel('源 MySQL database').selectOption({ label: 'orders' });
  const target = page.getByRole('region', { name: '目标端' });
  await target.getByLabel('数据库连接').selectOption({ label: '分析库（生产）' });
  await target.getByLabel('目标 PostgreSQL schema').fill('orders_migrated');

  await page.getByRole('button', { name: '下一步' }).click();
  await expect(page).toHaveURL(/\/scope$/);
  await expect(page.getByRole('region', { name: '源表' })).toBeVisible();
  return draftId;
}

function scopeTable(page: Page) {
  return page.getByRole('region', { name: '源表' });
}

test.describe('Gate 1: 一张表都没选时不能前进', () => {
  test('blocks 下一步, says why, and does not leave the stage', async ({ page }) => {
    await draftAtScope(page);
    const url = page.url();

    await expect(page.getByText(/请先选择至少一张表纳入迁移范围/)).toBeVisible();
    await page.getByRole('button', { name: '下一步' }).click();

    await expect(page).toHaveURL(url);
    await expect(page.getByRole('heading', { name: '新建迁移草稿' })).toBeVisible();
    await expect(page.getByText('迁移范围共 0 张')).toBeVisible();
  });

  test('blocks the next stage typed as a URL, not only the button', async ({ page }) => {
    // Every stage has its own address (#30), so a gate that only guards a button guards
    // nothing. The draft is sent back to the stage that is actually stopping it.
    const draftId = await draftAtScope(page);

    await page.goto(`/tasks/new/${draftId}/tables`);
    await expect(page).toHaveURL(new RegExp(`/tasks/new/${draftId}/scope$`));

    await page.goto(`/tasks/new/${draftId}/validation`);
    await expect(page).toHaveURL(new RegExp(`/tasks/new/${draftId}/scope$`));
  });

  test('opens once a table is in the 迁移范围, and closes again when it is taken out', async ({
    page,
  }) => {
    const draftId = await draftAtScope(page);
    const table = scopeTable(page);

    await table.getByRole('checkbox').first().check({ force: true });
    await expect(page.getByText('迁移范围共 1 张')).toBeVisible();
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(new RegExp(`/tasks/new/${draftId}/tables$`));

    // Returning to a completed stage is allowed; undoing the choice closes the gate again.
    await page.goto(`/tasks/new/${draftId}/scope`);
    await scopeTable(page).getByRole('checkbox').first().uncheck({ force: true });
    await expect(page.getByText('迁移范围共 0 张')).toBeVisible();
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(new RegExp(`/tasks/new/${draftId}/scope$`));
  });
});

test.describe('阶段二：在 1200 张表里选迁移范围', () => {
  test('keeps the row count bounded and the 表名 column visible while scrolling', async ({
    page,
  }) => {
    await draftAtScope(page);
    const table = scopeTable(page);

    // 1200 tables, a bounded window of rows. The claim #30 makes about production scale is
    // that the interface does not mount the database.
    const mounted = await page.getByRole('row').count();
    expect(mounted).toBeLessThan(100);
    expect(mounted).toBeGreaterThan(1);

    const box = await table.boundingBox();
    expect(box).not.toBeNull();
    const centre = box as NonNullable<typeof box>;
    await page.mouse.move(centre.x + centre.width / 2, centre.y + centre.height / 2);

    // The leftmost cell whose text reads like a table identifier — the 表名 column, found
    // by what it contains rather than by where it sits in the markup.
    const firstName = table
      .getByRole('row')
      .nth(1)
      .getByRole('cell')
      .filter({ hasText: /^[a-z][a-z0-9_]+$/ })
      .first();
    const nameBefore = ((await firstName.textContent()) ?? '').trim();
    expect(nameBefore).not.toBe('');

    // Sideways: the identifying column stays put, so the facts on the right stay attached
    // to a table you can still name.
    await page.mouse.wheel(600, 0);
    await expect(firstName).toBeInViewport();
    await expect(firstName).toHaveText(nameBefore);

    // Downwards: still bounded.
    await page.mouse.wheel(0, 4000);
    await expect.poll(() => page.getByRole('row').count()).toBeLessThan(100);
  });

  test('opens the same source database in the same order twice', async ({ page }) => {
    // User story 31: two screenshots of one database have to be comparable.
    const firstRow = async () => {
      const row = scopeTable(page).getByRole('row').nth(1);
      await expect(row).not.toBeEmpty();
      return ((await row.textContent()) ?? '').trim();
    };

    const draftId = await draftAtScope(page);
    const firstVisit = await firstRow();
    expect(firstVisit).not.toBe('');

    await page.goto(`/tasks/new/${draftId}/scope`);
    await expect(scopeTable(page)).toBeVisible();

    expect(await firstRow()).toBe(firstVisit);
  });

  test('searches by name and keeps the 迁移范围 while the filter moves', async ({ page }) => {
    await draftAtScope(page);
    const table = scopeTable(page);

    await table.getByRole('checkbox').first().check({ force: true });
    await expect(page.getByText('迁移范围共 1 张')).toBeVisible();

    await page.getByRole('searchbox', { name: '按名称搜索源表' }).fill('zzz_no_such_table');
    // A filter that matched nothing says so, rather than claiming there is no data.
    await expect(table).toContainText('没有匹配项');
    // …and it narrows what is on screen, never what was chosen.
    await expect(page.getByText('迁移范围共 1 张')).toBeVisible();

    await page.getByRole('searchbox', { name: '按名称搜索源表' }).fill('order');
    await expect(table).toContainText('order');
    await expect(page.getByText('迁移范围共 1 张')).toBeVisible();
  });

  test('names the two select-all scopes apart, and records an exclusion that can be undone', async ({
    page,
  }) => {
    await draftAtScope(page);
    const table = scopeTable(page);

    // ADR-0015: DBX owns cross-page selection wording. A virtualised selector has no pages,
    // so 「符合当前筛选的全部」 is the only scope on offer here — and it says so.
    await expect(table.getByRole('button', { name: '当前页全选' })).toHaveCount(0);
    await table.getByRole('button', { name: '选中符合当前筛选的全部' }).click();
    await expect(page.getByText('迁移范围共 1200 张')).toBeVisible();

    await table.getByRole('checkbox').first().uncheck({ force: true });
    await expect(page.getByText('迁移范围共 1199 张')).toBeVisible();
    await expect(table).toContainText('已排除 1 张');

    // An exclusion is a reviewable exception: it is listed by name and can be taken back.
    const excluded = page.getByRole('region', { name: '显式排除' });
    await expect(excluded).toContainText('显式排除是可复核的例外');
    await excluded.getByRole('button', { name: /^撤销排除 / }).click();
    await expect(page.getByText('迁移范围共 1200 张')).toBeVisible();
    await expect(excluded).toContainText('还没有显式排除任何表');
  });

  test('shows each table its own current condition, and calls estimates estimates', async ({
    page,
  }) => {
    await draftAtScope(page);
    const table = scopeTable(page);

    await expect(table).toContainText('当前情况');
    await expect(table).toContainText('SUPPORTED');
    // `Source baseline` lists 「estimated row count」 under `_Avoid_`; the screen says which
    // of the two it is showing.
    await expect(page.getByText(/行数与数据量是发现阶段的预估值，不是源基线/)).toBeVisible();
  });

  test('the 迁移范围 survives a browser refresh, exclusions included', async ({ page }) => {
    const draftId = await draftAtScope(page);
    const table = scopeTable(page);

    await table.getByRole('button', { name: '选中符合当前筛选的全部' }).click();
    await table.getByRole('checkbox').first().uncheck({ force: true });
    await expect(page.getByText('迁移范围共 1199 张')).toBeVisible();

    await page.reload();

    await expect(page).toHaveURL(new RegExp(`/tasks/new/${draftId}/scope$`));
    await expect(page.getByText('迁移范围共 1199 张')).toBeVisible();
    // Still an exclusion after the reload, not merely an unticked row.
    await expect(page.getByRole('region', { name: '显式排除' })).toContainText('撤销排除');
  });
});
