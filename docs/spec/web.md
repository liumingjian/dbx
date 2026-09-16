# web — v1 sub-spec

HTTP adapters: serve the hand-written frontend contract, the two 10 s progress channels, and the local API that the upgrade script calls. Reads go through `workflow.api.query`; writes go only through `orchestration` use cases.

**Read first**: ADR-0036 (web row) → ADR-0018 (enforcement, dependency direction, session rule) → ADR-0016 → ADR-0021 §Consequences → ADR-0039 → ADR-0035 §In-place upgrade → ADR-0028 §Trigger/§Audit → ADR-0026 §Delivery → ADR-0023 → ADR-0020 §URLs and gates → ADR-0022; #89 items 5, 6, 7, 8. CONTEXT.md terms: Migration task, Migration draft, Migration run, Migration run status, Migration task status, Table migration unit, Run snapshot, Task conclusion, Runtime condition, Environment check, Admission paused, Release version, Rollback window, Supplemental SQL, Abandonment list, Diagnostic package, Re-migration, Cancellation, Discard, System settings.

## Interface (`web.api`)

None. `web` is the top of the dependency graph, so no module may depend on it (ADR-0036 web row "—"; ADR-0018 §Dependency direction). Its only surface is HTTP under `/api`, and it serves exactly what the TypeScript contract in `frontend/src/contract/*.ts` declares, using the URL paths in `frontend/src/api/*.ts` and the error body `{error:{code}}` from `frontend/src/api/http.ts` (ADR-0016 §Contract). Those files are the source of truth for the wire format. This spec does not restate them.

## Consumes

- `workflow.api.query`: every read (progress, timelines, table migration units, task list, drafts, run projection) (ADR-0018 §Dependency direction; ADR-0036 workflow row "Unchanged").
- `orchestration.api` (names per the `orchestration` sub-spec), for every write and assembled artefact: `saveConnection`, `checkConnection`, `supportedPairs`, `saveDraft`, `discardDraft`, `runPreflight`, `contractRendering`, `execute`, `cancelRun`, `extendFreeze`, `declareFreezeBroken`, `continueAdmission`, `adoptCredential`, `recordDisposition`, `runSampling`, `discardRun`, `remigrate`, `copyAsDraft`, `projectedAbandonmentList`, `abandonmentList`, `abandonTask`, `retryAbandon`, `downloadSupplementalSql`, `packageManifest`, `exportPackage`, `recheckEnvironment`, and `latestCondition` — the one read that goes through `orchestration` rather than `workflow.api.query`, because the latest condition outcome is never persisted (ADR-0021 §Consequences). Pure modules (`contract.renderDdl`, `projectedList`) are reached only through these (ADR-0018 §Dependency direction).
- Never `workflow.api.command`, and never any deep module's side effects (ADR-0018 §Enforcement; ADR-0036 §Dependencies and purity).

## Obligations

**A. Boundary**
1. `web` references only `workflow.api.query` and `orchestration.api`. It never references `workflow.api.command` or any non-`api` package (ADR-0018 §Enforcement).
2. Every write endpoint maps 1:1 to one `orchestration` use case and holds no domain logic (ADR-0018 §Dependency direction).
3. The module README is at most 40 lines and follows ADR-0018's list (ADR-0018 §Module context).

