---
status: accepted (amends the shell clause of ADR-0020 and the warning clauses of ADR-0001 and ADR-0002; execution-platform clause amended by ADR-0032: a Connect restart fails its running boxes at once, and the ten-minute grace covers unreachability only; extended by ADR-0039 to Schema Registry, while database unreachability never changes the condition)
---

# The runtime condition observes and never adjudicates

DBX v1 ships its own single-node Kafka, Connect, and Schema Registry, and the customer's network never lets it phone home. Nobody but DBX can see that execution platform, so a state DBX does not show does not exist anywhere. Until now the only thing an operator could see was a table-centred timeline. When a run stalls, the DBA cannot tell "this table is stuck" from "the disk under DBX is full". DBX v1 therefore keeps a **runtime condition** (运行状况): a continuous, installation-wide account of whether it can keep migrating now ([#55](https://github.com/liumingjian/dbx/issues/55)).

- **Audience and wording.** The only reader is the single DBA user; v1 introduces no operations role. The interface never names broker, topic, connector, task, lag, or Schema Registry. The condition says whether DBX can keep working and which root-cause domain stands in the way: 运行环境, 迁移平台, or DBX 自身. Native readings belong in the diagnostic package only.
- **Form.** The top bar gets one persistent indicator next to language and theme, amending ADR-0020's shell. It shows an icon and the condition value, and opens a per-item panel. A global banner appears only when the condition is 受阻 or 无法判定, and it states the reason and who acts. There is no page and no dashboard.
- **Items and sources.** Four items:
  - Kafka data-disk usage: the environment check's E6 reading, sampled every 10 s.
  - Reachability of Kafka, Connect, and Schema Registry: E2's readiness probes.
  - A writable H2 directory: E7.
  - DBX-held space awaiting reclamation (below).

  Host memory, lag, offsets, throughput, and individual connector or task state are excluded. They belong to startup, to progress, to 运行监控, or to a single run.
- **Values.** 畅通, 需留意, 受阻, 无法判定. The whole condition takes its worst item's value, and only the last two stop admission. The condition also lists the latest environment check's unmet items as reasons, without re-running or rewriting them.
- **Gates are unchanged.** The condition interrupts nothing by itself. ADR-0002's 60% admission gate and 90% / 10 GB stop still act as written, and at 90% the stop now also raises the banner. The 80% warning that ADR-0002 left unplaced turns the condition to 需留意, with no banner and no interruption.
- **Table-scoped signals stay with tables.** ADR-0001's two-minute "suspected stuck" warning appears only on the affected table migration units in 运行监控, never in the runtime condition.
- **Execution platform unreachable mid-run.** 卡死 covers only connectors that still report healthy, and Docker restarts containers itself. While Kafka or Connect is unreachable:
  - DBX admits no new box.
  - Running boxes do not accrue 卡死's no-progress time.
  - The run stays 进行中, and the condition is 受阻.
  - After ten continuous minutes, the same as 卡死's hard threshold, each affected box fails with a structured diagnosis *execution platform unreachable* in the 迁移平台 domain. Its unfinished members become 因关联失败而阻塞 per ADR-0004, with no automatic retry, and they remain candidates for 重新迁移.
  - Admission resumes on its own once the platform is reachable again.
- **Space awaiting reclamation.** Failed cleanup never changes a migration result, but ADR-0002 keeps its space counted as occupied, which silently shrinks later admission. The disk item shows it as *DBX 待回收占用 X GB*, grouped by the migration runs that hold it, each with an entry into 丢弃. Orphan `dbx-` connectors that no run owns go to the diagnostic package only.
- **History.** DBX stores condition changes only: when, from which value to which, and why. The record is bounded (about the last 1,000 changes) and feeds the panel's recent changes and the diagnostic package. It stores no time series and draws no charts.

**The boundary with log aggregation.** The runtime condition is a bounded, structured set of current conclusions over fixed items. Log aggregation collects and searches raw process text. The former is in v1 scope and the latter stays out of scope. A proposal that stores raw log lines or metric time series is log aggregation under another name.

## Considered options

- **A second, operations-facing user with native Kafka metrics** was rejected. v1 is single-user with no roles, Kafka is DBX's private detail, and the customer's operations team could neither change nor interpret it.
- **Extending the environment check to run continuously** was rejected. An environment check is a gating proof with per-item verdicts. The runtime condition is an observation that adjudicates nothing, so merging them would blur which one blocks.
- **A dedicated status page** was rejected. The DBA needs the answer at two moments, before starting and while a run is not moving, and a persistent indicator covers both without a navigation item. ADR-0020 had already cut the dashboard.
- **Failing a box as soon as the platform is unreachable** was rejected, because Compose restarts and Connect recovery usually heal it within minutes. **Waiting indefinitely** was rejected because the write freeze keeps running out.
- **Storing metric time series** was rejected. It drifts toward the out-of-scope monitoring store, and nobody reads the curves offline.

## Consequences

The backend needs an installation-scoped status channel beside the run-scoped `RunProgressSource` of ADR-0016. The diagnosis catalog of ADR-0005 gains one structured code for an unreachable execution platform. The diagnostic package ([#56](https://github.com/liumingjian/dbx/issues/56)) should carry the condition-change record.
