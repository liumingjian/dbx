import { describe, expect, it } from 'vitest';
import { keepSplitSubsetsOnly } from './plex-split-subsets';

const complete = `@font-face {
  font-family: "IBM Plex Sans SC";
  font-weight: 400;
  src: url("../fonts/complete/woff2/hinted/IBMPlexSansSC-Regular.woff2") format("woff2");
}
`;

const split = `@font-face {
  font-family: "IBM Plex Sans SC";
  font-weight: 400;
  src: url("../fonts/split/woff2/hinted/IBMPlexSansSC-Regular-000.woff2") format("woff2");
  unicode-range: U+0391-03A1;
}
`;

describe('keepSplitSubsetsOnly', () => {
  it('drops the unranged fallback that points at the multi-megabyte complete family', () => {
    const result = keepSplitSubsetsOnly(complete + split);

    expect(result).not.toContain('complete/woff2');
    expect(result).toContain('split/woff2');
  });

  it('leaves a stylesheet of split subsets untouched', () => {
    expect(keepSplitSubsetsOnly(split)).toBe(split);
  });
});
