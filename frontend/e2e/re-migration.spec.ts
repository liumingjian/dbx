import { expect, test, type Locator, type Page } from '@playwright/test';
import { expectFreeOfExecutionPlatform, visibleText } from './gate7';

/**
 * Seam ① — 重新迁移, and **Gate 9** of the nine journey gates (#30 §15.4, #41).
 *
 * Gate 9 is 「重新迁移创建新的迁移运行并显示其选定范围」, and the property behind it is that
 * **history is immutable**: `CONTEXT.md` defines a 迁移运行 as 「one immutable execution
 * attempt」 and lists 「retry in place」 under its `_Avoid_`. So this file checks three
 * things a screenshot of a single page cannot:
 *
 *  1. a new 迁移运行 exists, with an identifier of its own;
 *  2. the run it was started from is exactly as it was — same 选定范围, same conclusions,
 *     still recorded as the task's first attempt;
 *  3. the new run states its own, narrower scope beside the 迁移任务's, so a partial rerun
 *     cannot be read as a fresh attempt at the whole task (ADR-0006).
 *
 * **Every step after the re-migration is a client-side navigation.** The mocked world is
 * built when the page loads, so a `page.goto` here would discard the run this test just
 * created and quietly assert against a world in which it never happened.
 */

test.use({ viewport: { width: 1680, height: 1050 } });

test.beforeEach(() => {
  test.slow();
});

const runId = 'run-monitored';
const taskId = 'task-monitored';

/** See `e2e/execution-confirmation.spec.ts`: the dev server compiles this graph cold. */
const FIRST_PAINT_MS = 60_000;

function panel(page: Page, name: string): Locator {
  return page.getByRole('region', { name, exact: true });
}

function remigrationPanel(page: Page): Locator {
  return panel(page, '重新迁移');
}

async function openReport(page: Page, scenario: string): Promise<void> {
  await page.goto(`/runs/${runId}/validation?scenario=${scenario}`);
  await expect(page.getByRole('heading', { name: '校验报告' })).toBeVisible({
    timeout: FIRST_PAINT_MS,
  });
  await expect(remigrationPanel(page)).toBeVisible({ timeout: FIRST_PAINT_MS });
}

/** The two counts the run states about itself: its own 选定表数 and the task's. */
async function scopeCountsOf(page: Page): Promise<{ run: number; task: number }> {
  const origin = panel(page, '本次迁移运行的来源');
  // The 迁移任务's own count arrives in its own read, and until it does the page says so
  // rather than printing the run's count twice. Wait for the comparison to exist.
  await expect(origin).toContainText('迁移任务的选定表数是', { timeout: FIRST_PAINT_MS });
  const text = await origin.innerText();
  const match = /选定表数是 (\d+) 张；迁移任务的选定表数是 (\d+) 张/.exec(text);
  expect(match, `run scope sentence not found in: ${text}`).not.toBeNull();
  return { run: Number(match?.[1]), task: Number(match?.[2]) };
}

