import { expect, test, type Page } from '@playwright/test';

/**
 * Seam ① — **the state-coverage matrix** (#42, ADR-0016).
 *
 * Every key view × loading / empty / error / blocked / inconclusive / disposed, each cell
 * reachable from a URL and asserted in domain language. This file is the systematic sweep;
 * the other specs in `e2e/` are the deep behavioural cases for individual journeys. The
 * duplication is deliberate — a matrix that only *referred* to assertions living elsewhere
 * would go green while a cell quietly stopped being reachable.
 *
 * Three rules the matrix follows, all of them consequences of what the six states mean:
 *
 *  - **A cell is reached by a URL, never by a walk.** Runs and drafts created in-session do
 *    not survive a reload — the mock store re-seeds on load (ADR-0016) — so every cell names
 *    a seeded world and a seeded identifier.
 *  - **A state that a view genuinely cannot enter is declared, not faked.** `#35` set that
 *    precedent honestly and this file keeps it: an unreachable cell carries the reason it
 *    is unreachable, and wherever the product can be made to *demonstrate* the reason, the
 *    demonstration is asserted too. Inventing a scenario that forced a view into a state
 *    the product cannot enter would make the matrix green and the claim false.
 *  - **Assertions are the glossary's words.** Never a Carbon class, never a DOM shape.
 *
 * What the six states mean here, stated once so the cells are comparable:
 *
 *  - **loading** — the read is in flight, and the view says what it is reading.
 *  - **empty** — there is nothing to show, and the view says what would put something there.
 *    For views addressed by an identifier, 「找不到」 is that state: the address is real, the
 *    thing it names is not.
 *  - **error** — the read failed, and the view offers a retry rather than a blank page.
 *  - **blocked** — the product refuses to go on, and says why.
 *  - **inconclusive** — DBX could not reach a judgement, and says so without softening it
 *    into a warning or hardening it into a failure.
 *  - **disposed** — a 校验处置 has closed the workflow, and the technical result is unchanged.
 */

test.use({ viewport: { width: 1680, height: 1050 } });

test.beforeEach(() => {
  test.slow();
});

/** See `e2e/execution-confirmation.spec.ts`: the dev server compiles this graph cold. */
const FIRST_PAINT_MS = 60_000;

/** The 迁移运行 every run-shaped surface is entered through (lead decision D22). */
const runId = 'run-monitored';
/** The 迁移草稿 seeded at 逐表配置与预检 (lead decision D22). */
const tablesDraftId = 'draft-ready-for-tables';

/** The six states #42 requires of every key view. */
const states = ['loading', 'empty', 'error', 'blocked', 'inconclusive', 'disposed'] as const;
type CoverageState = (typeof states)[number];

interface Reachable {
  /** The address a reviewer opens. Always carries its own `?scenario=`. */
  readonly url: string;
  /** Something to do once the page has painted, when the cell is behind an interaction. */
  readonly then?: (page: Page) => Promise<void>;
  /** What the screen must say, in domain language. */
  readonly reads: readonly (string | RegExp)[];
  /** Wording that must **not** be on screen, where the point of the cell is a distinction. */
  readonly doesNotRead?: readonly (string | RegExp)[];
}

type Cell =
  | ({ readonly kind: 'reachable' } & Reachable)
  | {
      readonly kind: 'unreachable';
      /** Why the product cannot enter this state here. Reported, never worked around. */
      readonly because: string;
      /** Where the state does live, or how the product demonstrates the refusal. */
      readonly instead?: Reachable;
    };

function reachable(cell: Reachable): Cell {
  return { kind: 'reachable', ...cell };
}

function unreachable(because: string, instead?: Reachable): Cell {
  return { kind: 'unreachable', because, ...(instead === undefined ? {} : { instead }) };
}

interface View {
  /** The view's own name, as `CONTEXT.md` words it. */
  readonly name: string;
  readonly cells: Readonly<Record<CoverageState, Cell>>;
}

