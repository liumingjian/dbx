import { expect, test, type Locator, type Page } from '@playwright/test';

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
 *  - `PASS` / `FAIL` / `INCONCLUSIVE` as three conclusions, not two and a caveat;
 *  - technical results against 预检排除项 — 「没迁」 and 「迁了但没过」;
 *  - `NOT_APPLICABLE` against `NOT_RUN` against a real failure.
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

test.describe('Gate 8：技术结论与校验处置在视觉与语义上都可区分', () => {
  test('a recorded 校验处置 leaves the technical conclusion exactly where it was', async ({
    page,
  }) => {
    // 「校验 INCONCLUSIVE」, straight from the URL: nothing has to be performed first.
    await openReport(page, 'inconclusive-validation');
    await page.getByRole('tab', { name: '只看未通过' }).click();

    const table = reportTable(page);
    await expect(table).toContainText('INCONCLUSIVE');
    const conclusionsBefore = await table.getByText('INCONCLUSIVE').count();
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
    await expect(disposition).toContainText('技术结论仍然是 INCONCLUSIVE');
    await expect(disposition).toContainText('逐行复核');

    // And the technical conclusion is still exactly what the 校验执行 concluded.
    await expect(table).toContainText('INCONCLUSIVE');
    expect(await table.getByText('INCONCLUSIVE').count()).toBe(conclusionsBefore);
    // The one thing the whole audit chain rests on: no laundering into a pass.
    await expect(unresolvedRow(page)).not.toContainText('PASS');
  });

  test('a disposed FAIL is still a FAIL, and the run does not read as succeeded', async ({
    page,
  }) => {
    await openReport(page, 'accepted-risk');
    await page.getByRole('tab', { name: '只看已记录校验处置' }).click();

    const table = reportTable(page);
    // The three columns are three separate facts: a technical conclusion, a decision, and
    // a workflow outcome. `COMPLETED_WITH_ACCEPTED_RISK` beside a `FAIL` is the report
    // working, not a contradiction.
    await expect(table).toContainText('FAIL');
    await expect(table).toContainText('已记录校验处置');
    await expect(table).toContainText('COMPLETED_WITH_ACCEPTED_RISK');
    await expect(table).not.toContainText('SUCCEEDED');

    await expect(panel(page, '校验处置')).toContainText('技术结论仍然是 FAIL');
    // The count, too: a disposed FAIL is counted as a FAIL and never quietly moved.
    await expect(panel(page, '技术结论分布')).toContainText('FAIL');
  });

  test('states the rule in words, beside the decision it governs', async ({ page }) => {
    await openReport(page, 'accepted-risk');
    await expect(page.getByText('不会把技术结论改写为 PASS').first()).toBeVisible();
  });
});

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
    await expect(exclusions).toContainText('OPERATOR_EXCLUDED');
    await expect(exclusions).toContainText('PREFLIGHT_UNSUPPORTED');
    await expect(exclusions).toContainText('PREFLIGHT_INCONCLUSIVE');
    await expect(exclusions).toContainText('只有 SUPPORTED 的预检可以继续');
  });

  test('separates NOT_APPLICABLE, NOT_RUN and a real failure', async ({ page }) => {
    await openReport(page, 'accepted-risk');
    const items = panel(page, '技术结论分布');
    await expect(items).toContainText('NOT_APPLICABLE');
    await expect(items).toContainText('NOT_RUN');
    await expect(items).toContainText('都不是失败');
    // All three judgements are stated, including one nobody reached.
    for (const conclusion of ['PASS', 'FAIL', 'INCONCLUSIVE']) {
      await expect(items).toContainText(conclusion);
    }
  });

  test('says a table whose write failed never ran a 校验执行', async ({ page }) => {
    await openReport(page, 'partial-table-failure');
    const table = reportTable(page);
    await expect(table).toContainText('没有校验执行');
    await expect(table).toContainText('FAILED');
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
    expect(text).toContain('校验执行技术结论 FAIL');
    expect(text).toContain('校验处置 已记录校验处置');
    expect(text).toContain('技术结论仍然是 FAIL');
    expect(text).toContain('预检排除项');
  });

  test('copies the report and says so', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await openReport(page, 'accepted-risk');
    await page.getByRole('button', { name: '复制报告' }).click();
    await expect(page.getByText('报告已复制到剪贴板。')).toBeVisible();

    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain('校验执行技术结论 FAIL');
    expect(copied).toContain('校验处置 已记录校验处置');
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
    const text = (await page.locator('body').innerText()).toLowerCase();
    for (const forbidden of ['箱', '连接器', 'topic', 'box', 'connector', 'kafka']) {
      expect(text, `「${forbidden}」 must not reach the interface`).not.toContain(forbidden);
    }
  });
});
