# condition — v1 sub-spec

Pure fold that turns the gathered runtime condition (运行状况) items into the installation's condition value, its reasons, the admission stop signal, and the change to record.

**Read first**: ADR-0036 (`condition` row, §Dependencies and purity); ADR-0021; ADR-0032 (§The second restart…, §Wedges not caused by OOM); ADR-0039; ADR-0002 (disk gates); ADR-0027 (E2, E6, E7); ADR-0018 (§Enforcement, session rule); ADR-0022. CONTEXT.md terms: Runtime condition (运行状况), Runtime condition value (畅通, 需留意, 受阻, 无法判定), Admission paused (准入已暂停), Environment check (环境自检), Box (箱), Blocked by an upstream failure (因关联失败而阻塞), Unsatisfied (不满足).

## Interface (`condition.api`)

- `fold(input) → ConditionOutcome`: pure. No clock, no I/O, no persistence, no calls into another module (ADR-0036 §Dependencies and purity; ADR-0018 §Enforcement).
  - `input`: the four item readings: Kafka data-disk reading (E6) plus reclaimable occupancy (待回收占用) per migration run; reachability of Kafka, Connect, and Schema Registry (E2, and the Connect restart check); H2 directory writability (E7). It also carries the latest environment check's unmet items, per-run restart and zero-output stuck counters (counted by `orchestration` from `workflow`'s persisted restart occurrences and zero-output `STUCK` boxes, so they survive a DBX restart) with each run's open admission pause and prior continue, the previous `ConditionOutcome`, and the observation time passed in by the caller.
  - `ConditionOutcome`: the condition value; each item's value and reason; the whole-condition reasons with root-cause domain; a banner flag; the admission stop signal (installation-wide, plus the runs to pause, each with its reason); the reclaimable-occupancy aggregate grouped by run; and at most one change entry (when, from, to, why).
- Input and output types are `condition.api`'s own records. `orchestration` maps other modules' facts onto them (ADR-0036 §Dependencies and purity).

## Consumes

None. `orchestration` gathers the items from `connector.kafkaFacts`, `environment.sampleReadings`, and `workflow.api.query`, and then calls `fold` (ADR-0036 §Dependencies and purity).

## Obligations

**A. Purity and boundary**
1. `condition` depends on no `JdbcTemplate`, HTTP client, or clock, and references only other modules' `api` (ADR-0018 §Enforcement; ADR-0036 §Dependencies and purity).
2. `fold` is deterministic: equal inputs give equal outcomes, and every timestamp comes from the input (ADR-0018 §Enforcement).
3. `fold` persists nothing. It returns the change entry and the runs to pause, and the caller writes them through `workflow` (ADR-0036 `workflow` row; ADR-0039 §A durable run fact).

**B. Items and exclusions**
4. The input admits exactly four items: Kafka data-disk usage, reachability of Kafka, Connect, and Schema Registry, a writable H2 directory, and DBX-held space awaiting reclamation (ADR-0021 §Items and sources).
5. The input has no field for host memory, lag, offsets, throughput, connector or task state, the two-minute suspected-stuck warning, or source and target database reachability (ADR-0021 §Items and sources, §Table-scoped signals; ADR-0031 §Observation; ADR-0039 §One ten-minute budget).
6. Orphan `dbx-` connectors that no run owns never appear in the outcome (ADR-0021 §Space awaiting reclamation).

**C. Item values**
7. Disk usage ≥ 80% gives 需留意 with no banner (ADR-0021 §Gates are unchanged; ADR-0002).
8. Disk usage ≥ 90% or free space < 10 GB gives 受阻 with the banner (ADR-0021 §Gates are unchanged; ADR-0002).
9. Kafka, Connect, or Schema Registry unreachable gives 受阻 (ADR-0021 §Execution platform unreachable; ADR-0039 §One ten-minute budget).
10. When the platform becomes reachable again, the reachability item leaves 受阻 on the next fold with no other input (ADR-0021 §Execution platform unreachable).
8a. A non-writable H2 directory gives 受阻 with the banner (ADR-0021 §Form, §Values).
8b. An item is 无法判定 only when the disk reading has had no successful `describeLogDirs` sample for 60 s (six consecutive 10 s samples); until then the last good reading stands. Reachability and H2 are never 无法判定: a probe that does not answer is itself the answer (ADR-0021 §Values).
11. The disk item carries reclaimable occupancy as *DBX 待回收占用 X GB*, grouped by the runs that hold it, each group keyed by run id so the UI can link to 丢弃. It is display only: its bytes are already inside the E6 reading (ADR-0021 §Space awaiting reclamation; ADR-0002).

**D. Fold and admission stop**
12. The whole condition takes its worst item's value, in the order 畅通 < 需留意 < 受阻 / 无法判定 (ADR-0021 §Values; CONTEXT.md Runtime condition value).
13. The installation-wide stop signal is raised exactly when the value is 受阻 or 无法判定 for a reason other than an admission pause (ADR-0021 §Values; Conflicts 1).
14. An open admission pause makes the condition 受阻 with the pause's reason, and it stops admission for that run only (ADR-0039 §A durable run fact).
15. 受阻 caused by a pause clears on the first fold after the DBA continues (CONTEXT.md Runtime condition value; ADR-0039 §One resume path).
16. The banner flag is set only for 受阻 or 无法判定, and the outcome states the reason and who acts (ADR-0021 §Form).
17. The latest environment check's unmet items appear as reasons verbatim. `fold` never re-evaluates or rewrites them (ADR-0021 §Values).
17a. An unmet environment-check item raises the whole value: 不满足 gives 受阻, 无法判定 gives 无法判定. Only E0, E1, E3, E4, E5, E8 enter as whole-condition reasons; E2, E6, E7 are already items and are never counted twice (ADR-0021 §Values; ADR-0027 §Conclusions).
18. The condition interrupts nothing: the outcome contains no box failure, cancellation, or gate override (ADR-0021 §Gates are unchanged).