/**
 * The first occurrence of some wording that a reader can actually see.
 *
 * Not simply `getByText(...).first()`. A Carbon `Select` renders its **whole** option
 * vocabulary into the document and hides all but the chosen one, so the 最近运行状态 filter
 * on 迁移任务 puts a hidden 「需要人工处理」 and a hidden 「完成，已接受风险」 into the DOM
 * ahead of any row — and a matrix that accepted those would report a state as covered on
 * the strength of a dropdown listing it. What every cell here claims is that the *screen*
 * says the word, so the visible occurrences are the only ones that count.
 */
function readsOnScreen(page: Page, wording: string | RegExp) {
  return page.getByText(wording).filter({ visible: true }).first();
}

async function visit(page: Page, cell: Reachable): Promise<void> {
  await page.goto(cell.url);
  if (cell.then !== undefined) {
    await cell.then(page);
  }
  for (const [index, reads] of cell.reads.entries()) {
    await expect(readsOnScreen(page, reads)).toBeVisible({
      timeout: index === 0 ? FIRST_PAINT_MS : 20_000,
    });
  }
  for (const absent of cell.doesNotRead ?? []) {
    await expect(page.getByText(absent)).toHaveCount(0);
  }
}

const views: readonly View[] = [
  {
    name: '数据源',
    cells: {
      loading: reachable({
        url: '/connections?scenario=loading',
        reads: ['正在读取数据库连接。'],
      }),
      empty: reachable({
        url: '/connections?scenario=empty',
        // An empty state that names the next action, not a blank page.
        reads: ['尚未登记任何数据库连接', '下一步：登记源 MySQL 与目标 PostgreSQL 的数据库连接。'],
      }),
      error: reachable({
        url: '/connections?scenario=error',
        reads: ['数据库连接读取失败', '重试'],
      }),
      blocked: unreachable(
        '数据源 只登记、校验与维护 数据库连接，它不在迁移的安全序列上，因此没有可以被挡住的动作。' +
          '一个 最近校验 不是 校验通过 的 数据库连接，在这里只是一条被记下来的事实；真正因此被拦住的' +
          '地方是 迁移向导 的 连接与数据库 阶段。',
        {
          url: '/connections?scenario=default',
          // The fact is stated here, and stated as a fact rather than as a refusal.
          reads: ['校验失败'],
        },
      ),
      inconclusive: unreachable(
        '数据库连接校验 的结论只有 校验通过 / 校验失败 / 尚未校验 三种（ADR-0006），里面没有 ' +
          '无法判定，因此这个视图无从显示一个。同一主张在 `src/conclusions/conclusion.test.ts` ' +
          '里对映射表本身再说一次。',
        {
          url: '/connections?scenario=default',
          reads: ['尚未校验'],
          // 尚未校验 is 未执行, and must never be read as 无法判定 (ADR-0004).
          doesNotRead: ['无法判定'],
        },
      ),
      disposed: unreachable(
        '校验处置 是操作员对某次 迁移运行 中某个 表迁移单元 的决定。一个 数据库连接 两者都没有，' +
          '这里没有任何东西可供处置。',
        { url: '/connections?scenario=default', reads: ['数据源'], doesNotRead: ['校验处置'] },
      ),
    },
  },
  {
    name: '迁移任务',
    cells: {
      loading: reachable({
        url: '/tasks?scenario=loading',
        // Both lists on the page say what they are reading, rather than only drawing
        // skeleton rows — which are nothing at all to a reader who cannot see them.
        reads: ['正在读取迁移任务。', '正在读取迁移草稿。'],
      }),
      empty: reachable({
        url: '/tasks?scenario=empty',
        reads: ['尚未批准任何迁移任务', '当前没有迁移草稿'],
      }),
      error: reachable({
        url: '/tasks?scenario=error',
        reads: ['迁移任务读取失败', '重试'],
      }),
      blocked: reachable({
        url: '/tasks?scenario=default',
        // A 迁移运行 that stopped and is waiting for a person. It has not ended: DBX
        // preserves the target data and the evidence rather than concluding for them.
        reads: ['需要人工处理'],
      }),
      inconclusive: unreachable(
        '一个 迁移运行 的状态是它自身单元的投影 (ADR-0004)，八个取值里没有「无法判定」：技术结果' +
          '未定是逐表的事实，住在 运行监控 与 校验报告 里。这个列表把它们链接出去，而不是替它们下结论。',
        {
          url: '/tasks?scenario=default',
          reads: ['查看迁移运行'],
        },
      ),
      disposed: reachable({
        url: '/tasks?scenario=default',
        // A 校验处置 closed the workflow; the run is 完成，已接受风险 and never 全部完成.
        reads: ['完成，已接受风险'],
      }),
    },
  },
  {
    name: '迁移任务的迁移运行历史',
    cells: {
      loading: reachable({
        url: '/tasks/task-orders-analytics/runs?scenario=loading',
        reads: ['正在读取迁移运行。'],
      }),
      empty: reachable({
        // A task identifier with no 迁移运行 behind it: 「没有」 rather than 「读不到」.
        url: '/tasks/no-such-task/runs?scenario=default',
        reads: ['该迁移任务还没有迁移运行', '迁移任务在执行确认后生成首个迁移运行。'],
      }),
      error: reachable({
        url: '/tasks/task-orders-analytics/runs?scenario=error',
        reads: ['迁移运行读取失败', '重试'],
      }),
      blocked: reachable({
        url: '/tasks/task-billing-analytics/runs?scenario=default',
        reads: ['需要人工处理'],
      }),
      inconclusive: unreachable(
        '同 迁移任务 列表：历史记录的是 迁移运行 的状态，而「技术结果未定」是逐表的结论。' +
          '每一行都带着通往 校验报告 的地址，无法判定 在那里。',
        {
          url: '/tasks/task-orders-analytics/runs?scenario=default',
          reads: ['查看校验报告'],
        },
      ),
      disposed: reachable({
        url: '/tasks/task-billing-staging/runs?scenario=default',
        reads: ['完成，已接受风险'],
      }),
    },
  },
  {
    name: '迁移向导',
    cells: {
      loading: reachable({
        url: `/tasks/new/${tablesDraftId}/tables?scenario=stage-tables-loading`,
        reads: ['正在读取逐表配置与预检。'],
      }),
      empty: reachable({
        // A 迁移草稿 that is not there. 「丢弃后不留痕迹」, so there is nothing to restore
        // and nothing to retry — which is why this is the empty state and not the error one.
        url: '/tasks/new/draft-that-was-discarded/connections?scenario=default',
        reads: ['找不到这份迁移草稿', '迁移草稿丢弃后不留痕迹'],
      }),
      error: reachable({
        url: `/tasks/new/${tablesDraftId}/tables?scenario=stage-tables-error`,
        reads: ['逐表配置读取失败', '重试'],
      }),
      blocked: reachable({
        url: `/tasks/new/${tablesDraftId}/tables?scenario=blocked-preflight`,
        // Gate 2, as the shell states it: the refusal and its reason, never a dead button
        // with nothing beside it.
        reads: ['还不能进入下一阶段', /只有结论为可迁移的预检可以继续/],
      }),
      inconclusive: reachable({
        url: `/tasks/new/${tablesDraftId}/tables?scenario=blocked-preflight`,
        // 无法判定 keeps its own form here: not a warning, not a risk to be accepted.
        reads: ['无法判定'],
      }),
      disposed: unreachable(
        '一份 迁移草稿 没有 迁移运行，因此没有 校验执行，也没有 校验处置：向导的第五、第六个' +
          '阶段属于 迁移运行。请求它们不会得到一个空页面，而是被送回真正拦住这份草稿的那个阶段。',
        {
          url: `/tasks/new/${tablesDraftId}/validation?scenario=blocked-preflight`,
          reads: [/只有结论为可迁移的预检可以继续/],
        },
      ),
    },
  },
  {
    name: '运行监控',
    cells: {
      loading: reachable({
        url: `/runs/${runId}?scenario=loading`,
        reads: ['正在读取迁移运行的进度观测。'],
      }),
      empty: reachable({
        url: '/runs/run-that-never-existed?scenario=default',
        reads: ['找不到这次迁移运行', '请从迁移任务的迁移运行列表重新进入。'],
      }),
      error: reachable({
        url: `/runs/${runId}?scenario=error`,
        reads: ['迁移运行进度读取失败', '重试'],
      }),
      blocked: reachable({
        url: `/runs/${runId}?scenario=stuck-table`,
        // 卡死 is a terminal diagnosis with a configured hard threshold, and the tables
        // stopped alongside it are blocked rather than failed.
        reads: ['卡死', '因关联失败而阻塞'],
      }),
      inconclusive: reachable({
        url: `/runs/${runId}?scenario=inconclusive-validation`,
        // ADR-0004: DBX never invents per-table blame merely to populate an outcome.
        reads: ['尚无技术结果'],
        doesNotRead: ['迁移失败'],
      }),
      disposed: reachable({
        url: `/runs/${runId}?scenario=accepted-risk`,
        reads: ['完成，已接受风险'],
      }),
    },
  },
  {
    name: '表迁移单元证据',
    cells: {
      loading: reachable({
        url: `/runs/${runId}/tables/${runId}-unit-2?scenario=loading`,
        reads: ['正在读取这张表的证据。'],
      }),
      empty: reachable({
        url: `/runs/${runId}/tables/no-such-unit?scenario=default`,
        reads: ['找不到这个表迁移单元', '请从进度矩阵重新进入。'],
      }),
      error: reachable({
        url: `/runs/${runId}/tables/${runId}-unit-2?scenario=error`,
        reads: ['表迁移单元证据读取失败', '重试'],
      }),
      blocked: reachable({
        url: `/runs/${runId}/tables/${runId}-unit-5?scenario=stuck-table`,
        reads: ['因关联失败而阻塞'],
      }),
      inconclusive: reachable({
        url: `/runs/${runId}/tables/${runId}-unit-5?scenario=stuck-table`,
        // 「Its own technical result is undetermined rather than failed」 (`CONTEXT.md`).
        reads: ['技术结果未定'],
        doesNotRead: ['迁移失败'],
      }),
      disposed: reachable({
        url: `/runs/${runId}?scenario=accepted-risk`,
        // Entered the way an operator enters it — from the row that carries the outcome —
        // because which 表迁移单元 a 校验处置 closed is a fact of the seeded world, not an
        // identifier a link should hard-code.
        then: async (page) => {
          const matrix = page.getByRole('region', { name: '进度矩阵', exact: true });
          await expect(matrix).toBeVisible({ timeout: FIRST_PAINT_MS });
          await matrix.getByText('完成，已接受风险').first().click();
          const drawer = page.getByRole('dialog', { name: '表迁移单元证据' });
          await expect(drawer).toBeVisible();
          // The evidence of a table a 校验处置 closed: the workflow ended, and the drawer
          // says so with the disposition's own wording rather than with 迁移完成.
          await expect(drawer).toContainText('完成，已接受风险');
          await expect(drawer.getByText('迁移完成', { exact: true })).toHaveCount(0);
        },
        reads: ['表迁移单元证据'],
      }),
    },
  },
  {
    name: '校验报告',
    cells: {
      loading: reachable({
        url: `/runs/${runId}/validation?scenario=loading`,
        reads: ['正在读取校验报告。'],
      }),
      empty: reachable({
        url: '/runs/no-such-run/validation?scenario=default',
        reads: ['找不到这次迁移运行'],
      }),
      error: reachable({
        url: `/runs/${runId}/validation?scenario=error`,
        reads: ['校验报告读取失败', '重试'],
      }),
      blocked: reachable({
        url: `/runs/${runId}/validation?scenario=default`,
        // The report refuses to conclude while the 校验执行 are still running, and says
        // why: 「半成品的结论会被当成结论用」.
        reads: ['校验尚未跑完', /报告不给出总体结论/],
      }),
      inconclusive: reachable({
        url: `/runs/${runId}/validation?scenario=inconclusive-validation`,
        reads: ['无法判定'],
      }),
      disposed: reachable({
        url: `/runs/${runId}/validation?scenario=accepted-risk`,
        // The disposition is recorded in its own column and its own section, and the
        // technical conclusion it did not change is still printed beside it.
        reads: ['已记录校验处置', /技术结论仍然是/],
      }),
    },
  },
];

