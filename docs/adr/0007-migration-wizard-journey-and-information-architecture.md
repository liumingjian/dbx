# Migration wizard journey and information architecture

DBX v1 uses a linear migration wizard as the primary operator journey. The intended operator is a DBA who may not write application code, so the product must expose migration scope, safety evidence, and required decisions without exposing execution-platform internals.

This decision was validated by the throwaway prototype for issue #21. The prototype compared a linear wizard, an exception-first workbench, and a compressed three-decision journey. Variant A, refined through human feedback, is the accepted direction. The prototype remains evidence rather than production code.

## Guided journey

The migration wizard has six ordered stages:

1. **Connections and database** — select existing verified source and target database connections, one source MySQL database, and one target PostgreSQL schema. Connection creation, credentials, and endpoint maintenance remain in the separate data-source management surface; the wizard does not accept inline credentials or arbitrary JDBC settings.
2. **Migration scope** — select source tables with search, select-all, individual selection, explicit exclusion, and deterministic sorting. V1 does not provide regular-expression selection. Each table shows its current condition and has an identifiable configuration surface.
3. **Per-table configuration and preflight** — automatic field mapping is the default. The operator reviews only structured mapping exceptions, mandatory exact preflight evidence, and the generated table write contract for each table. Blocking or inconclusive preflight findings cannot be acknowledged away: the operator must correct the source, prune an offending selected field and rerun preflight, or explicitly exclude the table.
4. **Execution confirmation** — summarize the source, target, selected tables, exclusions, generated contracts, and unresolved findings. Starting requires an accountable, time-bounded source write-freeze confirmation and creates an immutable migration run snapshot.
5. **Run monitoring** — show phase, progress, technical result, update time, and timeline for every included table migration unit. Boxes, connectors, topics, and resource controls remain internal scheduling details rather than operator-facing business entities.
6. **Validation report** — report technical `PASS`, `FAIL`, and `INCONCLUSIVE` conclusions separately from preflight exclusions and validation disposition. Accepting risk never changes the technical conclusion to `PASS`. Re-migrating eligible failed or inconclusive tables creates a new migration run with fresh connection checks, preflight, write freeze, source baseline, and contracts; it does not retry or resume the old run in place.

The stages are a safety sequence, not merely navigation. Forward movement is gated by scope selection, supported preflight conclusions and generated contracts, and the write-freeze confirmation. Operators may return to completed stages, but cannot use progress navigation to bypass a gate.

## Information architecture

The wizard sits inside a persistent product shell that distinguishes task work, data-source management, and system settings. The wizard itself uses one progress indicator rather than duplicate horizontal and vertical stage navigation.

The table is the durable observable and review unit throughout the journey. The scope view may be dense enough for a production database, while the per-table workspace reveals field mappings, structured exceptions, preflight evidence, and the read-only contract on demand. This keeps the common path automatic without hiding the evidence behind risky or unsupported tables.

DDL is the complete read-only rendering of the approved table write contract. It is not an independent setting or SQL editor. Mapping changes use bounded structured controls, regenerate the contract and DDL, and require the established approval and PostgreSQL zero-difference structural introspection before Sink starts. The wizard performs no probe write against a production target table.

The production selector must remain usable for large schemas through search, explicit scope controls, exclusions, and sorting. The smaller fixtures in the refined prototype exist only to make every example table and configuration state inspectable; they do not reduce the production scale requirement.

## Prototype evidence

The evidence is preserved on `prototype/migration-wizard-journey` and in:

- `prototype/migration-wizard/index.html`
- `prototype/migration-wizard/README.md`

The prototype is static HTML, CSS, and JavaScript. Its state changes are in memory; it performs no authentication, database connection, SQL execution, persistence, or live progress transport. Its URL-stable A/B/C switch preserves design provenance and is not part of the production information architecture.

Variant B, the exception-first workbench, and Variant C, the three-decision journey, remain comparison concepts in the prototype. They are not the selected v1 operator flow.

## Consequences

The linear journey makes the ordinary safe path discoverable while promoting exceptions at the stage where they must be resolved. It aligns UI gates with the existing domain decisions for connections, exact preflight, table write contracts, immutable runs, table migration units, write freeze, and validation disposition.

The chosen flow requires more navigation than the compressed journey and offers less immediate exception density than the workbench. That is an intentional tradeoff for a non-developer DBA: the product reveals detailed evidence progressively without requiring prior knowledge of the execution architecture.

This decision resolves the migration-wizard journey and its information architecture. It does not decide the organization of non-wizard task and administration pages, authentication and multi-user permissions, live update transport such as polling/SSE/WebSocket, reusable cross-task mapping templates, or future database-pair extension work.

## Rejected alternatives

- **Exception-first workbench as the primary flow** was rejected because it assumes an experienced operator and makes the ordinary safe path harder to discover. Exception-first presentation remains useful inside preflight and validation details.
- **Three-decision compression as the primary flow** was rejected because it hides required evidence and safety checkpoints behind overly broad decisions.
- **Duplicate stage navigation** was rejected because the product shell and one wizard progress indicator provide sufficient orientation.
- **Inline connection creation or credential entry** was rejected because database connections have a separate versioned lifecycle.
- **Arbitrary DDL editing** was rejected because it can diverge from source extraction and bypass the approved table write contract.
- **Regular-expression bulk selection or mapping** was rejected because explicit deterministic exceptions are safer and more understandable for the v1 audience.
