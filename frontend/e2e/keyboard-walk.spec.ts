import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Seam ① — **只用键盘可以走完整条迁移向导** (#30 §15, #42).
 *
 * This is a Playwright test and could not have been anything else. In jsdom every
 * client-side navigation in a data router throws, because the router builds a `Request` per
 * navigation and MSW rejects the `AbortSignal` jsdom supplies (lead decision D32) — and a
 * wizard walk *is* a sequence of client-side navigations. The keyboard is the other half of
 * the same argument: focus order, focus traps and what a key does to a control are browser
 * behaviours, and a test that asserted them against a simulated DOM would be asserting the
 * simulation.
 *
 * **Nothing in this file uses the mouse.** There is no `.click()`, no `.fill()` and no
 * `.selectOption()`: every control is reached with Tab and operated with Enter, Space or
 * typed characters, exactly as an operator who cannot use a pointing device would. That is
 * the whole claim, and using a convenience method anywhere would quietly void it.
 *
 * The walk deliberately runs **backwards and then forwards**. Reaching stage four is not the
 * property — ADR-0007 lets an operator return to any stage they have satisfied, so a wizard
 * that could only be walked one way by keyboard would be half walkable.
 */

test.use({ viewport: { width: 1680, height: 1050 } });

/**
 * The walk crosses six screens, two of which mount a production-sized table, and it presses
 * Tab for every stop on the way. Slow rather than flaky — nothing here is relaxed by it.
 */
test.beforeEach(() => {
  test.setTimeout(300_000);
});

/** See `e2e/execution-confirmation.spec.ts`: the dev server compiles this graph cold. */
const FIRST_PAINT_MS = 60_000;

/** The 迁移草稿 seeded already standing at 执行确认 (lead decision D22). */
const confirmDraftId = 'draft-ready-for-confirm';
/** The 迁移草稿 seeded at 逐表配置与预检 (lead decision D22). */
const tablesDraftId = 'draft-ready-for-tables';

/**
 * How many Tab presses a control may be away before the walk gives up.
 *
 * Generous on purpose: 迁移范围 mounts a bounded window of a 1200-table selector, and every
 * row in that window is a stop. The bound exists so that a control which has fallen *out*
 * of the tab order fails the test rather than hanging it.
 */
const TAB_LIMIT = 600;

/** Whether this element is the one the browser would send the next keystroke to. */
async function isFocused(target: Locator): Promise<boolean> {
  const count = await target.count();
  if (count === 0) {
    return false;
  }
  return target.evaluate((element) => element === document.activeElement);
}

/**
 * Walks the tab order until `target` has focus.
 *
 * The only way this test knows how to reach anything. A control that cannot be reached this
 * way cannot be reached by an operator using a keyboard either, which is precisely the
 * failure the file exists to catch.
 */
async function tabTo(page: Page, target: Locator, what: string): Promise<void> {
  await expect(target).toBeVisible({ timeout: FIRST_PAINT_MS });
  for (let pressed = 0; pressed < TAB_LIMIT; pressed += 1) {
    if (await isFocused(target)) {
      return;
    }
    await page.keyboard.press('Tab');
  }
  throw new Error(`「${what}」 is not reachable by Tab within ${TAB_LIMIT} stops`);
}

/** Reaches a control with Tab and presses it with Enter. */
async function pressByKeyboard(page: Page, target: Locator, what: string): Promise<void> {
  await tabTo(page, target, what);
  await page.keyboard.press('Enter');
}

/** Reaches a text field with Tab and types into it, character by character. */
async function typeByKeyboard(
  page: Page,
  target: Locator,
  text: string,
  what: string,
): Promise<void> {
  await tabTo(page, target, what);
  await page.keyboard.type(text);
}

function button(page: Page, name: string): Locator {
  return page.getByRole('button', { name, exact: true });
}

function stageLead(page: Page, stage: string): Locator {
  // The shell prints 「阶段：<名称>」 once, from the one table that names the stages
  // (ADR-0007). Reading it is how the walk knows where it is standing.
  return page.getByText(`阶段：${stage}`, { exact: true });
}

async function expectStandingAt(page: Page, stage: string): Promise<void> {
  await expect(stageLead(page, stage)).toBeVisible({ timeout: FIRST_PAINT_MS });
}

/** The wizard's six stages, in journey order (ADR-0007). */
const wizardStages = ['连接与数据库', '迁移范围', '逐表配置与预检', '执行确认'] as const;

test.describe('只用键盘走完整条迁移向导', () => {
  test('从迁移任务进入向导，退回第一阶段，再一路走到迁移运行与校验报告', async ({ page }) => {
    // The only pointing-device-free entry there is: an address. Everything after this is
    // Tab, Enter, Space and typed characters.
    await page.goto('/tasks?scenario=stage-confirm');
    await expect(page.getByRole('heading', { name: '迁移草稿' })).toBeVisible({
      timeout: FIRST_PAINT_MS,
    });

    // 继续编辑 lands on the stage this draft has actually reached, which is 执行确认.
    await pressByKeyboard(page, button(page, '继续编辑'), '继续编辑');
    await expectStandingAt(page, '执行确认');
    // The right draft, named by its identifier — 继续编辑 resumes work, it does not start it.
    await expect(page.getByText(confirmDraftId)).toBeVisible();

    // Backwards first: ADR-0007 lets an operator return to any stage they have satisfied,
    // and 上一步 has to carry them there under the keyboard as well.
    for (const stage of ['逐表配置与预检', '迁移范围', '连接与数据库']) {
      await pressByKeyboard(page, button(page, '上一步'), '上一步');
      await expectStandingAt(page, stage);
    }

    // Stage one, reached by keyboard, still knows the choices the draft carries: the pair
    // and the落点 are facts, not something re-entered on the way past.
    await expect(page.getByText('本次迁移落点')).toBeVisible();

    // And forwards again, through every stage, one 下一步 at a time. Each hop waits for
    // the stage's own gate to have finished reading what it needs: an unread safety fact
    // blocks exactly like a failed rule (lead decision D22), so pressing 下一步 into a
    // half-read stage would be racing the gate rather than passing it.
    for (const stage of wizardStages.slice(1)) {
      await expect(page.getByText('还不能进入下一阶段')).toHaveCount(0, {
        timeout: FIRST_PAINT_MS,
      });
      await pressByKeyboard(page, button(page, '下一步'), '下一步');
      await expectStandingAt(page, stage);
    }

    // 写冻结 — Gate 5's form, filled entirely from the keyboard. A 责任人 typed rather than
    // a box ticked is the whole distinction `CONTEXT.md` draws with 「permanent checkbox」.
    const freeze = page.getByRole('region', { name: '写冻结', exact: true });
    await expect(freeze).toBeVisible({ timeout: FIRST_PAINT_MS });
    await typeByKeyboard(page, freeze.getByLabel('责任人'), 'zhang.wei', '责任人');
    await typeByKeyboard(page, freeze.getByLabel('变更单号'), 'CHG-2026-0901', '变更单号');
    await pressByKeyboard(page, freeze.getByRole('button', { name: '确认写冻结' }), '确认写冻结');
    await expect(freeze).toContainText('zhang.wei 已确认写冻结');

    // 开始迁移 — and the challenge, typed. `StartMigrationModal` re-checks it inside the
    // submit handler precisely because a dialog reachable from the keyboard must not rest
    // its safety on a button's appearance.
    await pressByKeyboard(page, button(page, '开始迁移'), '开始迁移');
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('确认开始迁移');
    await typeByKeyboard(page, dialog.getByLabel(/输入源数据库名/), 'orders', '源数据库名');
    await pressByKeyboard(
      page,
      dialog.getByRole('button', { name: '确认并开始迁移' }),
      '确认并开始迁移',
    );

    // The 迁移运行 exists, and the keyboard walked all the way into it.
    await expect(page).toHaveURL(/\/runs\/[^/?]+/, { timeout: FIRST_PAINT_MS });
    await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible({
      timeout: FIRST_PAINT_MS,
    });

    // The wizard's sixth stage, seen from the run it concludes (#40): the last hop of the
    // journey, taken with the same two keys.
    await pressByKeyboard(page, page.getByRole('link', { name: '查看校验报告' }), '查看校验报告');
    await expect(page.getByRole('heading', { name: '校验报告' })).toBeVisible({
      timeout: FIRST_PAINT_MS,
    });
  });

  test('迁移范围的筛选与选择都能只用键盘操作', async ({ page }) => {
    // The stage where a keyboard operator would be abandoned first: a 1200-table selector
    // whose selection lives in a toolbar above a virtualised grid. Walking past it with
    // 下一步 proves the shell; this proves the stage.
    await page.goto(`/tasks/new/${tablesDraftId}/scope?scenario=blocked-preflight`);
    const summary = page.getByRole('status', { name: '迁移范围汇总' });
    await expect(summary).toBeVisible({ timeout: FIRST_PAINT_MS });

    // The search is a text field like any other, reached and typed.
    await typeByKeyboard(page, page.getByLabel('按名称搜索源表'), 'order', '按名称搜索源表');

    // 「符合当前筛选的全部」 — the scope this stage actually uses, taken from the keyboard.
    // D19: stage two is virtualised rather than paged, so this button and the count it
    // moves are the property, and 「当前页全选」 has no referent here.
    await pressByKeyboard(page, button(page, '选中符合当前筛选的全部'), '选中符合当前筛选的全部');
    await expect(page.getByText(/已选中符合当前筛选的全部 [\d,]+ 张/)).toBeVisible();
    await expect(summary).toContainText(/迁移范围共 [\d,]+ 张/);

    // And the selection can be given up again without a pointer. Re-entered by address
    // first, so that the walk to 清除选择 starts from the top of the tab order rather than
    // from wherever the previous control left it: the 迁移草稿 carries the selection back,
    // which is the durability #34 built the write-through for.
    await page.goto(`/tasks/new/${tablesDraftId}/scope?scenario=blocked-preflight`);
    await expect(summary).toBeVisible({ timeout: FIRST_PAINT_MS });
    await pressByKeyboard(page, button(page, '清除选择'), '清除选择');
    await expect(summary).toContainText('迁移范围共 0 张');
  });

  test('被挡住的阶段，用键盘也走不过去', async ({ page }) => {
    // The keyboard must not be a way around a gate. Gate 2 refuses 逐表配置与预检 whose
    // 预检 did not conclude 可迁移, and pressing 下一步 with the keyboard has to be refused
    // by the same evaluation that refuses the pointer — with the reason still on screen.
    await page.goto(`/tasks/new/${tablesDraftId}/tables?scenario=blocked-preflight`);
    await expect(page.getByText('还不能进入下一阶段')).toBeVisible({ timeout: FIRST_PAINT_MS });

    const before = page.url();
    await pressByKeyboard(page, button(page, '下一步'), '下一步');

    await expect(page).toHaveURL(before);
    await expect(page.getByText(/只有结论为可迁移的预检可以继续/)).toBeVisible();
  });
});