test.describe('状态覆盖矩阵：每个关键视图 × 六态', () => {
  test('the matrix answers for every view and every state', () => {
    // The matrix is only a claim about coverage if nothing may be left out of it. A view
    // added without a cell for one of the six states fails here rather than passing by
    // being absent.
    expect(views.length).toBeGreaterThan(0);
    for (const view of views) {
      expect(Object.keys(view.cells).sort(), `${view.name} 覆盖六态`).toEqual([...states].sort());
    }
  });

  for (const view of views) {
    test.describe(view.name, () => {
      for (const state of states) {
        const cell = view.cells[state];

        if (cell.kind === 'reachable') {
          test(`${state} — 可由 URL 直达并被断言`, async ({ page }) => {
            await visit(page, cell);
          });
          continue;
        }

        test(`${state} — 本视图不存在这个状态：${cell.because.slice(0, 40)}…`, async ({ page }) => {
          // Declared rather than faked. Where the product can demonstrate the reason — the
          // fact it records instead, the refusal it makes instead, the word it does not
          // own — that demonstration is asserted here.
          expect(cell.because.length).toBeGreaterThan(40);
          if (cell.instead !== undefined) {
            await visit(page, cell.instead);
          }
        });
      }
    });
  }
});

test.describe('场景库：五个场景各自可由 URL 直达', () => {
  /**
   * The five scenarios #30 names, each entered by its own address and each recognised by
   * the thing it exists to show. The deep cases live in `run-monitoring.spec.ts`,
   * `validation-report.spec.ts` and `re-migration.spec.ts`; this is the library index —
   * the check that all five are still *reachable*, together, from a link.
   */
  const library: readonly {
    readonly name: string;
    readonly url: string;
    readonly reads: readonly (string | RegExp)[];
    readonly doesNotRead?: readonly (string | RegExp)[];
  }[] = [
    {
      name: '全部成功',
      url: `/runs/${runId}?scenario=default`,
      reads: ['迁移完成'],
      // No table failed: this is the world the other four are read against. 卡死 is not
      // asserted absent because 「只看卡死」 is a filter this screen always offers.
      doesNotRead: ['迁移失败'],
    },
    {
      name: '部分表失败',
      url: `/runs/${runId}?scenario=partial-table-failure`,
      // Both populations on one screen, which is what makes it 「部分」.
      reads: ['迁移失败', '迁移完成'],
    },
    {
      name: '某表卡死',
      url: `/runs/${runId}?scenario=stuck-table`,
      reads: ['卡死', '终局诊断', '因关联失败而阻塞'],
    },
    {
      name: '用户取消',
      url: `/runs/${runId}?scenario=operator-cancellation`,
      reads: ['已取消', '这次迁移运行已经结束，没有可以取消的东西。'],
    },
    {
      name: '校验无法判定',
      url: `/runs/${runId}/validation?scenario=inconclusive-validation`,
      // 通过 / 未通过 / 无法判定 are three conclusions, not two and a caveat.
      reads: ['无法判定'],
    },
  ];

  for (const scenario of library) {
    test(`「${scenario.name}」`, async ({ page }) => {
      await visit(page, scenario);
    });
  }
});
