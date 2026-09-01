import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Seam ① — 阶段三 单表工作区, and **Gate 4** of the nine journey gates (#30 §15.4).
 *
 * Gate 4 is 「DDL 只读」, so the case that matters is the one proving the interface
 * **refused** an edit. Rendering some DDL and copying it would not distinguish a read-only
 * rendering from an editor nobody happened to type into. ADR-0011 rejects editable DDL
 * outright — arbitrary SQL can diverge from the Source output and bypass structured
 * preflight and mapping rules — so what is asserted here is that there is nothing in the
 * pane to type into, that typing into it changes nothing, and that the only route to a
 * different DDL is the bounded 映射规则 control that regenerates the 表写入契约.
 */

/**
 * A DBA console screen, stated explicitly rather than inherited.
 *
 * 「在一个屏幕内同时看到」 is only a claim if the screen is named: the three-pane workspace
 * is the stated reason the wizard is a full page rather than a wide tearsheet (ADR-0014),
 * and this is the size at which that has to hold. Fixed here so the assertion means the
 * same thing on CI's Linux as on a reviewer's mac (lead decision D9).
 */
test.use({ viewport: { width: 1680, height: 1050 } });

// These journeys walk the whole wizard and then load a production-scale 迁移范围, so they
// are legitimately slow rather than flaky.
test.beforeEach(() => {
  test.slow();
});

const sourceDdl = '源 DDL（MySQL 8.0）';
const targetDdl = '目标 DDL（PostgreSQL 15）';

/**
 * The 迁移草稿 the faulted scenarios seed, already parked at 逐表配置与预检.
 *
 * A scenario only holds while its parameter is on the URL, and client-side navigation
 * drops it — so a stage that is normally walked to cannot be reached under a fault unless
 * the draft is already there when the page loads.
 */
const seededDraftId = 'draft-ready-for-tables';

async function draftAtTables(page: Page): Promise<string> {
  await page.goto('/tasks');
  await page.getByRole('button', { name: '新建迁移草稿' }).click();
  await expect(page).toHaveURL(/\/tasks\/new\/[^/]+\/connections/);
  const draftId = page.url().split('/tasks/new/')[1]?.split('/')[0] ?? '';

  const source = page.getByRole('region', { name: '源端' });
  await source.getByLabel('数据库连接').selectOption({ label: '订单库（生产）' });
  await source.getByLabel('源 MySQL database').selectOption({ label: 'orders' });
  const target = page.getByRole('region', { name: '目标端' });
  await target.getByLabel('数据库连接').selectOption({ label: '分析库（生产）' });
  await target.getByLabel('目标 PostgreSQL schema').fill('orders_migrated');

  await page.getByRole('button', { name: '下一步' }).click();
  await expect(page).toHaveURL(/\/scope/);

  // The whole database into the 迁移范围, so every condition a table can be in is present
  // in the object tree.
  await page
    .getByRole('region', { name: '源表' })
    .getByRole('button', { name: '选中符合当前筛选的全部' })
    .click();
  await expect(page.getByText('迁移范围共 1200 张')).toBeVisible();

  await page.getByRole('button', { name: '下一步' }).click();
  await expect(page).toHaveURL(/\/tables/);
  return draftId;
}

function tree(page: Page): Locator {
  return page.getByRole('region', { name: '对象树' });
}

/** Opens the first table in the 迁移范围 whose 表写入契约 has been generated. */
async function openContractedTable(page: Page): Promise<void> {
  const node = tree(page)
    .getByRole('treeitem')
    .filter({ hasNotText: '尚未生成表写入契约' })
    .first();
  await node.click();
  await expect(page.getByRole('region', { name: targetDdl })).toContainText('CREATE TABLE');
}

test.describe('Gate 4: DDL 只读', () => {
  test('offers nothing to type into, and a keystroke changes nothing', async ({ page }) => {
    await draftAtTables(page);
    await openContractedTable(page);

    for (const name of [sourceDdl, targetDdl]) {
      const pane = page.getByRole('region', { name });
      await expect(pane).toContainText('CREATE TABLE');

      // Nothing in the pane accepts input. This is Gate 4 stated as a shape: a read-only
      // flag can be flipped, an absent editor cannot.
      await expect(pane.locator('textarea')).toHaveCount(0);
      await expect(pane.locator('input')).toHaveCount(0);
      await expect(pane.locator('[contenteditable]')).toHaveCount(0);

      const statement = pane.locator('pre');
      const before = await statement.textContent();
      await statement.click();
      await page.keyboard.type('DROP TABLE order_item;');
      await page.keyboard.press('Enter');
      await page.keyboard.press('Backspace');
      expect(await statement.textContent()).toBe(before);
    }

    // And the interface says why, so the operator does not go looking for the editor.
    await expect(page.getByText(/DDL 是表写入契约的只读完整呈现/)).toBeVisible();
  });

  test('holds the object tree, both DDLs and the findings on one screen', async ({ page }) => {
    // Story 38, and the stated reason the wizard is a full page rather than a wide
    // tearsheet (ADR-0014). If the three panes do not fit, the deviation bought nothing.
    await draftAtTables(page);
    await openContractedTable(page);

    await expect(tree(page)).toBeInViewport();
    await expect(page.getByRole('region', { name: sourceDdl })).toBeInViewport();
    await expect(page.getByRole('region', { name: targetDdl })).toBeInViewport();
    await expect(page.getByRole('region', { name: '发现' })).toBeInViewport();
  });

  test('changes structure only through a bounded 映射规则, and regenerates the DDL', async ({
    page,
  }) => {
    // The other half of Gate 4: the DDL is read-only *because* this is where structure
    // changes. `CONTEXT.md` — a 映射规则 names one source coordinate, one bounded action
    // and its target value, and never contains arbitrary SQL.
    await draftAtTables(page);

    const withExceptions = tree(page)
      .getByRole('treeitem')
      .filter({ hasText: '映射规则' })
      .filter({ hasNotText: '尚未生成表写入契约' })
      .first();
    await withExceptions.click();

    const contract = page.getByRole('region', { name: targetDdl });
    await expect(contract).toContainText('表写入契约 v1');
    const before = (await contract.locator('pre').textContent()) ?? '';
    expect(before).not.toBe('');

    const findings = page.getByRole('region', { name: '发现' });
    const rule = findings.getByRole('combobox').first();
    // A closed list of target values. There is no free-text control anywhere in the pane.
    await expect(findings.locator('textarea')).toHaveCount(0);
    const options = await rule.locator('option').allTextContents();
    expect(options.length).toBeGreaterThan(1);
    await rule.selectOption({ index: 1 });

    // 「修改映射后，表写入契约与 DDL 自动重新生成」 — a new contract version, new DDL, and
    // the rule now attributed to the operator rather than to the platform.
    await expect(contract).toContainText('表写入契约 v2');
    await expect(contract.locator('pre')).not.toHaveText(before);
    await expect(findings).toContainText('USER');
  });

  test('copies the whole statement, not a selection', async ({ page, context }) => {
    // Story 45: the DDL is pasted into a change review, so what leaves the screen has to
    // be the same statement the 结构证明 will later be made against.
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await draftAtTables(page);
    await openContractedTable(page);

    const pane = page.getByRole('region', { name: sourceDdl });
    const shown = (await pane.locator('pre').textContent()) ?? '';
    await pane.getByRole('button', { name: /复制/ }).click();

    // The clipboard, not a confirmation message: what matters is that the whole statement
    // left the screen, character for character.
    await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(shown);
    expect(shown).toContain('CREATE TABLE');
    expect(shown).toContain('PRIMARY KEY');
  });

  test('renders no DDL at all while a 映射例外 is still undecided', async ({ page }) => {
    // ADR-0011: the 表写入契约 is the complete write intent, and the per-column zero-date
    // relaxation is *approved* — so DBX will not choose it. Half a contract would be
    // rendered as half a DDL, which is the one thing a read-only rendering must never do.
    await draftAtTables(page);

    await tree(page)
      .getByRole('treeitem')
      .filter({ hasText: '尚未生成表写入契约' })
      .first()
      .click();

    const contract = page.getByRole('region', { name: targetDdl });
    await expect(contract).toContainText('尚未生成表写入契约');
    await expect(contract.locator('pre')).toHaveCount(0);
    await expect(page.getByRole('region', { name: '发现' })).toContainText('DBX 不会替你选');

    // And the stage says so rather than letting the draft walk on to 执行确认.
    const url = page.url();
    await expect(page.getByText(/没有生成表写入契约/)).toBeVisible();
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(url);
  });
});

test.describe('阶段三的三态', () => {
  test('says it is reading rather than showing a blank', async ({ page }) => {
    await page.goto(`/tasks/new/${seededDraftId}/tables?scenario=stage-tables-loading`);
    await expect(page.getByText('正在读取逐表配置与预检。')).toBeVisible();
  });

  test('offers a retry when the read fails', async ({ page }) => {
    await page.goto(`/tasks/new/${seededDraftId}/tables?scenario=stage-tables-error`);
    await expect(page.getByText('逐表配置读取失败')).toBeVisible();
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
    // A stage that cannot read its own evidence does not quietly let the draft past.
    await expect(page.getByText(/还没有读到逐表配置与预检/)).toBeVisible();
  });

  test('says a table needs no per-field review when it has no exceptions', async ({ page }) => {
    // Story 36: automatic mapping is the default, so the common table is one where there
    // is genuinely nothing to do — and the interface says that rather than showing an
    // empty grid.
    await draftAtTables(page);
    await tree(page).getByRole('treeitem').filter({ hasNotText: '映射规则' }).first().click();
    await expect(page.getByRole('region', { name: '发现' })).toContainText('字段均使用自动映射');
  });
});
