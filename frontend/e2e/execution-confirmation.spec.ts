import { expect, test, type Locator, type Page } from '@playwright/test';

/**
 * Seam ① — 执行确认, and **Gates 5 and 6** of the nine journey gates (#30 §15.4).
 *
 * Both gates are written as refusals, because a happy path proves nothing about a
 * constraint. Gate 5 is 「没有写冻结确认就无法启动」, so the case that matters is the one
 * where the operator presses 开始迁移 and **nothing happens** — no dialog, no run, no task.
 * Gate 6 is 「没有结构证明就不会开始写入目标」, which the frontend cannot enforce because
 * 结构证明 is a server-side catalog comparison performed inside the run; the agreed
 * assertion (lead decision D11) is that the stage **states the constraint in domain
 * language** and refuses to start while the summary reports a missing one.
 *
 * Everything asserted here is domain language: the glossary's own words, the enum literals
 * and the identifiers. Never a Carbon class, never a DOM shape.
 */

/** Stage four holds two tables and a form; give it the width it is designed for. */
test.use({ viewport: { width: 1680, height: 1050 } });

// The first navigation of a run compiles stage four's whole module graph in the dev server
// (see `FIRST_PAINT_MS`), which can outlast the default per-test budget. Slow rather than
// flaky, and stated the way the other cold-start specs state it.
test.beforeEach(() => {
  test.slow();
});

/**
 * The 迁移草稿 seeded already standing at 执行确认 (lead decision D22).
 *
 * Reaching stage four by walking would take four client-side navigations and a selection
 * out of 1200 tables, and the subject of every case below is what an operator may *not* do
 * once they are already standing there.
 */
const draftId = 'draft-ready-for-confirm';

function panel(page: Page, name: string): Locator {
  return page.getByRole('region', { name, exact: true });
}

function startButton(page: Page): Locator {
  // Exact: 「确认并开始迁移」 in the dialog would otherwise answer to the same name.
  return page.getByRole('button', { name: '开始迁移', exact: true });
}

/**
 * How long the *first* paint of a run may take.
 *
 * The suite runs four workers against one Vite dev server, which transforms each module the
 * first time it is asked for. The first navigation of a run therefore waits for stage four's
 * whole graph — the wizard, the table substrate, Carbon's modal and form — to be compiled,
 * with four workers arriving cold at once; on a freshly synced tree, where nothing is
 * cached, that has been measured past twenty seconds. Every later navigation of the same
 * run settles in under two.
 *
 * This buys patience for the dev server and nothing else: no assertion in this file is
 * relaxed by it, and a page that genuinely never renders still fails, just later.
 * `e2e/preflight-gate.spec.ts` already states its slow waits this way.
 */
const FIRST_PAINT_MS = 60_000;

async function openConfirm(page: Page, scenario: string): Promise<void> {
  await page.goto(`/tasks/new/${draftId}/confirm?scenario=${scenario}`);
  await expect(panel(page, '写冻结')).toBeVisible({ timeout: FIRST_PAINT_MS });
}

/** Confirms a 写冻结 with a named 责任人 and a bounded 时限. */
async function confirmWriteFreeze(page: Page, operator = 'zhang.wei'): Promise<void> {
  const freeze = panel(page, '写冻结');
  await freeze.getByLabel('责任人').fill(operator);
  await freeze.getByLabel('时限').selectOption('8');
  await freeze.getByLabel('变更单号').fill('CHG-2026-0901');
  await freeze.getByRole('button', { name: '确认写冻结' }).click();
  await expect(freeze).toContainText(`${operator} 已确认写冻结，时限 8 小时`);
}

