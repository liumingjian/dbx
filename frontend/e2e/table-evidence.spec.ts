import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Seam ① — 单表证据抽屉 (#39).
 *
 * The property under test is **URL ownership**, and it is the reason this drawer exists in
 * this form: a DBA who has found the table that failed pastes an address into a ticket, and
 * a colleague opening it lands on the same screen. So every case here is about the address
 * bar and the screen agreeing — opening changes the URL, the URL restores the drawer over
 * 运行监控, a reload keeps it, closing returns to the run, and back and forward retrace
 * exactly those steps. The prototype's `?variant=A`, a drawer whose state lived in a
 * component, is what this replaces.
 *
 * Gate 7 binds this surface as well: it sits on the monitoring journey, so 箱 / 连接器 /
 * topic must not reach it either, and 根因域's execution-platform values are presented as
 * 迁移平台 while the specific domain stays in the evidence for support.
 */

test.use({ viewport: { width: 1680, height: 1050 } });

test.beforeEach(() => {
  test.slow();
});

const runId = 'run-monitored';

/** See `e2e/execution-confirmation.spec.ts`: the dev server compiles this graph cold. */
const FIRST_PAINT_MS = 60_000;

function matrix(page: Page): Locator {
  return page.getByRole('region', { name: '进度矩阵', exact: true });
}

function drawer(page: Page): Locator {
  return page.getByRole('dialog', { name: '表迁移单元证据' });
}

async function openMonitor(page: Page, scenario: string): Promise<void> {
  await page.goto(`/runs/${runId}?scenario=${scenario}`);
  await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible({
    timeout: FIRST_PAINT_MS,
  });
  await expect(matrix(page)).toBeVisible({ timeout: FIRST_PAINT_MS });
}

/** Opens the evidence of the first table that failed. */
async function openFailedTableEvidence(page: Page): Promise<void> {
  await openMonitor(page, 'partial-table-failure');
  await page.getByRole('tab', { name: '只看失败' }).click();
  await matrix(page).getByText('FAILED').first().click();
  await expect(drawer(page)).toBeVisible();
}

test.describe('一张表的证据有自己的 URL', () => {
  test('opening the drawer changes the address, carrying the scenario with it', async ({
    page,
  }) => {
    await openFailedTableEvidence(page);

    // The address names the run and the 表迁移单元 — this is what gets pasted into a ticket.
    await expect(page).toHaveURL(new RegExp(`/runs/${runId}/tables/${runId}-unit-\\d+`));
    // Built through `paths`, so the world being reviewed travels with the link (D25).
    await expect(page).toHaveURL(/scenario=partial-table-failure/);
  });

  test('a reload restores the drawer over 运行监控', async ({ page }) => {
    await openFailedTableEvidence(page);
    const url = page.url();

    await page.reload();

    await expect(drawer(page)).toBeVisible({ timeout: FIRST_PAINT_MS });
    // Over the run, not instead of it: the colleague lands where the operator was.
    await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible();
    expect(page.url()).toBe(url);
  });

  test('visiting the address directly lands on the same screen', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-2?scenario=partial-table-failure`);

    await expect(drawer(page)).toBeVisible({ timeout: FIRST_PAINT_MS });
    await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible();
    await expect(matrix(page)).toBeVisible();
  });
});

test.describe('关闭与浏览器前进/后退', () => {
  test('closing returns to the run URL, and back and forward retrace it', async ({ page }) => {
    await openFailedTableEvidence(page);

    await drawer(page).getByRole('button', { name: '关闭' }).click();
    await expect(drawer(page)).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/runs/${runId}(\\?|$)`));
    await expect(matrix(page)).toBeVisible();

    // Back returns to the evidence that was being read — the drawer is a place, not a mode.
    await page.goBack();
    await expect(drawer(page)).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`/runs/${runId}/tables/`));

    await page.goForward();
    await expect(drawer(page)).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/runs/${runId}(\\?|$)`));
  });

  test('Escape closes it the same way the control does', async ({ page }) => {
    await openFailedTableEvidence(page);

    await page.keyboard.press('Escape');
    await expect(drawer(page)).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/runs/${runId}(\\?|$)`));
  });
});

