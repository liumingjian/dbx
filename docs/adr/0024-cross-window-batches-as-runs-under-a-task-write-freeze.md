---
status: accepted (amends the run-scoped write-freeze clause of ADR-0006)
---

# Cross-window migration is several runs under one task write freeze, judged by a task conclusion

A customer's downtime window can be shorter than the migration. ADR-0019 lets the DBA see that before the run, but ADR-0006 left only one move: cancel, then start the whole database again in the next window. DBX v1 therefore lets a migration task be completed across several windows, one set of tables per window, provided every table covered stays frozen for the whole span. This is not CDC: nothing is synchronised while the source is live. Decided in [#60](https://github.com/liumingjian/dbx/issues/60).

- **Mechanism: ordinary runs.** Each window is one ordinary, immutable migration run over a subset of the task's tables, created through 重新迁移 (re-migration) and the wizard as ADR-0020 describes. The draft for the next window is pre-scoped to the tables with no result yet: failed, undetermined, or never run. Runs stay as ADR-0004 and ADR-0006 define them. Each run has its own write freeze, source baseline, and target generations, and the rule of one non-terminal run per task is unchanged.
- **Planning is advice.** When the duration estimate's upper bound exceeds the write-freeze time limit, 执行确认 (execution confirmation) proposes a split into sets of tables, each fitting the window, computed by the ADR-0019 estimator. The proposal is recomputed from the remaining tables at every later window, when throughput confidence has usually become 可信 (reliable). The version shown to the change board is kept as a snapshot. The operator may accept it or choose tables freely. No split rescues a table whose own minimum window exceeds the downtime window, because v1 has no single-table sharding.
- **Stopping at the window's edge.** Cancellation gains a gentler form, 收尾取消 (finishing cancellation): DBX admits no new box and lets the tables already transferring finish. The remaining-time estimate tells the operator when to choose it. Tables never admitted end as 因运行取消而停止 (stopped by run cancellation), and they are candidates for 重新迁移. No new unit outcome or run status is added. DBX never stops on its own, because the freeze is a person's commitment.
- **Task write freeze (整库冻结承诺).** Amending ADR-0006, which ends a freeze with its run: the migration task carries a commitment over its whole scope, with an accountable operator and a deadline at the last planned window. It covers the tables still to migrate and the tables already migrated. The commitment is reconfirmed, and may be extended, before every run. Each run's own write freeze is unchanged.
- **Drift checks.** Before each later run starts, and once after the last one (the closing check), DBX rereads `COUNT(*)` and the primary key terminal value of every already-migrated table and compares them with that table's original source baseline. The scans are included in the duration estimate. A difference proves the freeze was broken. No difference proves nothing about row-preserving updates, as ADR-0006 and #16 already state.
- **Task conclusion (整库结论).** The task conclusion is a projection that is never edited. For each table in scope it takes the result of the table's latest migration unit, then overlays the closing check. It reads green only when every table's latest result is 迁移完成 (migration complete) and the closing check found no drift. A drifted table shows `INCONCLUSIVE / SOURCE_CHANGED` in the task conclusion and becomes a re-migration candidate. The earlier unit's result is never rewritten. A latest result of 完成，已接受风险 (completed with accepted risk) carries its risk wording into the task conclusion.

## Considered options

- **One run executed across several windows** was rejected. It breaks run immutability and puts tables from different source boundaries in one run, which ADR-0006 already rejected.
- **No cross-run commitment, relying on a final check alone** was rejected. Drift found at the end would have no accountable party, and days of exposure would go unnoticed until then.
- **Cancellation only** was rejected. Hours of work on a large table would be thrown away just before it finished.
- **Automatic stop at the freeze deadline** was rejected. It would make the operator's decision for them.
- **A binding batch plan** was rejected. It would freeze the least accurate estimate as a contract.

## Consequences

The task gains two projections and one record: the task conclusion, the proposed split, and the task write freeze with its confirmations. The task detail page (ADR-0020) needs a place to show the task conclusion beside the per-run views. Drift checks add full scans of migrated tables to every later window.
