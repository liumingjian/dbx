import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { expect, test } from '@playwright/test';

/**
 * Seam ① — **the nine journey gates, as one suite** (#30 §15.4, #42).
 *
 * The gates were written one ticket at a time and live beside the journeys they bind:
 * Gate 1 with 迁移范围, Gates 2 and 3 with 逐表配置与预检, Gate 4 with the 单表工作区,
 * Gates 5 and 6 with 执行确认, Gate 7 with 运行监控, Gate 8 with 校验报告, Gate 9 with
 * 重新迁移. That is the right home for each case — a gate is a property of a journey, not
 * of a file — but it leaves the *set* of them unrepresented anywhere, and a set nobody
 * names is a set that can quietly lose a member.
 *
 * So each gate's `test.describe` carries the tags `@gate` and `@gate-N`, and the one case
 * that proves the gate **blocked** something carries `@blocked-N`. Two things follow:
 *
 *  - the nine run as one suite — `npm run test:e2e:gates` is `playwright test --grep @gate`;
 *  - this file fails if a gate loses its case, loses its tag, or is never written at all.
 *
 * It asserts against the suite's own source rather than against a browser, because what it
 * checks is a fact about the suite. A gate whose blocking case was deleted would otherwise
 * leave nothing red: the remaining cases would still pass, and the gate would simply stop
 * being tested.
 */

const gates: Readonly<Record<number, string>> = {
  1: '一张表都没选时不能前进',
  2: '结论为不可迁移或无法判定的预检不能被批准',
  3: '映射变更使契约失效并重跑受影响的证据',
  4: 'DDL 只读',
  5: '没有写冻结确认就无法启动',
  6: '没有结构证明就不会开始写入目标',
  7: '监控以表迁移单元为中心，箱/连接器/topic 不外露',
  8: '技术校验结论与接受风险在视觉与语义上都可区分',
  9: '重新迁移创建新的迁移运行并显示其选定范围',
};

const here = dirname(fileURLToPath(import.meta.url));

function specSources(): readonly { readonly file: string; readonly source: string }[] {
  return readdirSync(here)
    .filter((entry) => entry.endsWith('.spec.ts'))
    .map((file) => ({ file, source: readFileSync(join(here, file), 'utf8') }));
}

test.describe('§15.4 的九条 journey gate 是一整套', () => {
  test('每一条都有自己的套件，并且带着 @gate 标签', () => {
    const sources = specSources();

    for (const [number, subject] of Object.entries(gates)) {
      const tag = `@gate-${number}`;
      const owners = sources.filter(({ source }) => source.includes(tag));

      expect(owners.length, `Gate ${number}（${subject}）应当正好有一个套件带 ${tag}`).toBe(1);
      const owner = owners[0];
      expect(owner).toBeDefined();
      // Tagged into the whole set as well, so `--grep @gate` really is all nine.
      expect(owner?.source ?? '', `${owner?.file} 的 Gate ${number} 也要带 @gate`).toMatch(
        new RegExp(`'@gate',\\s*'${tag}'`),
      );
    }
  });

  test('每一条都有一个证明它「挡住了」的用例', () => {
    // A gate is a refusal. A suite that only demonstrated the happy path underneath it
    // would be describing the journey, not the constraint — which is exactly the failure
    // #30 §15.4 exists to prevent, and why every gate names the case that gets refused.
    const sources = specSources();

    for (const [number, subject] of Object.entries(gates)) {
      const tag = `@blocked-${number}`;
      const owners = sources.filter(({ source }) => source.includes(tag));

      expect(owners.length, `Gate ${number}（${subject}）需要一个被挡住的用例（${tag}）`).toBe(1);
    }
  });

  test('九条都在，一条不多一条不少', () => {
    // The count is part of the claim: #30 §15.4 names nine, and a tenth tag appearing
    // without a subject here — or a ninth quietly becoming an eighth — is a drift between
    // the specification and the suite that nothing else would catch.
    expect(Object.keys(gates)).toHaveLength(9);

    const tagged = new Set<string>();
    for (const { source } of specSources()) {
      for (const match of source.matchAll(/@gate-(\d+)/g)) {
        tagged.add(match[1] as string);
      }
    }
    expect([...tagged].sort((left, right) => Number(left) - Number(right))).toEqual(
      Object.keys(gates),
    );
  });
});
