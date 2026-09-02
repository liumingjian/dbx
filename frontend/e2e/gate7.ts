import { expect, type Locator, type Page } from '@playwright/test';

/**
 * **Gate 7** — 「监控以表迁移单元为中心，箱/连接器/topic 不外露」 (#30 §15.4, #38).
 *
 * The gate is a negative claim: a DBA can run a production migration without ever learning
 * what the execution platform is made of. A negative claim is only as strong as the list it
 * is checked against, so there is **one list**, here, and every surface imports it. The
 * per-file copies this replaced had already drifted apart — two of them had lost
 * `waiting_for_box` and `blocked_by_box_failure`, which are precisely the literals D10 says
 * must never reach an operator.
 *
 * A surface that only checks a panel is not checking the gate either: a leak arrives in a
 * dialog, a filtered view, an exported file or a clipboard string just as easily as in the
 * main region, and those are the places a DBA copies from.
 */
export const FORBIDDEN: readonly string[] = [
  // The Chinese words, as `CONTEXT.md` and ADR-0004 use them…
  '箱',
  '连接器',
  // …the English ones a stray label or an untranslated literal would arrive in…
  'topic',
  'box',
  'connector',
  'kafka',
  // …and the two ADR-0004 phase literals named after the box, which D10 forbids shipping
  // until the lead has landed operator-facing wording. `等待调度` and `因关联失败而阻塞`
  // are what DBX says instead.
  'waiting_for_box',
  'blocked_by_box_failure',
];

/**
 * Asserts that none of the execution platform's vocabulary is in this text.
 *
 * Takes the text rather than a locator so that the same assertion covers a rendered screen,
 * an exported file and a clipboard string — the gate binds all three, because all three are
 * things an operator reads.
 */
export function expectFreeOfExecutionPlatform(text: string, where: string): void {
  const haystack = text.toLowerCase();
  for (const forbidden of FORBIDDEN) {
    expect(haystack, `${where}：「${forbidden}」 must not reach the interface`).not.toContain(
      forbidden,
    );
  }
}

/** Everything the operator can actually read on the page. */
export async function visibleText(page: Page): Promise<string> {
  return page.locator('body').innerText();
}

/** Everything the operator can read inside one region, dialog or drawer. */
export async function visibleTextOf(scope: Locator): Promise<string> {
  return scope.innerText();
}
