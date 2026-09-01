# Frontend contract, mock boundary, and run progress transport

DBX is built frontend-first against mock data. This decision fixes where the frontend stops and the backend begins, and it answers the transport question ADR-0007 deliberately left open.

## Contract

The boundary is a hand-written TypeScript contract. DBX does not author an OpenAPI document for a backend that has not been built: doing so would let the frontend fix server-side architecture by implication. Contract field names are taken from the glossary — `migrationTask`, `migrationRun`, `tableMigrationUnit`, `preflight`, `tableWriteContract`, `validationExecution`, `validationDisposition` — so the backend inherits the domain language rather than a second, parallel vocabulary.

## Mock boundary

Mocks intercept HTTP through MSW and are backed by a stateful in-memory store. Fixture constants embedded in components were rejected because they cannot express the dimension that matters most here: a run starts, per-table progress advances, some tables fail, and validation concludes `PASS`, `FAIL`, or `INCONCLUSIVE`. A separate mock server was rejected as a runtime dependency with no additional benefit.

The mock clock is controllable, with an adjustable rate and a scenario selected by URL parameter. Scenarios cover at minimum full success, partial table failure, a stuck table, operator cancellation, and an inconclusive validation. This is delivery infrastructure rather than convenience: without it, failure states cannot be reviewed and a multi-hour migration cannot be demonstrated.

## Progress transport

The frontend depends only on a `RunProgressSource` abstraction. The mock drives it from the controllable clock; the default real implementation polls; server-sent events or WebSocket remain substitutable behind the same interface. ADR-0007 left polling versus SSE versus WebSocket undecided and forbade inferring it from the prototype, so the frontend defines the seam and declines to choose the mechanism.

Per ADR-0004, progress observations are the only asynchronous writes and may be coalesced. The interface and every view built on it therefore assume progress can jump and can lag, and must not render as though advance were smooth or monotonic in time.

## Consequences

Replacing mocks with a real backend is a data-layer change, not a rewrite, provided no view reaches around the contract types or the `RunProgressSource` seam. If the backend later adopts OpenAPI, the hand-written types become the input to that document rather than a competing definition.

## Rejected alternatives

- **Pick WebSocket or polling as a product decision now** — contradicts the explicit gap in ADR-0007.
- **Write OpenAPI first and generate types** — freezes an undecided backend concern inside the frontend.
- **In-component fixture constants** — cannot express a run over time, so the prototype could not reach the required completeness.
