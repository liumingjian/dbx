# frontend — v1 sub-spec

The operator console: renders `web`'s facts and sends commands; computes no estimate, conclusion, gate, or projection.

**Read first**: ADR-0020, 0016, 0017, 0029, 0025, 0026, 0030, 0021, 0039, 0024, 0023, 0019/0038/0034 (Operator wording), 0027, 0028, 0031 (Operator wording), 0022; #89 items 6–8; #46 checklist (scope ruler); #64 (withdrawn; corrected below). CONTEXT.md terms: all of Surfaces and Value vocabularies, plus every term named below.

## Surfaces

- Shell: a flat sidebar (迁移任务 / 数据源 / 系统设置) and a top bar with theme and the 运行状况 (runtime condition) indicator, which opens a per-item panel. A global banner shows on 受阻/无法判定 only. No language switch, login, dashboard, or search (ADR-0021 §Form; #89 item 6). `/tasks` lists tasks and drafts (ADR-0020).
- `/tasks/new/:draftId/:stage`: the five-stage migration wizard (迁移向导). Stage 4 has a per-table drawer URL (ADR-0020 §wizard).
- `/tasks/:taskId/runs/:runNo/:tab`: a task header, a run switcher (「第 N 次迁移运行」), and five run-scoped tabs 运行监控 / 校验报告 / 预检发现 / 日志 / 运行快照. The header's 整库结论 opens a per-table drawer. Table evidence opens as the drawer `…/:tab/tables/:unitId` (ADR-0020; #89 item 8).
- `/datasources`: connection list, form, and 连接校验 (#46 per-page).
- `/settings/general` and `/settings/about`: preferences; the release version (发行版本); installation 诊断包 (diagnostic package) export (#46; ADR-0035; ADR-0028).
- `/recovery`: the 恢复态 (recovery mode) surface, reachable only when `web` serves recovery mode. It replaces the shell entirely — no sidebar, no 运行状况 indicator, because both read H2 ([#97](https://github.com/liumingjian/dbx/issues/97); ADR-0006 §Recovery).

## Consumes

- The hand-written TypeScript contract in `src/contract/`, using glossary field names (ADR-0016 §Contract). `web` serves it.
- `web` endpoints under `/api`, one per `orchestration` use case (names per its sub-spec) or `workflow.api.query` read, at the paths in `src/api/*.ts`.
- `RunProgressSource` (run-scoped snapshots) and the installation-scoped status channel. Both poll every 10 s and refetch at once after every user command (ADR-0016 note; #89 item 7).

## Obligations

**A. Host and state**
1. `frontend/` holds the `dbx-prototype@55507fb` snapshot, cut to #46. B/Cut items deleted: no flags or hidden routes (#46 Q8; #64 Engineering shape).
2. Server state lives only in TanStack Query. zustand holds only theme and sidebar collapse (ADR-0016 §State split).
3. MSW and scenarios run only in dev and in an explicit demo build. Production has no mocks or external URLs (ADR-0016 §Build; ADR-0017 §Fonts).
4. `?scenario=` is the only scenario state. No component reads wall-clock time for domain facts. Views tolerate jumping or lagging progress (ADR-0016).
5. Every stage, tab, and drawer restores from its URL on refresh (ADR-0020).

**B. Vocabulary and conclusions**
6. Only zh-CN ships. Copy via message keys. Every enum value renders its `CONTEXT.md` `_中文_` wording (#89 item 6; ADR-0030).
7. No `_Avoid_` word, and none of box, connector, topic, broker, lag, Schema Registry, heap, 回滚, or 诊断分类阶段, appears in any screen (ADR-0030; ADR-0021; ADR-0031 §Operator wording).
8. `conclusion.ts` is the only mapping from a conclusion to its indicator. `ConclusionIndicator` always renders shape, colour, and label. Palette and kinds follow ADR-0029, including `accepted-risk`. 无法判定 is neutral grey with a question mark (ADR-0017; ADR-0029 §Palette).
9. Lint forbids antd `Tag color` and `Badge status` for conclusions, and forbids importing `Table` outside `DataTable` (ADR-0017; ADR-0025).
10. The Chinese typography layer applies under `:lang(zh)`, with a 32 px control floor. Token values live only in `src/theme/antd.ts` and `tokens.css` (ADR-0017).

**C. Tables and selection**
11. Scale mode (`virtual` plus `fixed:'left'`) appears only on the unit list, 校验报告, and 预检发现. Others paginate; no column resize, hiding, or density switch (ADR-0025).
12. Selection is a scope: explicit rows, or filter-all minus exclusions, with undo. A filter change freezes the scope, and no regex exists (ADR-0025 §Selection model).

**D. Wizard**
13. An unmet gate redirects to the first unmet stage. Credentials are never entered inline. A `public` target schema is refused (ADR-0020; #64 story 6).
14. Stage 3 groups findings as 阻塞 / 数据有损 / 仅行为差异. 无法判定 is a separate neutral group showing its reason. Blocking gates per table, and nothing offers 我已知晓 (ADR-0029).
15. 预估耗时 first appears at stage 3, with confidence, source, 窗口下限, and preflight time. A stale table replaces the number with 「预检已过期，重新预检后更新」. A 低置信 estimate shows ADR-0034's fixed sentence (ADR-0038; ADR-0034).
16. Stage 4 has an 例外数 column. Its drawer has the tabs 列 / DDL（只读）/ 补建 SQL, with table rename in the header. On 列, each column gets a type chosen only from the pair's allowed list, a live ≤63-byte unique name check, 恢复默认, and an origin tag. Each edit stales that table's preflight. The DDL switches are gone, replaced by one read-only line (#64 Gap 3; ADR-0026).
17. Stage 5 shows counts per impact and contract class, each opening its tables' drawers. It carries the required two-part freeze block, 整库冻结承诺 plus this run's 写冻结. The block holds the split proposal when the upper bound exceeds the limit, which warns but never blocks. 执行 stays disabled until the block is complete, then opens a restating second confirmation (ADR-0020; ADR-0024; ADR-0026; #64 Gap 2).

**E. Task console**
18. The task header holds: task status (进行中/废弃中/已废弃/部分废弃); a 整库结论 summary that opens a per-table drawer, with drift shown as 「无法判定 · 源端数据已变化」; 下载补建 SQL; and 废弃 in a danger menu, enabled only when no run is nonterminal (#89 item 8; ADR-0024; ADR-0026).
19. 废弃 shows the exportable 废弃清单 with a read tick, a separate tick for red rows, and the schema name typed in. An approved draft can export 预估废弃清单, labelled projected (ADR-0023).
20. 运行监控 shows the phase strip, 预计剩余 or 无法预估 with its reason, throughput, and live heap usage (wording: D-29). The binding limit reads 平台内存预算. Suspected-stuck marks sit on units only (ADR-0019; ADR-0031; ADR-0021).
21. A unit whose validation is `INCONCLUSIVE` shows phase 校验中 and a separate 校验 column with 重新校验 and 校验处置 (#64 Gap 1).
22. An open 准入已暂停 shows the run as 需要人工处理, with its reason and 「继续迁移」 beside 「取消运行」 (ADR-0039).
23. 取消 offers 收尾取消. When units will not finish before the freeze expires, the console states the choice: extend the freeze, or those units fail at expiry (ADR-0024).
24. 校验报告 keeps technical results, preflight exclusions, and dispositions in separate panes. After a disposition, the original 无法判定/未通过 stays visible beside 完成，已接受风险 (ADR-0029; #64 Gap 1).
25. 日志 and the evidence drawer show error cards in the order what / where / affected / one action. The 阶段 on a card is the unit 阶段 `workflow` recorded on the occurrence, read as a fact; the diagnosis classification phase is never shown or translated, a box- or run-scoped diagnosis (准入已暂停) shows at its own scope with no 阶段, and an `ENVIRONMENT_CHECK` diagnosis shows as its 环境自检 item (ADR-0030, [#96](https://github.com/liumingjian/dbx/issues/96)). Raw detail collapsed; an unknown diagnosis offers export. 结构证明 appears only on the unit (ADR-0005 §Operator presentation; ADR-0026; ADR-0028).
26. 运行快照 holds the frozen DDL per table, the environment check conclusions, and run package export (ADR-0026; ADR-0027; ADR-0028).
27. 重新迁移 creates a draft pre-scoped to failed, undetermined, cancelled-stopped, never-run, and drifted tables. The draft enters stage 3 (ADR-0024; ADR-0020).

**F. Shell, data sources, settings**
28. The condition panel lists the four items, the unmet environment check items as reasons, and DBX 待回收占用 grouped by run with an entry into 丢弃 (ADR-0021).
29. The connection form lists engines only from `supportedPairs`, with TLS 模式 and 连接校验, no JDBC parameters. Stage 4's type choices are the draft contracts' mapping alternatives (#46 Q1, Q16; ADR-0008).
30. 关于 shows the release version only, with the BOM collapsed. The manifest is shown before any package export (ADR-0035; ADR-0028).

**G. Recovery mode**
31. When `web` serves 恢复态, the app renders `/recovery` and nothing else: one zh-CN line stating the control plane is unavailable, the backup list (time, size, checksum state), 关于, and 诊断包 export. Any other route redirects here rather than erroring, because every other page reads H2 (#97; `web` obligations 29–30).
32. Choosing a backup requires one restating confirmation naming that backup's time; restore is destructive and unattended retry is refused. A restore that fails shows what failed — 主密钥不对或缺失 versus 该备份已不可用 — and those two lead to different actions, never one generic failure (#97; `connection` obligation 18).
33. 恢复态 is never presented as 回滚, and obligation 7's ban still holds. The DBA meets 恢复 and, where the release script is involved, 回退窗口 (ADR-0030; CONTEXT 恢复态, 回退窗口).

## Verification

- **L1 `pnpm check`**: typecheck, ESLint (covers 9), `format:check`, and these Vitest tests:
  - ported `contract`/`store`/`clock`/`scenarios` tests (1–5);
  - `conclusion.test.ts` and `ConclusionIndicator.test.tsx`, every member with three channels (8);
  - `vocabulary.test.ts`, the `_Avoid_` and platform-word ban over zh-CN messages (6–7);
  - `selection.test.ts` (12);
  - `scale.test.tsx`: at 1,200 rows fewer than 100 mount, unchanged at 2,400 (11);
  - a unit test per form rule (13, 16).
- **L2 `pnpm e2e`**:
  - `smoke`: every route × zh light/dark × key scenarios;
  - `overlap`: 1920×1080 and 1280×800 (10);
  - scale L2 (11);
  - `@gate`: the nine §15.4 journeys plus 14, 15, 17–19, 22, 23, each landing with its slice.
- `pnpm verify` = L1 + L2, the merge gate, run on the mac via `rexec`; no frontend L3 (ADR-0022; #64 Testing).

**Mock scenarios** (`?scenario=` floor): `success`, `partial-failure`, `stuck`, `cancelled`, `inconclusive`, `abandonment` (+ 部分废弃), `cross-window` (split, drift), `finishing-cancel`, `freeze-expiring`, `runtime-caution`/`-impeded`/`-inconclusive`, `env-check-unsatisfied` (startup; pre-admission 需要人工处理), `connect-restart` (1st 需留意, 2nd 准入已暂停), `admission-paused-stuck`, `memory-budget-binding`, `estimate-unavailable`, `preflight-stale`, `scale-1200` (ADR-0016; 0021; 0024; 0027; 0031; 0032; 0039).

## Slices

Mock-backed; no cross-module blocker. Slice 3 blocks `web` slices 2–6. Real-backend wiring is not a frontend slice: the production build calls `/api` once `web` serves it (ADR-0016 §Build), so no cycle forms.
1. **Host swap**: replace the Carbon shell with `dbx-prototype@55507fb` (hash in commit); add ESLint, Prettier, Vitest, Playwright, `pnpm check`/`e2e`/`verify`; trim `DESIGN.md`; `README.md` ≤40 lines.
2. **Scope cut** (←1): cuts per #46, the zh-CN-only switch, CONTEXT wording, `vocabulary.test.ts`.
3. **Data layer** (←1): port `contract/`, `api/`, `mocks/` from `feature/30` replacing `types.ts`/`mock/`; add fields for ADR-0019–0039 and paths and payloads for the surfaces the port predates (status channel, 继续迁移, abandonment, downloads, package export, supported pairs); state split, both seams, scenario floor.
4. **Conclusions and theme** (←1): port `conclusion.ts`/`ConclusionIndicator`, palette, lint bans, reroute `StatusTag`, typography.
5. **Tables** (←1): `DataTable` bans, `selection.ts`, scale L1/L2, and the 1,200 fixture.
6. **Shell and condition** (←2,3,4): indicator, panel, and banner; the routes of A5.
7. **Data sources and settings** (←3,4,5; D-27): obligations 29–30, settings, package manifest.
8. **Wizard 1–3** (←3,4,5): URL gating, scope tree, findings, estimate (13–15).
9. **Stage 4 drawer** (←8): obligation 16.
10. **Stage 5** (←9): obligation 17.
11. **Task list and 运行监控** (←3,4,5; D-28, D-29): run switcher, obligations 20–23.
12. **Run tabs and evidence** (←11): obligations 21, 24–26.
13. **Task header** (←11,10; D-28): obligations 18–19, 27.
14. **Recovery mode** (←3,4): obligations 31–33, the `/recovery` route and its mock scenario.

## Conflicts resolved

- #64 zh-CN + en-US, key parity, en overlap/smoke → zh-CN only, switch hidden, keys kept (#89 item 6).
- ADR-0017 two-locale layout; ADR-0020/#46 language in top bar and settings → v2; hidden (#89 item 6). Top bar gains 运行状况 (ADR-0021).
- ADR-0016 "declines to choose the mechanism" and `feature/30`'s `DEFAULT_POLL_INTERVAL_MS = 2_000` → 10 s plus refetch after a command (#89 item 7).
- #64 header set → #89 item 8's, drawer not a sixth tab (#89 item 8).
- #64 estimate "on the draft" from scope → first at stage 3 after preflight, stale-aware; 可信 means per-source history (ADR-0038).
- ADR-0020 re-migration "failed and undetermined" → adds never-run, 因运行取消而停止, and drifted tables (ADR-0024).
- ADR-0036 "the run stays running" on paused admission → 需要人工处理 with 「继续迁移」 (ADR-0039).
- ADR-0029 阻塞 including a structural-proof difference → structural proof is only the unit's 迁移失败 (#86; ADR-0026).

- Endpoints for new surfaces → slice 3 declares them in `src/api/*.ts` (ADR-0016 §Contract).
- The condition banner as the one global "something is wrong" surface vs an H2 that cannot answer → 恢复态 replaces the shell instead of banners inside it, because the shell itself reads H2 (#97).
- Mid-run 写冻结 extension and declared break → `orchestration`'s `extendFreeze`, `declareFreezeBroken`.

## Open items

- **D-27** (T8): where General preferences (timezone, confirm-dangerous, page size) are stored (ADR-0016). Blocks slice 7.
- **D-28** (T8): where 重新迁移, 复制为迁移草稿, and the remaining 整库冻结承诺 time sit (#89 item 8). Blocks slices 11, 13.
- **D-29** (T8): wording for live heap usage in 运行监控 (ADR-0031 §Operator wording). Blocks slice 11.
