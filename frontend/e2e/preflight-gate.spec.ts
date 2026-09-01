import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Seam ① — 预检证据与阻断门禁, and **Gates 2 and 3** of the nine journey gates (#30 §15.4).
 *
 * Both gates are stated as refusals, because a happy path proves nothing about a
 * constraint. Gate 2 is 「结论为不可迁移或无法判定的预检不能被批准」, so the case
 * that matters is the one where the wizard **would not move** and there was nothing on
 * screen to click that would have made it. Gate 3 is 「映射变更使契约失效并重跑受影响的
 * 证据」, so the case that matters is the one where the **old conclusion left the screen**
 * — a superseded judgement still on display is exactly how a stale answer gets approved.
 *
 * What is asserted throughout is domain language: the conclusion literals, ADR-0003's own
 * sentences, and the names of the three exits. Never a Carbon class, never a DOM shape.
 */

/** The three-pane workspace at the size the stage is designed for (ADR-0014). */
test.use({ viewport: { width: 1680, height: 1050 } });

// Reruns take mock time on purpose — a 预检 that settled inside one request could never be
// caught 「进行中」 — so these journeys wait for real seconds rather than being flaky.
test.beforeEach(() => {
  test.slow();
});

/**
 * The 迁移草稿 the blocked scenario seeds, already parked at 逐表配置与预检.
 *
 * A review link has to *land* on the blocked stage. Walking the wizard to reach it would
 * take several client-side navigations, and the whole point of Gate 2 is what a DBA cannot
 * do once they are there.
 */
const seededDraftId = 'draft-ready-for-tables';
const scenario = 'blocked-preflight';

function tree(page: Page): Locator {
  return page.getByRole('region', { name: '对象树' });
}

/** 预检, exactly — 「逐表配置与预检」 is a region too, and a substring match would take it. */
function preflight(page: Page): Locator {
  return page.getByRole('region', { name: '预检', exact: true });
}

function exceptions(page: Page): Locator {
  return page.getByRole('region', { name: '映射例外' });
}

async function openStage(page: Page): Promise<void> {
  await page.goto(`/tasks/new/${seededDraftId}/tables?scenario=${scenario}`);
  await expect(tree(page)).toBeVisible();
}

/**
 * The 源表 a tree item is about.
 *
 * The identifier comes first in the label and the conclusion literal directly after it,
 * so the two are told apart by case: source identifiers are lower case and every
 * conclusion is upper case.
 */
async function tableNameOf(node: Locator): Promise<string> {
  const label = (await node.textContent()) ?? '';
  const name = /^[a-z0-9_]+/.exec(label)?.[0] ?? '';
  expect(name, '每个对象树节点都以源表名开头').not.toBe('');
  return name;
}

/**
 * The tree item for one named 源表.
 *
 * Anchored on the name *and* the conclusion that follows it, so `order_item_2` can never
 * stand in for `order_item` — which would let an assertion about one table pass on
 * another's evidence.
 */
function nodeFor(page: Page, name: string): Locator {
  return tree(page)
    .getByRole('treeitem')
    .filter({ hasText: new RegExp(`^${name}(可迁移|不可迁移|无法判定|执行中)`) })
    .first();
}

/** Opens one table and waits for its 预检 conclusion to be on screen. */
async function open(page: Page, node: Locator): Promise<string> {
  const name = await tableNameOf(node);
  await node.getByRole('button').first().click();
  await expect(preflight(page)).toBeVisible();
  return name;
}

/**
 * The table nothing could be concluded about.
 *
 * 无法判定 is the conclusion #30 says the stage exists to keep separate from a
 * warning, so it is the one the blocking case is written against.
 */
function inconclusiveTable(page: Page): Locator {
  return tree(page).getByRole('treeitem').filter({ hasText: '无法判定' }).first();
}

test.describe('Gate 2：结论为不可迁移或无法判定的预检不能被批准', () => {
  test('holds the stage shut, and offers nothing that would confirm it away', async ({ page }) => {
    await openStage(page);
    const name = await open(page, inconclusiveTable(page));

    // 「无法判定」 has its own form. It is not a warning, and the interface never softens it
    // into one — that misreading is the whole reason this stage is written down.
    await expect(preflight(page)).toContainText('无法判定');
    await expect(preflight(page)).toContainText('无法确认是否可迁移');
    await expect(preflight(page)).toContainText('不能忽略此检查继续迁移');
    await expect(preflight(page)).not.toContainText('警告');
    await expect(preflight(page)).not.toContainText('风险');

    // Everything pressable inside the 预检 is one of ADR-0003's three exits. There is no
    // acknowledgement, no dismissal and no fourth way out — and the pane says so.
    const actions = await preflight(page).getByRole('button').allInnerTexts();
    expect(actions.length).toBeGreaterThan(0);
    for (const action of actions) {
      expect(action).toMatch(/重新预检|显式排除该表|裁剪字段|撤销裁剪/);
    }
    await expect(preflight(page).getByRole('checkbox')).toHaveCount(0);
    await expect(preflight(page)).toContainText('没有第四条出路');

    // And the stage refuses to advance, saying which table and which conclusion.
    const url = page.url();
    await expect(page.getByText(/只有结论为可迁移的预检可以继续/)).toBeVisible();
    await page.getByRole('button', { name: '下一步' }).click();
    await expect(page).toHaveURL(url);

    // A typed address does not get past it either — every stage is deep-linkable, so the
    // gate has to hold against a URL and not only against a button. The `?scenario=`
    // survives the redirect, which is what makes the blocked world reviewable at all.
    await page.goto(`/tasks/new/${seededDraftId}/confirm?scenario=${scenario}`);
    await expect(page).toHaveURL(new RegExp(`/tables\\?scenario=${scenario}$`));
    await expect(page.getByText(/只有结论为可迁移的预检可以继续/)).toBeVisible();
    expect(name).not.toBe('');
  });

  test('says everything a table is at once, not the most dramatic half', async ({ page }) => {
    // Story 47: a 大记录表 that also carries 映射例外 is both, and a screen that showed one
    // of the two would send the operator to fix the wrong thing.
    await openStage(page);
    const node = tree(page)
      .getByRole('treeitem')
      .filter({ hasText: '不可迁移' })
      .filter({ hasText: '大记录表' })
      .filter({ hasText: '映射规则' })
      .first();
    await open(page, node);

    await expect(preflight(page)).toContainText('不可迁移');
    await expect(preflight(page)).toContainText('大记录表');
    await expect(preflight(page)).toContainText(/最大单值 [\d,]+ 字节/);
    await expect(preflight(page)).toContainText('大记录包络上限为 20 MiB（20,971,520 字节）');
    // The findings are listed as evidence, each with its stable code.
    await expect(preflight(page)).toContainText('阻断');
    // …and the mapping exceptions are still there beside them, in the same pane.
    await expect(exceptions(page).getByRole('combobox').first()).toBeVisible();
  });

  test('shows a rerunning 预检 as running rather than as a frozen screen', async ({ page }) => {
    await openStage(page);
    await open(page, inconclusiveTable(page));
    await expect(preflight(page)).toContainText(/评估于/);

    // Exit one: the source was fixed outside DBX, so read the facts again.
    await preflight(page).getByRole('button', { name: '重新预检' }).click();

    await expect(preflight(page)).toContainText('预检进行中');
    await expect(preflight(page)).toContainText('界面没有卡死');
    await expect(preflight(page)).not.toContainText('评估于');
    // While it runs the stage is blocked by the run itself, not by a leftover conclusion.
    await expect(page.getByText(/预检正在进行/)).toBeVisible();

    // And a rerun reports what it finds. Nothing about asking again relaxes a scan that
    // still cannot complete — ADR-0003: it 「cannot be overridden into a runnable table」.
    await expect(preflight(page)).toContainText(/评估于/, { timeout: 20_000 });
    await expect(preflight(page)).toContainText('无法判定');
    await expect(preflight(page)).toContainText('无法确认是否可迁移');
  });
});

test.describe('面对阻断的三条出路', () => {
  test('cuts the offending column and reruns the 预检 against what is left', async ({ page }) => {
    // Exit two. ADR-0003: 「Excluding one field does not waive the row check: DBX reruns
    // preflight against the approved selected columns.」
    await openStage(page);
    const node = tree(page)
      .getByRole('treeitem')
      .filter({ hasText: '不可迁移' })
      .filter({ hasText: '大记录表' })
      .first();
    const name = await open(page, node);

    await expect(preflight(page)).toContainText('超过 DBX v1 的 20 MiB（20,971,520 字节）上限');
    const cut = preflight(page)
      .getByRole('button', { name: /^裁剪字段 / })
      .first();
    const column = ((await cut.innerText()) ?? '').replace('裁剪字段 ', '').trim();
    expect(column).not.toBe('');
    await cut.click();

    // The conclusion goes away while the rescan runs, and comes back as a new one.
    await expect(preflight(page)).toContainText('预检进行中');
    await expect(preflight(page)).toContainText(/评估于/, { timeout: 20_000 });
    // `exact`, because 不可迁移 contains 可迁移: a substring match here would let the
    // opposite conclusion satisfy the assertion.
    await expect(preflight(page).getByText('可迁移', { exact: true })).toBeVisible();
    await expect(preflight(page)).not.toContainText('超过 DBX v1 的 20 MiB');

    // The cut is a reviewable decision rather than a disappearance: the column is still
    // listed, said to be 已裁剪, and can be put back.
    await expect(preflight(page)).toContainText('已裁剪字段');
    await expect(preflight(page).getByRole('button', { name: `撤销裁剪 ${column}` })).toBeVisible();
    await expect(nodeFor(page, name)).toContainText('已裁剪 1 个字段');
  });

  test('takes the table out of the 迁移范围 when it is explicitly excluded', async ({ page }) => {
    // Exit three. 「显式排除是可复核的例外」: the table stops being something the migration
    // has to answer for, rather than being quietly left unticked.
    await openStage(page);
    const name = await open(page, inconclusiveTable(page));
    await expect(nodeFor(page, name)).toBeVisible();

    await preflight(page).getByRole('button', { name: '显式排除该表' }).click();

    // It is gone from the 迁移范围, and searching for it by name finds nothing at all.
    await expect(nodeFor(page, name)).toHaveCount(0);
    await tree(page).getByRole('searchbox').fill(name);
    await expect(tree(page).getByRole('treeitem')).toHaveCount(0);
  });
});

test.describe('Gate 3：映射变更使契约失效并重跑受影响的证据', () => {
  test('takes the old conclusion off the screen and reaches a new one', async ({ page }) => {
    await openStage(page);

    // A table whose 表写入契约 is still missing because DBX will not choose the zero-date
    // relaxation on the operator's behalf, and whose 预检 has so far concluded 可迁移.
    const node = tree(page)
      .getByRole('treeitem')
      .filter({ hasText: '尚未生成表写入契约' })
      .filter({ hasNotText: '不可迁移' })
      .filter({ hasNotText: '无法判定' })
      .first();
    const name = await open(page, node);

    const conclusion = preflight(page).getByText('可迁移', { exact: true });
    await expect(conclusion).toBeVisible();
    await expect(preflight(page)).toContainText(/评估于/);

    // The mapping decision DBX refuses to make: keeping `NOT NULL` means the source's zero
    // dates would be rejected at the target, which is a fact about *this* table's data.
    await page
      .getByRole('combobox', { name: `为 deleted_at 选择映射规则` })
      .selectOption('NOT NULL');

    // Gate 3, stated as a disappearance: the conclusion reached against the previous
    // mapping is off the screen before anything else happens.
    await expect(preflight(page)).toContainText('预检进行中');
    await expect(conclusion).toHaveCount(0);
    await expect(preflight(page)).not.toContainText('评估于');
    await expect(nodeFor(page, name)).toContainText('执行中');

    // The contract was invalidated and reassembled from the new rule at the same time.
    await expect(page.getByRole('region', { name: '目标 DDL（PostgreSQL 15）' })).toContainText(
      '表写入契约 v2',
    );

    // And the rerun reaches a different conclusion, which is the point: the evidence was
    // re-established rather than relabelled.
    await expect(preflight(page)).toContainText(/评估于/, { timeout: 20_000 });
    await expect(preflight(page).getByText('不可迁移', { exact: true })).toBeVisible();
    await expect(preflight(page)).toContainText('零日期值在目标端会被拒绝');
    await expect(preflight(page)).toContainText('不能忽略此结论继续迁移');
  });
});