test.describe('执行确认 shows the whole scope before anything starts', () => {
  test('restates the source, the target, the tables, the exclusions and the contracts', async ({
    page,
  }) => {
    await openConfirm(page, 'stage-confirm');

    const scope = panel(page, '本次执行范围');
    await expect(scope).toContainText('订单库（生产）');
    await expect(scope).toContainText('orders');
    await expect(scope).toContainText('分析库（生产）');
    await expect(scope).toContainText('orders_migrated');
    // 表写入契约 is 「the immutable, single-table write intent」: the summary counts what
    // would be approved rather than implying something still editable.
    await expect(scope).toContainText(/本次将批准 \d+ 份表写入契约，共 [\d,]+ 列/);
    await expect(scope).toContainText('启动后不再改动');
    // Every table in the 迁移范围 carries a 可迁移 预检 — that is the only way a draft
    // reaches this stage at all — and each one names the contract version it would
    // approve. `exact`, because 不可迁移 contains 可迁移 as a substring.
    await expect(scope.getByText('可迁移', { exact: true }).first()).toBeVisible();
    await expect(scope.getByText(/^v\d+$/).first()).toBeVisible();

    // 「显式排除是可复核的例外」: an excluded table is still shown, by name.
    const excluded = panel(page, '显式排除');
    await expect(excluded).toContainText('显式排除是可复核的例外');
    await expect(excluded).not.toContainText('没有显式排除任何表');
  });

  test('puts the 未解决的发现 in front of the operator rather than at the bottom', async ({
    page,
  }) => {
    await openConfirm(page, 'stage-confirm');

    // A blocking 发现 cannot reach this stage — stage three's gate holds it — so what is
    // listed is what was found, judged non-blocking, and never resolved.
    const notice = page.getByRole('alert').filter({ hasText: '未解决的发现' });
    await expect(notice).toBeVisible();
    await expect(notice).toContainText('会随这次迁移一起被带走');

    const findings = panel(page, '未解决的发现');
    await expect(findings).toContainText('大记录单值');
    await expect(findings).toContainText('超过 20 MiB 上限');

    // Prominence, stated as an ordering rather than as a colour: the findings stand above
    // the scope they belong to, so nobody reaches the start button without passing them.
    const findingsBox = await findings.boundingBox();
    const scopeBox = await panel(page, '本次执行范围').boundingBox();
    expect(findingsBox).not.toBeNull();
    expect(scopeBox).not.toBeNull();
    expect((findingsBox?.y ?? 0) < (scopeBox?.y ?? 0)).toBe(true);
  });
});

test.describe('Gate 5：没有写冻结确认就无法启动', () => {
  test('refuses the start, and says a 责任人 and a 时限 are what is missing', async ({ page }) => {
    await openConfirm(page, 'stage-confirm');

    await expect(panel(page, '写冻结')).toContainText('尚未确认写冻结');
    await expect(page.getByText(/启动前必须确认源端写冻结，并写明责任人与时限/)).toBeVisible();

    // The refusal itself: pressing 开始迁移 opens nothing and starts nothing.
    const url = page.url();
    await startButton(page).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).toHaveURL(url);
    await expect(page.getByText(/启动前必须确认源端写冻结/)).toBeVisible();

    // And no 迁移任务 came into existence behind the screen. Navigated client-side on
    // purpose: a reload would rebuild the mock world and prove nothing about this one.
    await page.getByRole('link', { name: '迁移任务' }).click();
    await expect(page.getByRole('region', { name: '迁移任务' })).not.toContainText(
      'orders → orders_migrated',
    );
    // The 迁移草稿 is still a draft, exactly where it was.
    await expect(page.getByRole('region', { name: '迁移草稿' })).toContainText(draftId);
  });

  test('a 写冻结 with no named 责任人 is not a confirmation', async ({ page }) => {
    // 「permanent checkbox」 is under 写冻结's `_Avoid_`. A blank 责任人 leaves the freeze
    // unconfirmed rather than recording an anonymous commitment.
    await openConfirm(page, 'stage-confirm');
    const freeze = panel(page, '写冻结');
    await freeze.getByLabel('时限').selectOption('12');
    await freeze.getByRole('button', { name: '确认写冻结' }).click();

    await expect(freeze).toContainText('尚未确认写冻结');
    await expect(page.getByText(/启动前必须确认源端写冻结/)).toBeVisible();
    await startButton(page).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });
});

test.describe('Gate 6：没有结构证明就不会开始写入目标', () => {
  test('states the constraint and refuses to start while a 结构证明 is missing', async ({
    page,
  }) => {
    await openConfirm(page, 'structural-proof-missing');

    // The constraint, in the glossary's own terms — 「the deterministic comparison of the
    // actual PostgreSQL table … only zero difference permits the Sink to start」 — and an
    // honest statement of who establishes it, since the frontend cannot.
    const proof = panel(page, '结构证明');
    await expect(proof).toContainText('结构证明是目标表与已批准的表写入契约的确定性比对');
    await expect(proof).toContainText('只有零差异才允许开始写入');
    await expect(proof).toContainText('没有结构证明，DBX 不会向目标表写入任何数据');
    await expect(proof).toContainText('由平台在迁移运行内、建表之后完成');

    // ADR-0011: a first run meeting an existing target table fails review; it is never
    // reused, truncated or replaced.
    await expect(proof).toContainText('无法建立结构证明的表');
    await expect(proof).toContainText('不会复用、清空或替换');

    // Even with the 写冻结 confirmed, the start is refused — and the refusal names the
    // table it is about.
    await confirmWriteFreeze(page);
    await expect(page.getByText(/没有结构证明，DBX 不会开始写入目标表/)).toBeVisible();

    const url = page.url();
    await startButton(page).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).toHaveURL(url);
  });
});

