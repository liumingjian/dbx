---
status: accepted (amends ADR-0019's "before the run" clause)
---

# The duration estimate replays the scheduling plan on per-shape stream rates under one shared ceiling

ADR-0019 divides planned transfer bytes by "a throughput range", but the reference bands measured by [#63](https://github.com/liumingjian/dbx/issues/63) and [#78](https://github.com/liumingjian/dbx/issues/78) differ by table shape (narrow keyed 14.3–14.5, wide text 121–137, large-record 189–192 estimator MiB/s for one stream), by concurrency, and by memory tier. A single divisor either ignores the single-stream tail or double-counts admission. We decided that 预估耗时 (duration estimate) comes from replaying the scheduling plan, with each stream at its shape's rate and all running streams under one shared ceiling ([#80](https://github.com/liumingjian/dbx/issues/80)).

## Shape rate per table

- **Large-record tables** (ADR-0003's preflight flag) use the large-record band.
- **Every other table** uses a two-point per-row cost model fitted to the narrow and wide anchors: `seconds per row = a + b × L`, so its rate is `L ÷ (a + b × L)`. L is the table's planned transfer bytes ÷ its row count, in the same estimator bytes as the bands. A table narrower than the narrow anchor takes the narrow rate; one wider than the wide anchor takes the wide rate. The model never extrapolates past its anchors, and never up to the large-record rate.
- The fit is made separately for the band's low and high ends. The anchors' row lengths ship in the release beside the band values and the reference machine spec.

## Combination: one replayer, two rate sources

- The estimator replays the scheduling plan under ADR-0002's rolling admission and every admission gate, including ADR-0031's platform memory budget and largest-first order. Each box advances at the shape rate of the table it is transferring.
- While the running boxes' rates sum above the **shared throughput ceiling**, all of them are scaled down in proportion. The ceiling is **145–151 MiB/s**, #78's ≥16 GiB all-streams-active window. It stands for the shared Sink and target write path.
- 窗口下限 (minimum window) is the largest table's solo time in the same replay. It is not computed separately.
- This is the replayer ADR-0019 already uses for 预计剩余 (remaining-time estimate). Before the run it is fed reference or history rates; during the run it is fed each stream's observed rate.

## Memory tiers

- One ceiling serves every tier. The tier acts only through the replayed admission: at 8 GiB, the memory budget queues later boxes, and the replay lengthens accordingly.
- #78's whole-run bands are the replayer's acceptance test, not an input. On the reference dataset the replay must land inside 88–92 MiB/s at ≥16 GiB and 72–97 MiB/s at 8 GiB. Feeding a whole-run band in as the ceiling would count admission queueing twice.

## Range ends

- The lower bound of the duration is one replay with every parameter at its fast end. The upper bound is one replay with every parameter at its slow end. Ends are never mixed, and nothing is sampled.
- No allowance is added for jitter in when later boxes start, which spread the 8 GiB run from 149 s to 202 s. The replay is deterministic, so its upper bound can be optimistic. The fixed wording below covers this.

## History and confidence

- When this deployment has past runs, their observed stream rates refit a and b, and their observed rates replace the large-record rate and the ceiling. This is done parameter by parameter.
- A parameter that history lacks, such as the large-record rate when no large-record table has run, falls back to the reference band.
- The estimate is 可信 (reliable) only when every parameter it used came from this deployment's history. Otherwise it is 低置信 (low confidence). ADR-0019's two levels stand.

## Operator wording

A 低置信 estimate shows its range unwidened, its source (参考吞吐带 with the reference machine spec), and one fixed sentence:

> 参考值在实验环境测得，未包含贵方源库的读取速度；源库较慢时实际耗时会更长。

The sentence states the direction of the error, never a number.

## Considered options

- **Row-length thresholds between shapes** were rejected: the rate differs about eightfold between the narrow and wide anchors, so every table near a threshold gets the wrong shape.
- **Total bytes ÷ the concurrent band, or per-box rates summed to a cap** were rejected: neither sees the single-stream tail. ADR-0019 already rejected linear extrapolation for that reason.
- **A ceiling per memory tier** was rejected: the 8 GiB tier never had all streams active at once, so its ceiling has no measurement.
- **A safety multiplier on the upper bound, or Monte Carlo over the parameters** were rejected as false precision: nothing measured calibrates the multiplier or the correlations. A trial run (试跑) remains ADR-0019's open refinement.
- **Per-parameter confidence labels** were rejected: they contradict ADR-0019's two levels.

## Consequences

- The release's reference data grows from a band to a small table: three shape bands, two anchor row lengths, and the ceiling, all versioned with the reference machine spec and re-measured with ADR-0031's constants.
- The S9 lab scenario gains a replay check against the whole-run bands. A replayer change that leaves either tier's whole-run band fails it.