**B. Wire contract**
4. Every path in `frontend/src/api/*.ts` has a handler, and every response body deserializes into the matching `frontend/src/contract/*.ts` type, with glossary field names unchanged (ADR-0016 §Contract).
5. A refusal returns a non-2xx status with body `{error:{code}}` (`frontend/src/api/http.ts`).
6. User-facing text in responses is zh-CN only, while message keys stay (#89 item 6).
7. No authentication in v1 (technical plan §10).
8. The production bundle served by the platform contains no mocks (ADR-0016 §On the prototype host, Build).

**C. Progress channels**
9. `GET /api/migration-runs/{runId}/progress` returns the run's latest `RunProgressSnapshot` from the coalesced H2 progress, and never triggers an external call (ADR-0016 §Progress transport; ADR-0004 flush cadence).
10. Snapshots may lag or jump. `web` never interpolates or smooths them (ADR-0016 §Progress transport).
11. An installation-scoped status endpoint returns the runtime condition's value, its per-item values, and the open reasons with their root-cause domain and who-acts text, admission-paused reasons included. It serves `orchestration.latestCondition()` and, like the progress channel, triggers no external call. Before the first fold after startup it answers 无法判定 with the reason *尚未取得读数* (ADR-0021 §Form, §Consequences; ADR-0039 bullet 1).
12. Both channels are designed for a 10 s poll plus one immediate refetch after a user command. The endpoints stay stateless per request, so SSE can later replace polling behind the same seam (#89 item 7).
13. While the environment check concludes 不满足, `web` still serves the UI, the status endpoint, and diagnostic export. Only the start of migrations is refused, and `orchestration` does the refusing (ADR-0027 §Consequence of failure).

**D. Task and run commands** (each forwards to one `orchestration` use case)
14. Draft CRUD, plus a stage-gate read that yields the first unmet stage of `/tasks/new/:draftId/:stage` (ADR-0020 §URLs and gates).
15. 执行 creates a run from a draft, and only after the write-freeze block is present (ADR-0020 stage 5).
16. Re-migration returns a new draft pre-scoped per ADR-0020 and ADR-0024 (ADR-0020 §重新迁移; ADR-0024 §Mechanism).
17. Cancellation, including 收尾取消 (finishing cancellation), plus a pre-commit consequences read (ADR-0024 §Stopping; `frontend/src/api/runProgress.ts`).
18. 继续迁移 on a run with open admission paused calls `continueAdmission` (继续准入) (ADR-0039 bullet 3).
19. Discard is a separate command, accepted only after execution has stopped (ADR-0006 §Discard).
20. Abandonment is task-scoped and accepted only when the task has no nonterminal run. It requires the typed schema name, and a retry continues a partially abandoned task (ADR-0023; #89 item 8).
21. The abandonment list and the projected abandonment list are exportable reads (ADR-0023 bullets 2–3).
22. Connection check, preflight, manual sampling, disposition, and task-write-freeze reconfirmation are commands (ADR-0036 orchestration row; ADR-0024).

**E. Downloads**
23. 下载补建 SQL returns one task-level `.sql` file assembled by `orchestration`, streamed and never written to server disk (ADR-0026 §Delivery, §Task-level).
24. Diagnostic package export has an installation scope (系统设置) and a run scope (运行快照), is available at any time, shows the manifest before export, and returns at most 50 MB. Each export is audited by `orchestration` (ADR-0028 §Trigger, §Manifest, §Bound, §Audit).

**F. Installation and upgrade**
25. A local nonterminal-run query lists every migration run that is not terminal, with enough identity for the script to name each one in Chinese, and it answers without admission running (ADR-0035 §In-place upgrade step 2).
26. A read of the installation record (release version, key fingerprint, rollback-window state) for the upgrade and rollback scripts (#89 item 5; ADR-0035 §Failed upgrade).
27. 关于 shows the release version only, with the bill of materials collapsed (ADR-0035 §Release version).

## Verification

- A (1–3): L1 `check`, ArchUnit rules (ADR-0018) and the README-limit test.
- B, D, E (4–8, 14–24): L1 `WebContractTest`. It covers one case per `frontend/src/api/*.ts` path against a stubbed `orchestration.api` and `workflow.api.query`, round-trips a JSON fixture of each contract type, and asserts that each write calls exactly one use case. `WebDownloadContractTest` covers the streamed SQL, the 50 MB bound, and the order in which the manifest comes before the package.
- C (9–13): L1 `WebProgressContractTest`, which covers stateless reads, no external calls, and serving while the environment check is unsatisfied. L3 `e2eTest`: a run whose progress advances while polled at 10 s.
- F (25–27): L1 `WebInstallationContractTest`. L4 `packageTest`: the upgrade is refused with one nonterminal run and proceeds with none (ADR-0035 §Verification).

## Slices

1. **Skeleton**: the `web.api` placeholder package, the README, `WebContractTest` with all contract paths marked pending, the `/api` prefix and error body, ArchUnit green. No blockers.
2. **Read endpoints**: task list and drafts, task detail with task header, run projection, units, timelines, validation report, preflight findings, run snapshot, task conclusion (4, 9–10, 14 read). Blocked by `workflow` slice 1, `frontend` slice 3.
3. **Status channel and installation**: condition status, installation record, nonterminal-run query, 关于 (11–13, 25–27). Blocked by `workflow` slice 1, `frontend` slice 3, `orchestration` slice 6.
4. **Draft and run commands** (14–18, 22). Blocked by `orchestration` slice 1, `frontend` slice 3.
5. **Destructive commands**: discard, abandonment, retry, abandonment lists (19–21). Blocked by `orchestration` slice 1, `frontend` slice 3.
6. **Downloads**: supplemental SQL and diagnostic package (23–24). Blocked by `orchestration` slice 1, `frontend` slice 3.

Each slice depends on slice 1; slices 2–6 are otherwise independent. Provider slice 1s suffice because every L1 test runs against stubbed `orchestration.api` and `workflow.api.query`; `frontend` depends on no `web` slice, so no cycle forms.

## Conflicts resolved

- ADR-0016 "the default real implementation polls" with no cadence, and the `feature/30`/`impl/*` branches' `DEFAULT_POLL_INTERVAL_MS = 2_000` → 10 s plus a refetch after a command (#89 item 7; ADR-0016 amendment note).
- ADR-0018 web row, with only HTTP adapters → adds the status channel and the nonterminal-run query (ADR-0036 web row).
- ADR-0036 "Admission paused … the run stays running" → the run projects `ATTENTION_REQUIRED` while the pause is open (ADR-0039).
- ADR-0005 and ADR-0017/0020 two-locale text → zh-CN only in v1 (#89 item 6).
- Brief: "the contract lives in `frontend/` on main" → `origin/main` has no `frontend/src/contract/`. The contract exists on `origin/feature/30-frontend-module` and `origin/impl/*`, and ADR-0016 §On the prototype host ports it in `frontend` slice 3.

- The TS contract is missing on `main` → `frontend` slice 3 ports it and declares every path and payload, including the surfaces it predated (status channel, installation record, nonterminal runs, 继续迁移, abandonment, downloads, export) (ADR-0016 §Contract).
- `web` calling pure `contract` entries directly → only through `orchestration` use cases (ADR-0018 draws only `web → orchestration` and `workflow.api.query`).

## Implementer decides

- Listen address and port of the local API for `dbx upgrade`/`rollback`: reachable by the script on the host, no auth in v1, answering without admission running (TP §10; ADR-0035 §In-place upgrade).

## Open items

None. D-18 is settled in [#95](https://github.com/liumingjian/dbx/issues/95): the channel serves `orchestration.latestCondition()`.
