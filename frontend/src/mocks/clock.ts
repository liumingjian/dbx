import type { IsoTimestamp } from '@/contract';

/**
 * The controllable clock (ADR-0016).
 *
 * A three-hour migration has to be reviewable in seconds, and a screenshot taken twice
 * has to show the same instant, so mock time is not wall-clock time: it runs at an
 * adjustable rate from a fixed anchor. The rate is accelerated by default — that is
 * delivery infrastructure, not a convenience, because without it no failure state can be
 * reviewed and no multi-hour run can be demonstrated.
 */
export interface ControllableClock {
  /** Milliseconds since the epoch, in mock time. */
  now(): number;
  nowIso(): IsoTimestamp;
  /** How many mock milliseconds pass per real millisecond. */
  getRate(): number;
  setRate(rate: number): void;
  /** Jump mock time forward without waiting for it. */
  advance(mockMilliseconds: number): void;
}

/** Mock time runs sixty times faster than real time unless a scenario says otherwise. */
export const DEFAULT_CLOCK_RATE = 60;

/**
 * The instant every scenario starts from. Fixed so that two runs of the same scenario
 * produce the same timestamps, which is what makes screenshots comparable.
 */
export const DEFAULT_CLOCK_ANCHOR = '2026-09-01T09:00:00.000Z';

export interface ControllableClockOptions {
  /** Mock time at rate-change zero. */
  readonly anchor?: IsoTimestamp;
  readonly rate?: number;
  /** Real time source; injected so tests do not have to wait for anything. */
  readonly realNow?: () => number;
}

export function createControllableClock(options: ControllableClockOptions = {}): ControllableClock {
  const realNow = options.realNow ?? (() => Date.now());
  let rate = normaliseRate(options.rate);
  let mockAnchor = Date.parse(options.anchor ?? DEFAULT_CLOCK_ANCHOR);
  let realAnchor = realNow();

  function reanchor(): void {
    mockAnchor = mockAnchor + (realNow() - realAnchor) * rate;
    realAnchor = realNow();
  }

  // Written as a named function rather than a method so that destructuring the clock
  // cannot silently detach it from its anchors.
  const now = (): number => mockAnchor + (realNow() - realAnchor) * rate;

  return {
    now,
    nowIso() {
      return new Date(now()).toISOString();
    },
    getRate() {
      return rate;
    },
    setRate(next) {
      reanchor();
      rate = normaliseRate(next);
    },
    advance(mockMilliseconds) {
      reanchor();
      mockAnchor += mockMilliseconds;
    },
  };
}

/**
 * A rate has to be a finite, non-negative number. Zero is allowed and means frozen time —
 * useful for a screenshot — but a negative or unparseable rate is rejected rather than
 * quietly accepted, because time running backwards would be indistinguishable from a bug
 * in the views that read this clock.
 */
export function normaliseRate(rate: number | undefined): number {
  if (rate === undefined || !Number.isFinite(rate) || rate < 0) {
    return DEFAULT_CLOCK_RATE;
  }
  return rate;
}
