---
status: accepted (amends ADR-0019's "once the migration scope is settled" timing and ADR-0034's "History and confidence")
---

# The duration estimate waits for preflight, and history is kept per source data source

ADR-0034 replays the scheduling plan, and that plan needs preflight output. M, the largest exact row byte length, sets each box's execution signature, reservation and admitted concurrency (ADR-0031, ADR-0033). The large-record flag sets a table's band and its isolation (ADR-0003). ADR-0019 still showed 预估耗时 (duration estimate) from stage 2, 迁移范围 (migration scope), before any of these facts exist. ADR-0034 also left "this deployment's past runs" undefined ([#87](https://github.com/liumingjian/dbx/issues/87)).

## When the estimate exists

- 预估耗时 first appears once the draft's preflight completes, in stage 3, 预检 (preflight). Stage 2 shows no estimate and no provisional one.
- If any table's preflight goes stale, for example after a stage-4 mapping rule (ADR-0020), the whole estimate shows no number. It reads 「预检已过期，重新预检后更新」 until that table passes preflight again. 执行确认 (execution confirmation) already requires the rerun, so this adds no gate.
- The draft's estimate carries its preflight time, so the DBA can see which day's statistics the number the change board receives rests on. ADR-0019's recomputation from the source baseline stands.

## What history is

- **Scope.** History belongs to a source 数据源 (data source). Stream rates, a, b and the large-record rate come only from that source's past runs. The shared ceiling belongs to the target 数据源, because it stands for the Sink and target write path. A new source starts with no history, so its estimate is 低置信 (low confidence).
- **Sample.** One sample is one table migration unit whose transfer finished, whatever its run's outcome. A transfer shorter than 60 s is dropped, because connector start-up dominates it. A ceiling observation is a stretch of at least 5 minutes with at least 4 boxes running; its total throughput counts as one observation.
- **Comparability.** Every sample records an estimate-basis fingerprint: ADR-0031's and ADR-0033's constants and settings, plus `DBX_MEMORY_TIER`. Only samples whose fingerprint matches the current basis are used. Others are kept but ignored. An upgrade that leaves the basis alone keeps history usable.
- **Minimum per parameter.** Below its minimum, a parameter falls back to the reference band (ADR-0034):
  - a and b: at least 5 samples, with the largest L at least 4 times the smallest.
  - large-record rate: at least 2 samples.
  - ceiling: at least 2 observations.
- **Range ends.** a and b are fitted by least squares over all samples. The slow end scales the fitted curve by the smallest observed ÷ fitted ratio among the samples, and the fast end by the largest. The large-record rate and the ceiling take their observed minimum and maximum. The replay stays deterministic, and no safety factor is added.

## Considered options

- **A provisional stage-2 estimate from `information_schema` stand-ins** (M from declared column widths, large-record guessed from LOB columns) was rejected. The number changes after preflight in a direction nobody can state in advance: a table wrongly treated as large-record streams faster but admits fewer boxes. That is the false precision ADR-0034 rejected. The change-board need is met by the persisted draft and a preflight run days ahead, since preflight never consumes the write-freeze window.
- **Only transfer bytes and 窗口下限 (minimum window) at stage 2** was rejected: 窗口下限 needs the same shape rate that needs preflight.
- **One history per deployment** was rejected: the source's read speed is the main error ADR-0034's fixed sentence names. A fast source's rates would then be applied to a slow one and labelled 可信 (reliable).
- **Discarding history on every release** was rejected: most releases do not change a rate-relevant constant.
- **Counting whole runs as samples** was rejected: the model fits single-stream rates, and a run holds many of those.
