---
status: accepted (amends the estimate clause of ADR-0002)
---

# Migration duration is estimated before the run, from history or a shipped reference band

> Amended by [ADR-0034](0034-duration-estimate-replays-the-scheduling-plan-on-shape-rates.md): "a throughput range" is now a replay of the scheduling plan, with per-shape stream rates under one shared throughput ceiling. History replaces reference parameters one by one.

> Amended by [ADR-0038](0038-duration-estimate-waits-for-preflight-and-history-is-per-source.md): the estimate first appears once preflight completes, not once scope is settled, and "this deployment's past runs" means the source data source's past runs under the same estimate basis.

An offline migration under write freeze stops the customer's business, and the DBA must put a number on the downtime-window request before the change board approves it. ADR-0002 showed no completion estimate on a first run, which left the DBA nothing to request a window with. DBX v1 therefore gives a **duration estimate** (预估耗时) before any run, and a **remaining-time estimate** (预计剩余) during it, both from one backend estimator that every page only renders ([#59](https://github.com/liumingjian/dbx/issues/59)).

- **Before the run.** Once the migration scope is settled, DBX divides the conservative transfer bytes (ADR-0002, from unfrozen `information_schema` statistics) by a throughput range, applied through the scheduling plan's concurrency. The range comes from this deployment's own past runs when they exist (confidence 可信), otherwise from a **reference throughput band** measured in the project lab and shipped in the release with its reference machine spec (confidence 低置信). Every estimate also states the **minimum window** (窗口下限): the largest table's time through its single extraction stream. The estimate is kept on the migration draft, so the DBA can take it to the change board days ahead, and is recomputed from exact counts once the source baseline is captured.
- **During the run.** The remaining-time estimate replays the unfinished part of the immutable scheduling plan under rolling admission at each stream's observed bytes throughput, yielding a range whose tail reflects the last large table running alone. ADR-0002's gate (five minutes and 1% of data) still decides when it replaces the duration estimate.
- **When it stops being credible.** If elapsed time passes the duration estimate's upper bound, or the live range stays unstable across consecutive samples, DBX shows **estimate unavailable** (无法预估) with its reason and the still-reliable facts (bytes transferred of total, current throughput), and returns to a range once throughput stabilises. **Unstable** is defined in [#93](https://github.com/liumingjian/dbx/issues/93) over projected finish instants, not remaining durations: the estimate is recomputed on each 10 s progress poll (#89 item 7), the last **6 consecutive samples** are kept, and the range is unstable when the spread between the earliest and latest projected finish instant in that window exceeds **25%** of the window's median remaining duration. Returning to a range needs that same measure to stay at or below 25% for a full window of 6, which is the only hysteresis; fewer than 6 samples is never unstable, because the handover already cost five minutes and 1% of the data. The 6 and the 25% are versioned with ADR-0034's constants. Each switch is recorded on the run's timeline.
- **At execution confirmation**, an upper bound beyond the write freeze's time limit raises a warning; it never blocks execution.
- **Commitment.** The technical plan publishes the method, the reference band, and its machine spec as a reference, not an SLA. v1 promises that the DBA can estimate, not how fast the migration runs.

Estimates remain presentation only: they never drive correctness, timeouts, or `STUCK`.

## Considered options

- **No first-run estimate (ADR-0002 as written)** was rejected: the window request needs its number before the first run exists.
- **A benchmark at install time** was rejected: it measures the bundled Kafka and the target, not the customer's MySQL read path, which is usually the bottleneck.
- **Scaling the reference band to customer hardware** was rejected as false precision: nothing calibrates the formula, and the source database's speed is invisible to it.
- **Linear extrapolation by bytes or by finished tables** was rejected: under largest-first scheduling it underestimates the single-stream tail badly.
- **Three confidence levels** were rejected: only two sources exist (reference band, observed history or live data), and no data would place a middle boundary.
- **A trial run that does not write the target** stays open as a later refinement once real estimate error is visible.

## Consequences

The reference band must be reproducible: the lab needs a bulk dataset and a timed throughput scenario before the band can ship. Estimates depend on unfrozen statistics until the source baseline exists, so the draft's number and the run's recomputed number can legitimately differ.