test.describe('启动是一个需要明确意图的动作', () => {
  test('will not start until the operator types the source database themselves', async ({
    page,
  }) => {
    await openConfirm(page, 'stage-confirm');
    await confirmWriteFreeze(page);

    await startButton(page).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('确认开始迁移');
    // What the operator is consenting to is stated before they consent to it.
    await expect(dialog).toContainText('不可变的执行快照');
    await expect(dialog).toContainText('范围日后不可篡改');

    const confirm = dialog.getByRole('button', { name: '确认并开始迁移' });
    await expect(confirm).toBeDisabled();

    // A near miss is not intent either.
    await dialog.getByLabel(/输入源数据库名/).fill('order');
    await expect(confirm).toBeDisabled();

    await dialog.getByLabel(/输入源数据库名/).fill('orders');
    await expect(confirm).toBeEnabled();
  });

  test('Enter in the challenge field does not start a migration either', async ({ page }) => {
    // A Carbon modal submits on Enter, which is why `StartMigrationModal` re-checks the
    // challenge inside `onRequestSubmit` rather than trusting the disabled button. Nothing
    // proved that until now: a keystroke is not a click, and a dialog whose safety lived in
    // a button's appearance would start a production migration from the keyboard.
    await openConfirm(page, 'stage-confirm');
    await confirmWriteFreeze(page);

    const before = page.url();
    await startButton(page).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('确认开始迁移');

    await dialog.getByLabel(/输入源数据库名/).press('Enter');
    // Nothing started, and the dialog is still standing there asking for the name.
    await expect(page).toHaveURL(before);
    await expect(dialog).toBeVisible();
    await expect(page).not.toHaveURL(/\/runs\//);

    // A near miss submitted by keyboard is not intent either.
    await dialog.getByLabel(/输入源数据库名/).fill('order');
    await dialog.getByLabel(/输入源数据库名/).press('Enter');
    await expect(page).toHaveURL(before);
    await expect(dialog).toBeVisible();
  });

  test('turns the 迁移草稿 into a 迁移任务 and one immutable 迁移运行', async ({ page }) => {
    await openConfirm(page, 'stage-confirm');
    await confirmWriteFreeze(page, 'li.na');

    await startButton(page).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel(/输入源数据库名/).fill('orders');
    await dialog.getByRole('button', { name: '确认并开始迁移' }).click();

    // A 迁移运行 now exists and has its own URL, like everything else in DBX.
    await expect(page).toHaveURL(/\/runs\/[^/?]+/);
    // #38 gave that route its stage's own name: the run's own page is 运行监控, and the
    // 迁移运行 it observes is named on it by identifier.
    await expect(page.getByRole('heading', { name: '运行监控' })).toBeVisible();

    // The draft became the task. Client-side, so the mock world is the same one the run
    // was created in.
    await page.getByRole('link', { name: '迁移任务' }).click();
    await expect(page.getByRole('region', { name: '迁移任务' })).toContainText(
      'orders → orders_migrated',
    );

    // And the 迁移草稿 is gone. That is what makes the recorded scope unalterable from the
    // interface: there is no longer anything through which it could be edited — a 迁移运行
    // is 「one immutable execution attempt」 and DBX offers no way to revise one.
    await expect(page.getByRole('region', { name: '迁移草稿' })).not.toContainText(draftId);
  });
});

test.describe('执行确认 has a state for a summary it cannot read', () => {
  test('offers a retry instead of a blank page', async ({ page }) => {
    await page.goto(`/tasks/new/${draftId}/confirm?scenario=stage-confirm-error`);
    await expect(page.getByText('执行确认汇总读取失败')).toBeVisible({
      timeout: FIRST_PAINT_MS,
    });
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
    // An unread summary is not a satisfied one: the stage says so rather than offering a
    // start it cannot justify.
    await expect(page.getByText(/还没有读到执行确认汇总/)).toBeVisible();
  });
});