test.describe(
  'Gate 9：重新迁移创建新的迁移运行，并显示它自己的选定范围',
  { tag: ['@gate', '@gate-9'] },
  () => {
    test(
      'creates a new run, leaves the original untouched, and states its own smaller scope',
      { tag: '@blocked-9' },
      async ({ page }) => {
        // 「校验 INCONCLUSIVE」 straight from the URL: tables whose technical result is
        // undetermined are exactly what a 重新迁移 is for.
        await openReport(page, 'inconclusive-validation');

        const remigration = remigrationPanel(page);
        // Stated before anything is selected: this creates a run, it does not retry one.
        await expect(remigration).toContainText(
          '重新迁移创建新的迁移运行，不修改也不重试原有的迁移运行',
        );
        await expect(remigration).toContainText(
          '重新做连接检查、预检、写冻结确认、源基线与表写入契约',
        );

        await remigration.getByRole('button', { name: '当前页全选' }).click();
        const selected = /已选 (\d+) 张表/.exec(await remigration.innerText());
        expect(selected).not.toBeNull();
        const selectedCount = Number(selected?.[1]);
        expect(selectedCount).toBeGreaterThan(0);

        await remigration.getByRole('button', { name: '发起重新迁移' }).click();
        const dialog = page.getByRole('dialog', { name: '发起重新迁移' });
        // What is about to happen to the earlier run: nothing.
        await expect(dialog).toContainText('原有的迁移运行不会被修改，也不会被重试');
        // 「permanent checkbox」 is under 写冻结's `_Avoid_`: a new run needs a new commitment,
        // with a named 责任人 and a bounded 时限.
        await expect(dialog).toContainText('需要一次新的写冻结确认');

        const confirm = dialog.getByRole('button', { name: '发起重新迁移' });
        await expect(confirm).toBeDisabled();
        await dialog.getByLabel('责任人').fill('li.na');
        await dialog.getByLabel('写冻结时限（小时）').fill('6');
        // Starting a production migration takes intent, not a second click.
        await expect(confirm).toBeDisabled();
        await dialog.getByLabel('键入源 database 名称 orders 以确认').fill('orders');
        await expect(confirm).toBeEnabled();
        await confirm.click();

        // A new 迁移运行, with an address of its own.
        await expect(page).not.toHaveURL(new RegExp(`/runs/${runId}(\\?|$)`));
        await expect(page).toHaveURL(/\/runs\/[^/?]+/);
        await expect(page).toHaveURL(/scenario=inconclusive-validation/);
        await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible({
          timeout: FIRST_PAINT_MS,
        });
        const newRunUrl = page.url();

        const origin = panel(page, '本次迁移运行的来源');
        await expect(origin).toContainText('这是一次重新迁移');
        await expect(origin).toContainText(`没有修改也没有重试迁移运行 ${runId}`);
        // Its own selected scope, stated beside the task's so nobody reads it as a whole rerun.
        const counts = await scopeCountsOf(page);
        expect(counts.run).toBe(selectedCount);
        expect(counts.run).toBeLessThan(counts.task);
        await expect(origin).toContainText('不是整个迁移任务的重跑');

        // The evidence this run established for itself, each statement with its own instant.
        const evidence = panel(page, '本次迁移运行重新建立的证据');
        await expect(evidence).toContainText('上一次迁移运行的结论不会被带到这一次');
        await expect(evidence).toContainText('连接检查');
        await expect(evidence).toContainText('校验通过');
        await expect(evidence).toContainText('写冻结确认');
        await expect(evidence).toContainText('责任人 li.na');
        await expect(evidence).toContainText('源基线');
        await expect(evidence).toContainText('预检 可迁移');
        await expect(evidence).toContainText('表写入契约 v');

        // The history now holds both attempts, side by side, each with its own conclusion.
        await page.getByRole('link', { name: '返回迁移运行列表' }).click();
        const history = page.getByRole('region', { name: '迁移运行' }).first();
        await expect(history).toBeVisible({ timeout: FIRST_PAINT_MS });
        await expect(page.getByText('该迁移任务共有 2 次迁移运行')).toBeVisible();
        await expect(history).toContainText('首次迁移');
        await expect(history).toContainText(`重新迁移（对 ${runId}）`);
        await expect(page).toHaveURL(new RegExp(`/tasks/${taskId}/runs`));

        // And the run it came from is exactly where it was: same scope, same origin, and it
        // is still the task's first attempt rather than a record that was reused.
        await history.getByRole('link', { name: runId }).click();
        await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible({
          timeout: FIRST_PAINT_MS,
        });
        await expect(page).toHaveURL(new RegExp(`/runs/${runId}`));
        const originalOrigin = panel(page, '本次迁移运行的来源');
        await expect(originalOrigin).toContainText('这是执行确认生成的首个迁移运行');
        const originalCounts = await scopeCountsOf(page);
        expect(originalCounts.run).toBe(originalCounts.task);
        expect(originalCounts.run).toBeGreaterThan(counts.run);

        expect(newRunUrl).toContain('/runs/');
      },
    );

    test('every historical 迁移运行 is revisitable straight from its URL', async ({ page }) => {
      // A seeded 迁移任务 whose second run migrated 18 of its 1164 tables again. Both
      // attempts are addressable without walking anything, which is what makes a run a
      // record rather than a screen.
      const historicalTaskId = 'task-orders-analytics';
      await page.goto(`/tasks/${historicalTaskId}/runs?scenario=default`);
      await expect(page.getByText('该迁移任务共有 2 次迁移运行')).toBeVisible({
        timeout: FIRST_PAINT_MS,
      });

      await page.goto(`/runs/${historicalTaskId}-run-1?scenario=default`);
      await expect(panel(page, '本次迁移运行的来源')).toContainText(
        '这是执行确认生成的首个迁移运行',
        { timeout: FIRST_PAINT_MS },
      );

      await page.goto(`/runs/${historicalTaskId}-run-2?scenario=default`);
      const second = panel(page, '本次迁移运行的来源');
      await expect(second).toContainText('这是一次重新迁移', { timeout: FIRST_PAINT_MS });
      await expect(second).toContainText(`没有修改也没有重试迁移运行 ${historicalTaskId}-run-1`);
      // 18 tables of a 1164-table task: its own scope, and the task's beside it.
      const counts = await scopeCountsOf(page);
      expect(counts.run).toBeLessThan(counts.task);
      // And the run it names is one link away, still recorded as the first attempt.
      await page.getByRole('link', { name: '查看上一次迁移运行' }).click();
      await expect(panel(page, '本次迁移运行的来源')).toContainText(
        '这是执行确认生成的首个迁移运行',
      );
    });
  },
);