**E. Restart counters and pause**
19. A run's first Connect restart gives 需留意 with the reason *迁移平台发生过重启*, and that run's admission continues (ADR-0032 §The second restart…).
19a. That 需留意 projects the run's restart count, not a timed event: it holds while the run is nonterminal, goes once no nonterminal run holds a restart count, and never decays on a timer (ADR-0032 §The second restart…).
20. A run's second Connect restart gives 受阻 and requests a pause for that run with reason *迁移平台发生过重启* (ADR-0032 §The second restart…; ADR-0039 §One resume path).
21. Two consecutive boxes of a run reaching 卡死 (stuck) with zero records give 受阻 and request a pause with reason *迁移平台无法启动新的读取* (ADR-0032 §Wedges not caused by OOM; ADR-0039 §One resume path).
22. After a continue, a single further restart or a single further zero-output stuck box requests a pause again. Counters never reset (ADR-0039 §Once warned, one more strikes).
23. Counters are per run, and a pause never stops another run's admission (ADR-0039 §A durable run fact).
24. `fold` requests no pause for an already-open pause and has no pause timeout (ADR-0039 §No timeout of its own).

**F. Change entry and wording**
25. A change entry (time, from value, to value, reason) is emitted only when the whole value differs from the previous outcome's. It carries no metric samples (ADR-0021 §History).
26. Reason texts use only the domains 运行环境, 迁移平台, and DBX 自身, and never contain broker, topic, connector, task, lag, or Schema Registry (ADR-0021 §Audience and wording; ADR-0030).
26a. Each reason `condition` authors carries the domain and who-acts text of ADR-0021 §Form's six-row table: disk 运行环境, reachability 迁移平台, H2 运行环境, both pause reasons 迁移平台. 待回收占用 is no reason of its own; it rides on the disk row (ADR-0030).
26b. Environment-check reasons carry `environment`'s own domain and who-acts text through unchanged; `condition` authors neither (obligation 17).
27. Reason texts are zh-CN message keys (#89 item 6).

## Verification

All groups are pure and verified at L1 `check`. No L2 is required, because `condition` is no side-effect shell (ADR-0022 §Who runs which rung).

| Group | Rung | Test |
|---|---|---|
| A | L1 | ArchUnit purity and `api`-only rules (ADR-0018); `ConditionContractTest.determinism` |
| B | L1 | `ConditionContractTest.items`: compile-time shape of the input record, orphan exclusion |
| C | L1 | `ConditionContractTest.itemValues`: 79/80/89/90% and 10 GB boundaries; each platform service unreachable, then reachable again; H2 unwritable; the 60 s stale-sample boundary and that reachability/H2 never yield 无法判定; reclaimable grouping |
| D | L1 | `ConditionContractTest.foldAndStop`: worst-value table over all value pairs; stop signal; pause-scoped stop; banner; unmet items passed through unchanged, and each unmet conclusion's effect on the value with E2/E6/E7 excluded |
| E | L1 | `ConditionContractTest.restartCounters`: restart 1 and 2, including that the first 需留意 holds until the run is terminal; stuck 1 and 2 (zero-output and with output); pause, continue, one more strike; two runs isolated |
| F | L1 | `ConditionContractTest.changeEntry`; `ConditionReasonDomainTest`: every authored reason's domain and who-acts key against ADR-0021 §Form, and environment-sourced reasons passed through; `ConditionWordingTest`: scans every reason key's zh-CN text for forbidden words |

The end-to-end restart and unreachable paths are proven in `connector`'s and `orchestration`'s L3 scenarios, not here (ADR-0032 §Consequences).

## Slices

1. **`api` and `ConditionContractTest` skeleton**: input and outcome records, the `fold` signature, value ordering, worst-value fold, installation-wide stop signal, and determinism (A, 12, 13). README ≤ 40 lines. No blockers.
2. **Item values and exclusions**: disk, reachability, H2, reclaimable-occupancy aggregate, orphan exclusion, and unmet-item reasons (B, C, 17, 17a). Blocked by slice 1.
3. **Restart counters and pause signal**: 14–15 and 19–24. Blocked by slice 1.
4. **Change entry, banner, and wording**: 16, 18, and F. Blocked by slices 2 and 3.

`condition` has no cross-module blockers. Its consumers are `orchestration` (gathering and calling `fold`, writing pauses) and `workflow` (persisting the pause and the change record). Both wait for `condition` slice 1.

## Conflicts resolved

See [`conflicts.md`](conflicts.md#condition) — provenance only; every winning ruling is already an obligation above.

## Open items

None. D-14, D-15, and D-16 are settled in [#95](https://github.com/liumingjian/dbx/issues/95) and recorded in ADR-0021 and ADR-0032.
