# Frontend contract, mock boundary, and run progress transport

> Amended by [ADR-0021](0021-runtime-condition-observes-and-never-adjudicates.md): an installation-scoped status channel sits beside `RunProgressSource`. Per [#89](https://github.com/liumingjian/dbx/issues/89), both default implementations poll every 10 s and refetch at once after a user command; SSE stays substitutable behind the seam.

DBX is built frontend-first against mock data. This decision fixes where the frontend stops and the backend begins, and it answers the transport question ADR-0007 deliberately left open.

Amended in [#50](https://github.com/liumingjian/dbx/issues/50): #45 replaced the frontend host with `dbx-prototype` (Ant Design 5, ADR-0017; journey and IA per ADR-0020). All three conclusions below survive the change of host unchanged; they are independent of the UI library. The section "On the prototype host" records how they land there.

## Contract

The boundary is a hand-written TypeScript contract. DBX does not author an OpenAPI document for a backend that has not been built: doing so would let the frontend fix server-side architecture by implication. Contract field names are taken from the glossary — `migrationTask`, `migrationRun`, `tableMigrationUnit`, `preflight`, `tableWriteContract`, `validationExecution`, `validationDisposition` — so the backend inherits the domain language rather than a second, parallel vocabulary.

## Mock boundary

Mocks intercept HTTP through MSW and are backed by a stateful in-memory store. Fixture constants embedded in components were rejected because they cannot express the dimension that matters most here: a run starts, per-table progress advances, some tables fail, and validation concludes `PASS`, `FAIL`, or `INCONCLUSIVE`. A separate mock server was rejected as a runtime dependency with no additional benefit.

The mock clock is controllable, with an adjustable rate and a scenario selected by URL parameter. Scenarios cover at minimum full success, partial table failure, a stuck table, operator cancellation, and an inconclusive validation. This is delivery infrastructure rather than convenience: without it, failure states cannot be reviewed and a multi-hour migration cannot be demonstrated.

## Progress transport

The frontend depends only on a `RunProgressSource` abstraction: a subscription that delivers snapshots. The mock drives it from the controllable clock; the default real implementation polls; server-sent events or WebSocket remain substitutable behind the same interface. ADR-0007 left polling versus SSE versus WebSocket undecided, and ADR-0020 did not reopen it, so the frontend defines the seam and declines to choose the mechanism.

Per ADR-0004, progress observations are the only asynchronous writes and may be coalesced. The interface and every view built on it therefore assume progress can jump and can lag, and must not render as though advance were smooth or monotonic in time.

## On the prototype host

- **Skeleton.** The contract, API and mock layers of `feature/30-frontend-module` (`contract/`, `api/`, `mocks/`) are ported into the prototype host. They are the skeleton; the prototype's own domain types (`src/types.ts`) and static mocks (`src/mock/`) are deleted, and its pages are rewired to consume contract types. The prototype's data shapes are not aligned by renaming: #46 ruled that `CONTEXT.md` wins, and the ported contract already follows it. The port also adds the fields the contract predates: estimates (ADR-0019), runtime condition (ADR-0021), task lifecycle and abandonment (ADR-0023), and the task write-freeze commitment (ADR-0024). Mocks for features #46 cut or deferred to the B stage are not ported.
- **State split.** TanStack Query owns all server state, reached only through the contract and MSW. zustand owns pure client preferences only: locale, theme, sidebar collapse. zustand never caches server data. Amended in [#99](https://github.com/liumingjian/dbx/issues/99): zustand holds only what never needs to leave the browser — theme and sidebar collapse, locale having gone with the zh-CN-only ruling (#89 item 6). The General preferences of 系统设置 — 时区, 危险操作二次确认, 每页条数 — are installation-scoped product configuration, not browser state: they live on the H2 installation record (#89 item 5) and reach the console as server state through the contract. `CONTEXT.md` defines 系统设置 as product-wide configuration; a timezone that differs per browser makes two DBAs read the same run evidence differently, and a confirmation policy a cleared browser store can silently drop is not a policy. The prototype's license, session and mock-bar state goes with the B-stage cut.
- **Time.** The prototype's fixed `NOW` is replaced by the controllable clock and its fixed anchor; no component reads wall-clock time for domain facts. The ported scenario set is pruned of scenarios that depend on cut features, and gains one for task abandonment (ADR-0023) and one for cross-window batches (ADR-0024). The five run scenarios above remain the floor.
- **Progress.** The run monitoring (运行监控) view reads `RunProgressSource`; the prototype's static monitor charts are cut except throughput, whose shape ADR-0019 fixes.
- **Scenario entry.** `?scenario=` is the only source of truth, so a review link lands in its state on first paint. A development-only scenario bar may pick a scenario, but it only rewrites the URL parameter and holds no state of its own. The prototype's license mock bar is deleted.
- **Build.** MSW and scenarios are enabled only in development and an explicit demo build; the production bundle served by the platform contains no mocks.

## Consequences

Replacing mocks with a real backend is a data-layer change, not a rewrite, provided no view reaches around the contract types or the `RunProgressSource` seam. If the backend later adopts OpenAPI, the hand-written types become the input to that document rather than a competing definition.

## Rejected alternatives

- **Pick WebSocket or polling as a product decision now** — contradicts the explicit gap in ADR-0007.
- **Write OpenAPI first and generate types** — freezes an undecided backend concern inside the frontend.
- **In-component fixture constants** — cannot express a run over time, so the prototype could not reach the required completeness.
- **Keep the prototype's types and static mocks as the skeleton and rename toward the glossary** (#50) — redoes alignment the ported contract already has, and still lacks a time dimension.
- **Keep server data in zustand** (#50) — a second cache beside TanStack Query, with no invalidation story once a real backend arrives.
- **Keep the prototype's mock bar as scenario state** (#50) — state outside the URL cannot be shared as a review link.
