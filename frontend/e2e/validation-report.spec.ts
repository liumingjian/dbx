import { expect, test, type Locator, type Page } from '@playwright/test';
import { expectFreeOfExecutionPlatform, visibleText } from './gate7';

/**
 * Seam ① — 校验报告, and **Gate 8** of the nine journey gates (#30 §15.4, #40).
 *
 * Gate 8 is 「技术校验结论与接受风险在视觉与语义上都可区分」, and this file is where that
 * claim is actually checked against a browser. The property is a negative and an
 * arrangement at once: recording a 校验处置 must leave the technical conclusion visibly
 * unchanged, and the two must never be readable as one thing. `CONTEXT.md` says why in one
 * sentence — 「Accepting risk may close the workflow but never changes the technical
 * validation result to passed」 — and lists 「Manual pass, overridden result」 under
 * 校验处置's `_Avoid_`.
 *
 * The other subjects here are the three separations the report exists to keep:
 *
 *  - 通过 / 未通过 / 无法判定 as three conclusions, not two and a caveat;
 *  - technical results against 预检排除项 — 「没迁」 and 「迁了但没过」;
 *  - 不适用 against 未执行 against a real failure.
 *
 * Everything asserted is domain language: the glossary's own words and the enum literals.
 * Never a Carbon class, never a DOM shape.
 */

test.use({ viewport: { width: 1680, height: 1050 } });

test.beforeEach(() => {
  test.slow();
});

const runId = 'run-monitored';

/** See `e2e/execution-confirmation.spec.ts`: the dev server compiles this graph cold. */
const FIRST_PAINT_MS = 60_000;

function panel(page: Page, name: string): Locator {
  return page.getByRole('region', { name, exact: true });
}

function reportTable(page: Page): Locator {
  return panel(page, '逐表校验结论');
}

async function openReport(page: Page, scenario: string): Promise<void> {
  await page.goto(`/runs/${runId}/validation?scenario=${scenario}`);
  await expect(page.getByRole('heading', { name: '校验报告' })).toBeVisible({
    timeout: FIRST_PAINT_MS,
  });
  await expect(reportTable(page)).toBeVisible({ timeout: FIRST_PAINT_MS });
}

/** The row of the first table whose 校验执行 did not pass. */
function unresolvedRow(page: Page): Locator {
  return reportTable(page).getByRole('row').filter({ hasText: '校验处置' }).first();
}

