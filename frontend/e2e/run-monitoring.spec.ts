import { expect, test, type Locator, type Page } from '@playwright/test';
import { expectFreeOfExecutionPlatform, visibleText, visibleTextOf } from './gate7';

/**
 * Seam ① — 运行监控, and **Gate 7** of the nine journey gates (#30 §15.4, #38).
 *
 * Gate 7 is 「监控以表迁移单元为中心，箱/连接器/topic 不外露」. It is asserted as an
 * absence, which is unusual and deliberate: the claim is that a DBA can run a production
 * migration without ever learning what the execution platform is made of, and the only
 * honest way to check that is to read everything on the screen and find none of it.
 *
 * The other two subjects of this file are the ones that are easy to get subtly wrong:
 *
 *  - progress is rendered **as observation**, so it may jump and it may lag, and the
 *    interface says so rather than animating a smooth advance that the platform never
 *    reported (ADR-0004);
 *  - **卡死 has its own form**, clearly distinct from 「慢」. It is a terminal diagnosis
 *    with a configured hard threshold, not a stronger shade of a slow row.
 *
 * Everything asserted is domain language — the glossary's own words, the enum literals and
 * the identifiers. Never a Carbon class, never a DOM shape.
 */

test.use({ viewport: { width: 1680, height: 1050 } });

test.beforeEach(() => {
  test.slow();
});

/**
 * The 迁移运行 that 运行监控 is entered through (lead decision D22).
 *
 * Seeded already in flight, so 「部分表失败」 and 「某表卡死」 are one URL away instead of
 * three hours away. The scenario chooses which run it is.
 */
const runId = 'run-monitored';

/** See `e2e/execution-confirmation.spec.ts`: the dev server compiles this graph cold. */
const FIRST_PAINT_MS = 60_000;

function panel(page: Page, name: string): Locator {
  return page.getByRole('region', { name, exact: true });
}

async function openMonitor(page: Page, scenario: string): Promise<void> {
  await page.goto(`/runs/${runId}?scenario=${scenario}`);
  await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible({
    timeout: FIRST_PAINT_MS,
  });
  await expect(panel(page, '进度矩阵')).toBeVisible({ timeout: FIRST_PAINT_MS });
}