test.describe('证据让 DBA 判断是源问题还是目标问题', () => {
  test('states what happened, what is affected, one action, and the 根因域', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-2?scenario=partial-table-failure`);
    const panel = drawer(page);
    await expect(panel).toBeVisible({ timeout: FIRST_PAINT_MS });

    // 诊断 and 错误事件 are separate sections, because they are separate things: an
    // observed fact, and a versioned interpretation of it (ADR-0005).
    const diagnosis = panel.getByRole('region', { name: '诊断', exact: true });
    await expect(diagnosis).toContainText('根因域 SOURCE_DATABASE');
    await expect(diagnosis).toContainText('源数据库');
    await expect(diagnosis).toContainText('影响');
    await expect(diagnosis).toContainText('建议动作');
    // A stable code and the catalog version it was reached under: a historical diagnosis
    // keeps its version, so support can reproduce it.
    await expect(diagnosis).toContainText('诊断代码 DBX-SOURCE-PERMISSION-DENIED');
    await expect(diagnosis).toContainText(/诊断规则版本 [\d.]+/);

    const occurrences = panel.getByRole('region', { name: '错误事件', exact: true });
    await expect(occurrences).toContainText(/首次观测 \d{4}-\d{2}-\d{2}/);
    await expect(occurrences).toContainText(/共观测 \d+ 次/);
    await expect(occurrences).toContainText('证据引用');
  });

  test('a target-side refusal reads as a target problem', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-6?scenario=partial-table-failure`);
    const panel = drawer(page);
    await expect(panel).toBeVisible({ timeout: FIRST_PAINT_MS });

    // The same screen, the other side of the migration. This contrast is the whole point.
    await expect(panel).toContainText('根因域 TARGET_DATABASE');
    await expect(panel).toContainText('目标数据库');
  });

  test('a table stopped alongside another is undetermined, and the platform is 迁移平台', async ({
    page,
  }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-5?scenario=stuck-table`);
    const panel = drawer(page);
    await expect(panel).toBeVisible({ timeout: FIRST_PAINT_MS });

    await expect(panel).toContainText('因关联失败而阻塞');
    await expect(panel).toContainText('技术结果未定');
    await expect(panel).toContainText('根因域 迁移平台');

    // Gate 7, on this surface too — read the whole screen and find none of it.
    const text = (await page.locator('body').innerText()).toLowerCase();
    for (const forbidden of [
      '箱',
      '连接器',
      'topic',
      'box',
      'connector',
      'kafka',
      'blocked_by_box_failure',
    ]) {
      expect(text, `「${forbidden}」 must not reach the interface`).not.toContain(forbidden);
    }
  });

  test('a table that has not failed gets no invented cause', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-1?scenario=default`);
    const panel = drawer(page);
    await expect(panel).toBeVisible({ timeout: FIRST_PAINT_MS });

    await expect(panel).toContainText('这张表没有需要解释的失败');
    await expect(panel).toContainText('这张表没有错误事件。');
  });
});

test.describe('抽屉有自己的 loading 与 error 态', () => {
  test('says the read is running rather than showing an empty panel', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-2?scenario=loading`);
    await expect(drawer(page)).toContainText('正在读取这张表的证据。', {
      timeout: FIRST_PAINT_MS,
    });
  });

  test('offers a retry when the read failed', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/${runId}-unit-2?scenario=error`);
    const panel = drawer(page);
    await expect(panel).toContainText('表迁移单元证据读取失败', { timeout: FIRST_PAINT_MS });
    await expect(panel.getByRole('button', { name: '重试' })).toBeVisible();
  });

  test('says a table this run does not contain is missing rather than broken', async ({ page }) => {
    await page.goto(`/runs/${runId}/tables/no-such-unit?scenario=default`);
    const panel = drawer(page);
    await expect(panel).toContainText('找不到这个表迁移单元', { timeout: FIRST_PAINT_MS });
    await expect(panel.getByRole('button', { name: '重试' })).toHaveCount(0);
  });
});