test.describe(
  'Gate 8：技术结论与校验处置在视觉与语义上都可区分',
  { tag: ['@gate', '@gate-8'] },
  () => {
    test(
      'a recorded 校验处置 leaves the technical conclusion exactly where it was',
      { tag: '@blocked-8' },
      async ({ page }) => {
        // 「校验无法判定」, straight from the URL: nothing has to be performed first.
        await openReport(page, 'inconclusive-validation');
        await page.getByRole('tab', { name: '只看未通过' }).click();

        const table = reportTable(page);
        await expect(table).toContainText('无法判定');
        const conclusionsBefore = await table.getByText('无法判定', { exact: true }).count();
        expect(conclusionsBefore).toBeGreaterThan(0);

        // The decision is an action of its own, named as itself and offered per table.
        await table
          .getByRole('button', { name: /记录 .+ 的校验处置/ })
          .first()
          .click();
        const dialog = page.getByRole('dialog', { name: '记录校验处置' });
        await expect(dialog).toContainText('不会改变这个技术结论');

        // 「An operator's **audited** decision」: neither field is optional.
        const confirm = dialog.getByRole('button', { name: '记录校验处置' });
        await expect(confirm).toBeDisabled();
        await dialog.getByLabel('责任人').fill('li.na');
        await dialog.getByLabel('理由').fill('差异已在变更评审 CHG-2026-0901-2 中逐行复核。');
        await expect(confirm).toBeEnabled();
        await confirm.click();
        await expect(dialog).toHaveCount(0);

        // Semantically distinguishable: the decision is recorded in its own column and its own
        // section, carrying the result it did not change.
        await expect(table).toContainText('已记录校验处置');
        await expect(table).toContainText('li.na');
        const disposition = panel(page, '校验处置');
        await expect(disposition).toContainText('责任人 li.na');
        await expect(disposition).toContainText('技术结论仍然是无法判定');
        await expect(disposition).toContainText('逐行复核');

        // And the technical conclusion is still exactly what the 校验执行 concluded.
        await expect(table).toContainText('无法判定');
        expect(await table.getByText('无法判定', { exact: true }).count()).toBe(conclusionsBefore);
        // The one thing the whole audit chain rests on: no laundering into a pass. `exact`,
        // because 未通过 contains 通过 — the row may hold neither, and must hold no 通过.
        await expect(unresolvedRow(page).getByText('通过', { exact: true })).toHaveCount(0);
      },
    );

    test('a disposed 未通过 is still 未通过, and the run does not read as succeeded', async ({
      page,
    }) => {
      await openReport(page, 'accepted-risk');
      await page.getByRole('tab', { name: '只看已记录校验处置' }).click();

      const table = reportTable(page);

      // **Separate columns**, named as the two different things they are. A whole-table
      // `toContainText` would be satisfied by a single merged cell reading
      // 「FAIL（已接受风险）」 — which is the exact failure Gate 8 exists to prevent, so the
      // columns are located by name and asserted to be two.
      const headers = await table.getByRole('columnheader').allInnerTexts();
      const conclusionColumn = headers.findIndex((header) => header.trim() === '校验执行技术结论');
      const dispositionColumn = headers.findIndex((header) => header.trim() === '校验处置');
      const outcomeColumn = headers.findIndex((header) => header.trim() === '表迁移单元技术结果');
      expect(conclusionColumn, '校验执行技术结论 是一列').toBeGreaterThanOrEqual(0);
      expect(dispositionColumn, '校验处置 是它自己的一列').toBeGreaterThanOrEqual(0);
      expect(outcomeColumn, '表迁移单元技术结果 是第三列').toBeGreaterThanOrEqual(0);
      expect(new Set([conclusionColumn, dispositionColumn, outcomeColumn]).size).toBe(3);

      // The row where the decision was actually taken, and the two cells in it.
      const disposed = table.getByRole('row').filter({ hasText: '已记录校验处置' }).first();
      const cells = disposed.getByRole('cell');
      const conclusionCell = cells.nth(conclusionColumn);
      const dispositionCell = cells.nth(dispositionColumn);

      // Semantically distinguishable: a screen reader reaching the conclusion cell is told
      // the conclusion and nothing else — not 「未通过（已接受风险）」, which would be the
      // decision qualifying the result.
      await expect(conclusionCell).toHaveAccessibleName('未通过');
      // …and the disposition cell never states a technical conclusion of its own.
      await expect(dispositionCell).not.toHaveText(
        /^(PASS|FAIL|INCONCLUSIVE|通过|未通过|无法判定)$/,
      );
      await expect(dispositionCell).toContainText('已记录校验处置');

      // Visually distinguishable, asserted as the thing ADR-0014 actually requires rather
      // than as a class name: the conclusion carries a symbol, the disposition does not.
      // A judgement and a decision are drawn in two different vocabularies.
      expect(await conclusionCell.locator('svg').count()).toBeGreaterThan(0);
      expect(await dispositionCell.locator('svg').count()).toBe(0);

      // And the third column is a third fact. 完成，已接受风险 beside a 未通过 is the report
      // working, not a contradiction — and neither of them is 迁移完成.
      await expect(cells.nth(outcomeColumn)).toHaveText('完成，已接受风险');
      await expect(table).not.toContainText('迁移完成');

      await expect(panel(page, '校验处置')).toContainText('技术结论仍然是未通过');
      // The count, too: a disposed 未通过 is counted as one and never quietly moved.
      await expect(panel(page, '技术结论分布')).toContainText('未通过');
    });

    test('states the rule in words, beside the decision it governs', async ({ page }) => {
      await openReport(page, 'accepted-risk');
      await expect(page.getByText('不会把技术结论改写为通过').first()).toBeVisible();
    });
  },
);

test.describe('三个结论、预检排除项、以及三种「没跑」', () => {
  test('states the run 选定范围 the conclusions cover', async ({ page }) => {
    await openReport(page, 'inconclusive-validation');
    const scope = panel(page, '本次迁移运行的选定范围');
    await expect(scope).toContainText('选定表数');
    await expect(scope).toContainText('排除表数');
    await expect(scope).toContainText('源基线捕获于');
    await expect(scope).toContainText('下面的技术结论只覆盖这些选定的表。');
  });

  test('keeps 预检排除项 out of the technical results', async ({ page }) => {
    await openReport(page, 'inconclusive-validation');
    const exclusions = panel(page, '预检排除项');
    await expect(exclusions).toContainText('它们没有迁移，也没有校验执行');
    // Each exclusion says which kind of absence it is.
    await expect(exclusions).toContainText('操作员显式排除');
    await expect(exclusions).toContainText('预检判定不可迁移');
    await expect(exclusions).toContainText('预检无法判定');
    await expect(exclusions).toContainText('只有结论为可迁移的预检可以继续');
  });

  test('separates 不适用, 未执行 and a real failure', async ({ page }) => {
    await openReport(page, 'accepted-risk');
    const items = panel(page, '技术结论分布');
    await expect(items).toContainText('不适用');
    await expect(items).toContainText('未执行');
    await expect(items).toContainText('都不是失败');
    // All three judgements are stated, including one nobody reached. Anchored, because
    // 未通过 contains 通过 and an unanchored match would let one stand for the other.
    for (const conclusion of ['通过', '未通过', '无法判定']) {
      await expect(items.getByText(new RegExp(`^${conclusion} \\d+$`)).first()).toBeVisible();
    }
  });

  test('says a table whose write failed never ran a 校验执行', async ({ page }) => {
    await openReport(page, 'partial-table-failure');
    const table = reportTable(page);
    await expect(table).toContainText('没有校验执行');
    await expect(table).toContainText('迁移失败');
  });
});

