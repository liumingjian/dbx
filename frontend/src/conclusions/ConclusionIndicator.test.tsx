import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { messages } from '@/messages';
import { ConclusionIndicator } from './ConclusionIndicator';
import { conclusionIndicatorKind, dbxConclusions } from './conclusion';

/**
 * 「屏幕阅读器能读出每个结论的文字含义，结论不依赖视觉呈现」 (#30 §15, #42), at seam ②.
 *
 * This is the half of the claim a browser cannot make on its own. Seam ① can only check
 * the conclusions a given screen happens to be showing; the claim is about *every*
 * conclusion DBX can render, including the ones no fixture currently produces — and the
 * only way to state that is to render the whole vocabulary and read it back.
 *
 * What is asserted is deliberately not 「an icon has a label attribute」. It is that the
 * conclusion's own `_中文_` wording is **text in the accessibility tree**: the same words a
 * sighted reader sees, reachable without seeing the symbol, the shape or the colour that
 * accompany them. ADR-0014 requires at least three of symbol, shape, colour and text; this
 * is the test that the text is never the one left out.
 */
describe('每个结论都读得出它的意思', () => {
  it.each(dbxConclusions)('%s renders its 中文 wording as text', (conclusion) => {
    const wording = messages.conclusion.labels[conclusion];
    render(<ConclusionIndicator conclusion={conclusion} />);

    // Found by its words, not by a class, a colour or an icon name.
    expect(screen.getByText(wording)).toBeInTheDocument();
  });

  it('reads a row-specific wording when the row names something else', () => {
    // 运行监控 and 迁移任务 pass the wording the row is actually about — 「完成，已接受风险」
    // rather than the bare conclusion — and it must reach the accessibility tree the same
    // way. A label the caller supplies that never renders would be a silent regression to
    // colour-only meaning on exactly the screens that matter most.
    render(
      <ConclusionIndicator
        conclusion="INCONCLUSIVE"
        label={messages.tasks.runStatuses.COMPLETED_WITH_ACCEPTED_RISK}
      />,
    );

    expect(
      screen.getByText(messages.tasks.runStatuses.COMPLETED_WITH_ACCEPTED_RISK),
    ).toBeInTheDocument();
  });

  it('never leaves two conclusions sharing one wording', () => {
    // 无法判定 read as 不适用 — or 未执行 read as either — is the misreading `CONTEXT.md`
    // and ADR-0004 forbid. The indicators keep them apart (see `conclusion.test.ts`); this
    // is the same claim about the words, which are what a screen reader gets.
    const wordings = dbxConclusions.map((conclusion) => messages.conclusion.labels[conclusion]);
    expect(new Set(wordings).size).toBe(dbxConclusions.length);
  });

  it('answers for every conclusion the indicator table declares', () => {
    // A conclusion added to the mapping without a `_中文_` wording would render an empty
    // indicator, which is meaning carried by colour alone.
    for (const conclusion of Object.keys(conclusionIndicatorKind)) {
      const wording =
        messages.conclusion.labels[conclusion as keyof typeof conclusionIndicatorKind];
      expect(wording, `${conclusion} 需要一句中文`).toBeTruthy();
    }
  });
});
