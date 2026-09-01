import { describe, expect, it } from 'vitest';
import { createControllableClock, DEFAULT_CLOCK_RATE, normaliseRate } from './clock';

// A pure-function test, in the spirit of `src/routes/paths.test.ts`. The mock store itself
// is not a seam (#30) — but the clock's arithmetic is what makes a three-hour migration
// replay in seconds and what makes two screenshots comparable, so it is worth pinning.
describe('controllable clock', () => {
  function clockWithFakeRealTime(rate?: number) {
    let realTime = 1_000;
    const clock = createControllableClock({
      anchor: '2026-09-01T09:00:00.000Z',
      rate,
      realNow: () => realTime,
    });
    return { clock, tickReal: (ms: number) => (realTime += ms) };
  }

  it('is accelerated by default', () => {
    expect(DEFAULT_CLOCK_RATE).toBeGreaterThan(1);
    const { clock } = clockWithFakeRealTime();
    expect(clock.getRate()).toBe(DEFAULT_CLOCK_RATE);
  });

  it('runs mock time at the configured rate', () => {
    const { clock, tickReal } = clockWithFakeRealTime(60);
    tickReal(1_000);
    expect(clock.nowIso()).toBe('2026-09-01T09:01:00.000Z');
  });

  it('keeps the time already elapsed when the rate changes', () => {
    const { clock, tickReal } = clockWithFakeRealTime(60);
    tickReal(1_000);
    clock.setRate(0);
    tickReal(10_000);
    expect(clock.nowIso()).toBe('2026-09-01T09:01:00.000Z');
  });

  it('can be advanced without waiting for real time', () => {
    const { clock } = clockWithFakeRealTime(60);
    clock.advance(3 * 60 * 60 * 1000);
    expect(clock.nowIso()).toBe('2026-09-01T12:00:00.000Z');
  });

  it('refuses a rate that would run time backwards', () => {
    // Negative time would be indistinguishable from a bug in the views reading this clock.
    expect(normaliseRate(-1)).toBe(DEFAULT_CLOCK_RATE);
    expect(normaliseRate(Number.NaN)).toBe(DEFAULT_CLOCK_RATE);
    expect(normaliseRate(0)).toBe(0);
  });
});
