import { describe, expect, it } from 'vitest';
import { deepFreeze } from './immutable';

/**
 * The property behind 「迁移运行是不可变的执行快照」, checked as a mechanism.
 *
 * The journey is asserted at seam ① (`e2e/execution-confirmation.spec.ts`); what is
 * checked here is that the store's immutability is a fact about the objects it hands out
 * rather than a claim in a comment. A `readonly` type is erased at run time; this is not.
 */
describe('deepFreeze', () => {
  it('refuses a write to the record itself', () => {
    const run = deepFreeze({ id: 'run-1', selectedTableCount: 12 });
    expect(() => {
      (run as { selectedTableCount: number }).selectedTableCount = 0;
    }).toThrow(TypeError);
    expect(run.selectedTableCount).toBe(12);
  });

  it('refuses a write to the scope nested inside it', () => {
    // The scope of the execution is exactly what must not be alterable afterwards, and it
    // is one level down — freezing only the top object would leave it open.
    const run = deepFreeze({
      id: 'run-1',
      sourceBaseline: { capturedAt: '2026-09-01T09:00:00.000Z', entries: [{ sourceTable: 'a' }] },
    });
    expect(() => {
      (run.sourceBaseline as { capturedAt: string }).capturedAt = '2026-09-02T09:00:00.000Z';
    }).toThrow(TypeError);
    expect(() => {
      (run.sourceBaseline.entries as { sourceTable: string }[]).push({ sourceTable: 'b' });
    }).toThrow(TypeError);
  });

  it('leaves primitives alone rather than failing on them', () => {
    expect(deepFreeze('run-1')).toBe('run-1');
    expect(deepFreeze(null)).toBe(null);
  });
});