test.describe('进行中的校验不假装有结论', () => {
  test('says the 校验执行 have not finished, instead of a total verdict', async ({ page }) => {
    await openReport(page, 'default');
    const notice = panel(page, '校验尚未跑完');
    await expect(notice).toContainText('不给出总体结论');
    await expect(panel(page, '校验执行已全部结束')).toHaveCount(0);
  });
});

test.describe('报告是可以带走的东西', () => {
  test('a failing table links straight to its evidence drawer', async ({ page }) => {
    await openReport(page, 'partial-table-failure');
    await reportTable(page).getByRole('link', { name: '打开证据' }).first().click();

    // #39's route, over 运行监控 — the address a colleague can be sent.
    await expect(page.getByRole('dialog', { name: '表迁移单元证据' })).toBeVisible({
      timeout: FIRST_PAINT_MS,
    });
    await expect(page).toHaveURL(new RegExp(`/runs/${runId}/tables/${runId}-unit-\\d+`));
    await expect(page).toHaveURL(/scenario=partial-table-failure/);
  });

  test('exports the report as a file that keeps the separations', async ({ page }) => {
    await openReport(page, 'accepted-risk');

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: '导出报告' }).click();
    const file = await download;
    const stream = await file.createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of stream) {
      chunks.push(chunk as Buffer);
    }
    const text = Buffer.concat(chunks).toString('utf8');

    expect(file.suggestedFilename()).toContain(runId);
    expect(text).toContain('校验报告');
    expect(text).toContain('本次迁移运行的选定范围');
    expect(text).toContain('校验执行技术结论 未通过');
    expect(text).toContain('校验处置 已记录校验处置');
    expect(text).toContain('技术结论仍然是未通过');
    expect(text).toContain('预检排除项');
    // Gate 7 binds what leaves the product as well as what is on screen. The export prints
    // `outcomeLabel` and each item's `detail` verbatim, so a platform literal that reached
    // either would travel straight into a change review.
    expectFreeOfExecutionPlatform(text, '导出的校验报告');
  });

  test('copies the report and says so', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await openReport(page, 'accepted-risk');
    await page.getByRole('button', { name: '复制报告' }).click();
    await expect(page.getByText('报告已复制到剪贴板。')).toBeVisible();

    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain('校验执行技术结论 未通过');
    expect(copied).toContain('校验处置 已记录校验处置');
    expectFreeOfExecutionPlatform(copied, '复制到剪贴板的校验报告');
  });
});

test.describe('报告有自己的 loading、error 与 not-found 态', () => {
  test('says the read is running rather than showing a blank page', async ({ page }) => {
    await page.goto(`/runs/${runId}/validation?scenario=loading`);
    await expect(page.getByText('正在读取校验报告。')).toBeVisible({ timeout: FIRST_PAINT_MS });
  });

  test('offers a retry when the read failed', async ({ page }) => {
    await page.goto(`/runs/${runId}/validation?scenario=error`);
    await expect(page.getByText('校验报告读取失败')).toBeVisible({ timeout: FIRST_PAINT_MS });
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
  });

  test('says a run it does not have is missing rather than broken', async ({ page }) => {
    await page.goto('/runs/no-such-run/validation?scenario=default');
    await expect(page.getByText('找不到这次迁移运行')).toBeVisible({ timeout: FIRST_PAINT_MS });
    await expect(page.getByRole('button', { name: '重试' })).toHaveCount(0);
  });
});

test.describe('Gate 7 binds this surface too', () => {
  test('no 箱, 连接器 or topic reaches the 校验报告', async ({ page }) => {
    await openReport(page, 'accepted-risk');
    expectFreeOfExecutionPlatform(await visibleText(page), '校验报告');
  });
});
