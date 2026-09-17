# dialect

Source dialect, target dialect, the directed database pair, the pair-owned `TypeMapper`, and immutable typed SQL plans; never opens connections.

Pure and at the bottom of the graph: depends on no other module, no `JdbcTemplate`, no HTTP client, no clock. It returns facts, decisions and plans; it never advances workflow, creates or approves a contract, or overrides a gate.

## Entry points (`com.dbx.dialect.api`)

- `DialectCatalog.compileTime()` → `select` (`DatabasePair | Unsupported`: exact product and release series, no fallback, endpoints never compose a pair), `list`
- `DatabasePair` → `map` (`Supported | Unsupported`), `mapIdentifier` (`Exact | Renamed | Unsupported`), `descriptorCodec`, `executionRequirements`, `validationCapabilities`, `source()`, `target()`
- `SourceDialect` (its only constructor demands a `BoundedReadRequirement`, ADR-0033), `TargetDialect` → the plan, normalisation and settings capabilities of the sub-spec §Interface
- `DescriptorCodec` → reads and writes only its own `DescriptorVersion`; an unknown version is `Unsupported`
- `SqlPlan` → immutable, closed `OperationKind`, bound `SqlValue`s, `ResultSchema`, `TimeoutClass`, `RequiredPrivilege`s, `EvidencePolicy`, `fingerprint()`
- `TargetIdentifier.quoted()` → the one place an identifier becomes SQL text (always double-quoted)
- `ProofOutcome` → `PROVEN | INCONCLUSIVE | REJECTED`

An entry point its slice has not landed throws `NotImplementedInSlice` naming itself and the slice; it never returns an empty value.

## Layout

- `api/` — every public type, one file per type
- `catalog/` — the compile-time catalog: endpoint release series and certified pairs, registered separately
- `pair/` — `MySql80ToPostgres15` composes one class per capability: `PairRegistration` + `DescriptorCodecV1` (2), `TypeMapper` (3), `IdentifierMapper` (4), `PairRequirements` (9)
- `mysql/`, `postgres/` — the two endpoint dialects (slices 5–6, 7–8)

## Contract test

`DialectContractTest` (stubs, closed results, plan shape, fingerprint, hostile names and values, module purity); `DialectCatalogContractTest` (selection, refusals, `list`, codecs, bounded-read precondition); `IdentifierMappingContractTest` (byte-counted limit, rename, quoting). Later slices add `SourceDialectContractTest`, `TargetDialectContractTest`, `PairContractTest`.

## Read

- Sub-spec: [docs/spec/dialect.md](/docs/spec/dialect.md)
- ADRs: [ADR-0003](/docs/adr/0003-preflight-gated-large-record-envelope.md), [ADR-0008](/docs/adr/0008-database-dialect-and-pair-extension-seam.md), [ADR-0011](/docs/adr/0011-platform-owned-ddl-and-table-write-contract.md), [ADR-0018](/docs/adr/0018-backend-module-boundaries-and-agent-working-surface.md), [ADR-0022](/docs/adr/0022-verification-ladder-and-explicit-golden-updates.md), [ADR-0033](/docs/adr/0033-bounded-source-reads-cursor-fetch-and-keyset-chunks.md), [ADR-0036](/docs/adr/0036-module-table-owns-every-v1-obligation.md), [ADR-0037](/docs/adr/0037-bulk-source-reads-keyset-column-and-64-mib-cap.md)