test.describe('Gate 7：监控以表迁移单元为中心，执行平台不外露', () => {
  for (const scenario of ['default', 'partial-table-failure', 'stuck-table']) {
    test(`no 箱, 连接器 or topic reaches the operator in 「${scenario}」`, async ({ page }) => {
      await openMonitor(page, scenario);

      // The organising unit, named as itself.
      await expect(page.getByText('监控以表迁移单元为中心')).toBeVisible();
      await expect(panel(page, '进度矩阵')).toBeVisible();

      // One list, shared by every surface Gate 7 binds (`e2e/gate7.ts`).
      expectFreeOfExecutionPlatform(await visibleText(page), `运行监控（${scenario}）`);
    });
  }

  test('the filtered views are bound too, and so is the 取消 dialog', async ({ page }) => {
    // 只看失败 and 只看卡死 are where a DBA goes the moment something is wrong, which is
    // exactly when a platform literal is most likely to be reached for — and the cancel
    // dialog is the one screen that describes the machinery being stopped.
    await openMonitor(page, 'partial-table-failure');
    await page.getByRole('tab', { name: '只看失败' }).click();
    await expect(panel(page, '进度矩阵').getByText('迁移失败').first()).toBeVisible();
    expectFreeOfExecutionPlatform(await visibleText(page), '只看失败');

    await openMonitor(page, 'stuck-table');
    await page.getByRole('tab', { name: '只看卡死' }).click();
    await expect(panel(page, '进度矩阵').getByText('卡死').first()).toBeVisible();
    expectFreeOfExecutionPlatform(await visibleText(page), '只看卡死');

    await openMonitor(page, 'default');
    await page.getByRole('button', { name: '取消迁移运行' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('取消不是丢弃，也不回滚');
    expectFreeOfExecutionPlatform(await visibleTextOf(dialog), '取消迁移运行对话框');
  });

  test('says 等待调度 and 因关联失败而阻塞 instead of the literals named after the box', async ({
    page,
  }) => {
    // 等待调度: 「an ordinary waiting state, not a fault, and it carries no diagnosis」.
    await openMonitor(page, 'default');
    await expect(page.getByText('等待调度').first()).toBeVisible();

    // 因关联失败而阻塞: 「its own technical result is undetermined rather than failed, and
    // it is a candidate for re-migration」.
    await openMonitor(page, 'stuck-table');
    await expect(page.getByText('因关联失败而阻塞').first()).toBeVisible();
  });
});

test.describe('每张表都带着自己的阶段、进度、技术结果与观测时间', () => {
  test('shows all four per table, and the run总体 above them', async ({ page }) => {
    await openMonitor(page, 'default');

    const matrix = panel(page, '进度矩阵');
    for (const header of ['源表', '阶段', '进度', '技术结果', '观测时间']) {
      await expect(matrix.getByText(header, { exact: true }).first()).toBeVisible();
    }
    // A progress reading is an observed row count against the 源基线, never a bare percentage.
    await expect(matrix.getByText(/[\d,]+ \/ [\d,]+ 行/).first()).toBeVisible();
    // Every row's observation carries its own UTC instant.
    await expect(matrix.getByText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC/).first()).toBeVisible();

    // The whole-run reading, with the true totals it is measured against.
    await expect(page.getByText(/观测时间 \d{4}-\d{2}-\d{2}/)).toBeVisible();
    await expect(
      page.getByText(/已观测：读取 [\d,]+ 行、写入 [\d,]+ 行；源基线共 [\d,]+ 行/),
    ).toBeVisible();
  });
});

test.describe('进度按可跳变、可滞后渲染', () => {
  test('states the rule, and marks a table whose observation trails the snapshot', async ({
    page,
  }) => {
    await openMonitor(page, 'default');

    // ADR-0004 permits observations to be coalesced, so the page says what the numbers are
    // and are not. A DBA who reads a frozen bar as 「卡住了」 needs this sentence.
    const notice = page.getByText(/进度按观测渲染/);
    await expect(notice).toBeVisible();
    await expect(notice).toContainText('可能跳变');
    await expect(notice).toContainText('也可能滞后');
    await expect(notice).toContainText('不做平滑推进');

    // A lagging row is annotated as lagging, and not as anything worse.
    await expect(panel(page, '进度矩阵').getByText('观测滞后').first()).toBeVisible();
  });

  test('a lagging observation is not a 卡死', async ({ page }) => {
    // The default run has lagging tables and no diagnosis. If lag alone produced 卡死, the
    // word would appear on this screen — and 卡死 would have become a synonym for 「慢」.
    await openMonitor(page, 'default');
    await expect(panel(page, '进度矩阵').getByText('观测滞后').first()).toBeVisible();
    await expect(page.getByRole('region', { name: '卡死' })).toHaveCount(0);
  });
});

test.describe('卡死 has its own presentation, distinct from 「慢」', () => {
  test('states the threshold, what stopped, and what was stopped alongside it', async ({
    page,
  }) => {
    await openMonitor(page, 'stuck-table');

    const stuck = panel(page, '卡死');
    await expect(stuck).toBeVisible();
    await expect(stuck).toContainText('终局诊断');
    await expect(stuck).toContainText('硬阈值');
    // The distinction, said outright rather than left to a colour.
    await expect(stuck).toContainText('慢的表仍在推进');
    await expect(stuck).toContainText(/配置的硬阈值 [\d,]+ 分钟/);
    await expect(stuck).toContainText(/诊断时间 \d{4}-\d{2}-\d{2}/);
    await expect(stuck).toContainText(/最后一次可观测进度 \d{4}-\d{2}-\d{2}/);

    // The two populations are named separately, because a DBA deciding what to re-migrate
    // needs to know which table stopped and which was stopped with it.
    await expect(stuck).toContainText('停止推进的表');
    await expect(stuck).toContainText('因关联失败而阻塞的表');
    await expect(stuck).toContainText('技术结果未定');

    // 根因域: both execution-platform domains are presented as the single 迁移平台 domain.
    await expect(stuck).toContainText('根因域 迁移平台');

    // The run has not ended: DBX preserves the target data and the evidence and waits.
    await expect(page.getByText('需要人工处理').first()).toBeVisible();
  });

  test('can be filtered for on its own, and the stalled table carries no technical result', async ({
    page,
  }) => {
    await openMonitor(page, 'stuck-table');

    await page.getByRole('tab', { name: '只看卡死' }).click();
    const matrix = panel(page, '进度矩阵');
    // The stalled table is marked 卡死 beside its phase — and ADR-0004: 「STUCK is
    // deliberately not a table outcome … DBX never invents per-table blame merely to
    // populate an outcome」, so its 技术结果 is that there is none yet.
    await expect(matrix.getByText('卡死').first()).toBeVisible();
    await expect(matrix.getByText('尚无技术结果').first()).toBeVisible();
    await expect(matrix.getByText('因关联失败而阻塞').first()).toBeVisible();
    await expect(matrix).not.toContainText('迁移完成');
  });
});

test.describe('「部分表失败」', () => {
  test('is reachable by URL and can be narrowed to the tables that failed', async ({ page }) => {
    await openMonitor(page, 'partial-table-failure');

    const matrix = panel(page, '进度矩阵');
    await expect(matrix.getByText('迁移失败').first()).toBeVisible();
    await expect(matrix.getByText('迁移完成').first()).toBeVisible();

    await page.getByRole('tab', { name: '只看失败' }).click();
    await expect(matrix.getByText('迁移失败').first()).toBeVisible();
    await expect(matrix).not.toContainText('迁移完成');

    await page.getByRole('tab', { name: '全部' }).click();
    await expect(matrix.getByText('迁移完成').first()).toBeVisible();
  });
});

test.describe('事件流与日志', () => {
  test('read as a timeline of tables and a technical log, both bounded honestly', async ({
    page,
  }) => {
    await openMonitor(page, 'stuck-table');

    const events = panel(page, '事件流');
    await expect(events).toContainText('诊断为卡死');
    await expect(events).toContainText(/进入阶段/);

    const log = panel(page, '日志');
    await expect(log).toContainText('read=');
    await expect(log).toContainText('hard threshold exceeded');

    // Bounded rendering states its bound and the true total (lead decision D24).
    await expect(log).toContainText(/仅显示最近 \d+ 行，共 \d+ 行。/);
  });
});

test.describe('取消进行中的运行', () => {
  test('states the consequences before it happens, and then stops the run', async ({ page }) => {
    await openMonitor(page, 'default');

    await page.getByRole('button', { name: '取消迁移运行' }).click();
    const dialog = page.getByRole('dialog');
    // What is being stopped, what is not being touched, and that there is no way back.
    await expect(dialog).toContainText(/会停止 \d+ 个仍在进行中的表迁移单元。/);
    await expect(dialog).toContainText(/已经终局的 \d+ 个表迁移单元不受影响/);
    await expect(dialog).toContainText('取消不是丢弃，也不回滚');
    await expect(dialog).toContainText('未完成的表需要新的迁移运行重新迁移');

    await dialog.getByRole('button', { name: '确认取消迁移运行' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // The run stops: its projected status becomes a cancellation, and the cancel control
    // gives way to a statement that there is nothing left to cancel.
    // 取消中 while it converges, 已取消 once it has: both are the run's own 取消, which is
    // the one place `CONTEXT.md` allows that word.
    await expect(page.getByText(/^(取消中|已取消)$/).first()).toBeVisible({ timeout: 30_000 });
  });

  test('「操作员取消」 is reachable by URL, already stopped', async ({ page }) => {
    await openMonitor(page, 'operator-cancellation');
    await expect(page.getByText('已取消').first()).toBeVisible();
    await expect(page.getByText('这次迁移运行已经结束，没有可以取消的东西。')).toBeVisible();
  });
});

test.describe('运行监控 has a state for an observation it cannot take', () => {
  test('offers a retry instead of a blank page', async ({ page }) => {
    await page.goto(`/runs/${runId}?scenario=error`);
    await expect(page.getByText('迁移运行进度读取失败')).toBeVisible({ timeout: FIRST_PAINT_MS });
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
  });

  test('says a run it cannot find is not there, rather than offering an impossible retry', async ({
    page,
  }) => {
    await page.goto('/runs/run-that-never-existed');
    await expect(page.getByText('找不到这次迁移运行')).toBeVisible({ timeout: FIRST_PAINT_MS });
  });
});