test.describe('重新迁移的候选是有边界的', () => {
  test('never offers a 预检排除项, because it never migrated', async ({ page }) => {
    await openReport(page, 'inconclusive-validation');
    const remigration = remigrationPanel(page);
    await expect(remigration).toContainText('预检排除项不会出现在这里');
    await expect(remigration).toContainText('它们没有迁移，也没有校验执行');

    // The tables named as 预检排除项 are named nowhere in the candidate list.
    const exclusions = panel(page, '预检排除项');
    const names = (await exclusions.innerText())
      .split('\n')
      .map((line) => line.trim().split(' ')[0] ?? '')
      .filter((name) => /^[a-z][a-z0-9_]+$/.test(name));
    expect(names.length).toBeGreaterThan(0);
    const candidateText = await remigration.innerText();
    for (const name of names) {
      expect(candidateText, `「${name}」 never migrated and is not a candidate`).not.toContain(
        name,
      );
    }
  });

  test('offers nothing while the 校验执行 have not finished', async ({ page }) => {
    // 「还没跑完」 is not a conclusion, so there is nothing here to migrate again — and the
    // panel says which kind of nothing it is rather than showing an empty table.
    await openReport(page, 'default');
    const remigration = remigrationPanel(page);
    await expect(remigration).toContainText('这次迁移运行没有需要重新迁移的表');
    await expect(remigration).toContainText('未完的校验不是失败');
    await expect(remigration.getByRole('button', { name: '发起重新迁移' })).toHaveCount(0);
  });

  test('names a table it cannot admit rather than dropping it from the list', async ({ page }) => {
    // A failed table whose fresh 预检 does not conclude `SUPPORTED` is exactly the table an
    // operator is looking for: it stays visible, with the reading that refuses it.
    await openReport(page, 'partial-table-failure');
    const remigration = remigrationPanel(page);
    await expect(remigration).toContainText('上次校验执行结论');
    await expect(remigration).toContainText('本次预检结论');
  });
});

test.describe('发起重新迁移 needs intent, from the keyboard too', () => {
  test('Enter in the challenge field starts nothing', async ({ page }) => {
    // `StartRemigrationModal` re-checks its guard inside `onRequestSubmit` **because**
    // Carbon modals submit on Enter. Nothing exercised that path, so the re-check was an
    // untested claim — and a 重新迁移 started from a keystroke is a new production run.
    await openReport(page, 'inconclusive-validation');
    const remigration = remigrationPanel(page);
    await remigration.getByRole('button', { name: '当前页全选' }).click();
    await remigration.getByRole('button', { name: '发起重新迁移' }).click();

    const dialog = page.getByRole('dialog', { name: '发起重新迁移' });
    await expect(dialog).toBeVisible();
    const before = page.url();

    await dialog.getByLabel('键入源 database 名称 orders 以确认').press('Enter');
    await expect(page).toHaveURL(before);
    await expect(dialog).toBeVisible();

    // Filling the 写冻结 fields is not consent to start either, while the name is unsaid.
    await dialog.getByLabel('责任人').fill('li.na');
    await dialog.getByLabel('写冻结时限（小时）').fill('6');
    await dialog.getByLabel('键入源 database 名称 orders 以确认').press('Enter');
    await expect(page).toHaveURL(before);
    await expect(dialog).toBeVisible();
  });
});

test.describe('Gate 7 binds this surface too', () => {
  test('no 箱, 连接器 or topic reaches 重新迁移', async ({ page }) => {
    await openReport(page, 'inconclusive-validation');
    await expect(remigrationPanel(page)).toBeVisible();
    // The whole screen, not one panel: a leak arrives wherever it arrives, and scanning the
    // panel that was written most carefully proves the least.
    expectFreeOfExecutionPlatform(await visibleText(page), '重新迁移');

    // 因关联失败而阻塞 units are re-migration candidates (D29/D35), so the 卡死 run is the
    // one place the platform's own literals would most plausibly be reached for.
    await openReport(page, 'stuck-table');
    await expect(remigrationPanel(page)).toBeVisible();
    expectFreeOfExecutionPlatform(await visibleText(page), '重新迁移（某表卡死）');
  });
});
