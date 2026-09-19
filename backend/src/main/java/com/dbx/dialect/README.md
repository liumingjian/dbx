# dialect

Source dialect, target dialect, the directed database pair, the pair-owned `TypeMapper`, and immutable typed SQL plans; never opens connections.

Pure and at the bottom of the graph: depends on no other module, no `JdbcTemplate`, no HTTP client, no clock. It returns facts, decisions and plans; it never advances workflow, creates or approves a contract, or overrides a gate.

## Entry points (`com.dbx.dialect.api`)

- `DialectCatalog.compileTime()` → `select` (`DatabasePair | Unsupported`: exact product and release series, no fallback, endpoints never compose a pair), `list`
- `DatabasePair` → `map` (`Supported | Unsupported`), `mapIdentifier` (`Exact | Renamed | Unsupported`; a mapping rule is refused, TP §7.1), `descriptorCodec`, `executionRequirements`, `validationCapabilities`, `source()`, `target()`
- `SourceDialect` (its only constructor demands a `BoundedReadRequirement`, ADR-0033), `TargetDialect` → the plan, normalisation and settings capabilities of the sub-spec §Interface
- `DescriptorCodec` → reads and writes only its own `DescriptorVersion`; an unknown version is `Unsupported`
- `SqlPlan` → immutable, closed `OperationKind`, bound `SqlValue`s, `ResultSchema`, `TimeoutClass`, `RequiredPrivilege`s, `EvidencePolicy`, `fingerprint()`
- `TargetIdentifier.quoted()` → double-quoted text; PostgreSQL SQL takes a name only through `postgres.PostgresIdentifier.quoted`, which first refuses a name over 63 bytes (the one byte limit, shared with `IdentifierMapper`). MySQL SQL takes one only through package-private `mysql.MySqlIdentifier` (backticks)
- `postgres.PostgresLiteral` → the single literal renderer, for DDL `DEFAULT`/`CHECK` and supplemental SQL only (ADR-0008 §Plans as amended by #123); every other value is bound
- `ConnectionSemantics`, `SinkSettings` (the single `V1`) → ordered `ConnectorProperty` records, never a map; `BoundedReadRequirement` → M-independent constants only (`connector.deriveBox` applies M)
- `ProofOutcome` → `PROVEN | INCONCLUSIVE | REJECTED`

An entry point its slice has not landed throws `NotImplementedInSlice` naming itself and the slice; it never returns an empty value.

## Layout

- `api/` — every public type, one file per type
- `catalog/` — the compile-time catalog: endpoint release series and certified pairs, registered separately
- `pair/` — `MySql80ToPostgres15` composes one class per capability: `PairRegistration` + `DescriptorCodecV1` (2), `TypeMapper` (3), `IdentifierMapper` (4), `PairRequirements` (9)
  - `TypeMapper` dispatches on the sealed `MySqlDataType` (one enum per TP §6 family) to `NumericMapping`, `CharacterBinarySpecialMapping`, `TemporalMapping`; shared fact readings live in `SourceFacts`
- `mysql/`, `postgres/` — the two endpoint dialects (slices 5–6, 7–8); `mysql.QueryProjection.columnExpression` is the one per-column read expression (projection and slice 6 envelope scan)
  - neither imports the other (ADR-0008): supplemental comments quote source definitions from `postgres.SourceDefinitions`, which the pair wires to `mysql.MySqlDefinitions`

## Contract test

`DialectContractTest` (stubs, closed results, plan shape, fingerprint, hostile names and values, module purity); `DialectCatalogContractTest` (selection, refusals, `list`, codecs, bounded-read precondition); `IdentifierMappingContractTest` (byte-counted limit, rename, quoting); `NumericMappingContractTest`, `CharacterBinarySpecialMappingContractTest`, `TemporalMappingContractTest`, `TypeMappingExhaustiveTest` (fixture `src/test/resources/dialect/`), `TypeMappingPropertyTest`, golden set `type-mapping-matrix`; `ConnectionSemanticsContractTest`, `BoundedReadContractTest`, `SinkSettingsContractTest`; `SourceCapabilityPlansContractTest` (read-only capability plans, MySQL quoting); `SourceMetadataContractTest` (metadata plan text, fresh statistics, bound names, normalisation); `KeysetCandidatesContractTest` (ADR-0037 §Choice order and exclusions); `PreflightScanContractTest` (one aggregate query per table: envelope, type-domain counts, keyset extrema, labels, `decimal(20,0)`); `BaselinePlanContractTest` (`baselinePlan`: exact `COUNT(*)`, keyset extrema rendered by the preflight scan's own and declared `decimal(20,0)`, the count alone without a keyset column, grading left to `validation`); `SamplingContractTest` (`samplingPlan`: both TP §9.3 strategies, `STATEMENT_ONLY` evidence, thresholds bound never computed); `SourceDialectContractTest` (`queryProjection*`: prune, rename, quoting, rule agreement); `TargetDialectContractTest` (`ddlPlan`: plan shape, exact DDL, fingerprints, hostile names and literals, forbidden tokens, inconsistent tables); `SupplementalStatementsContractTest` (supplemental SQL text and order, reasons, hostile names, foreign keys last); `CapabilityProbeContractTest` (probe order and shape, identifier confinement to the probe's own two names, privilege subset, least-privilege text over the power set); `TargetCatalogContractTest` (`catalogReadPlan`/`normalizeCatalog`: TP §7.4 facts, coordinates bound never quoted, unnameable catalog types kept as text, broken reads refused); `MaintenancePlansContractTest` (`maintenancePlans`: guarded drops by OID, explicit `RESTRICT`, no `CASCADE`, bound `setval`, closed exact-object grants); `PairContractTest` (`executionRequirements`: ADR-0009 policy constants no caller can contradict; `validationCapabilities`: obligation 23a, byte-length comparability per type family and character set); `ValidationFactPlansContractTest` (`source.validationFactPlans`: the shared `ValidationFactBatch` rule both dialects call, TP §9.2 aggregates, ADR-0040 null counts and byte lengths, key facts without manufactured extrema); `TargetValidationFactsContractTest` (`target.validationFactPlans`: the same shared batch rule batch for batch, approved target names, `octet_length` byte lengths, `numeric` extrema); `TargetSamplingLookupContractTest` (`target.samplingLookupPlan`: one bound lookup per key tuple, `STATEMENT_ONLY` evidence, a wrong-arity tuple refused).

## Read

- Sub-spec: [docs/spec/dialect.md](/docs/spec/dialect.md)
- ADRs: [ADR-0003](/docs/adr/0003-preflight-gated-large-record-envelope.md), [ADR-0006](/docs/adr/0006-versioned-connections-recovery-and-reruns.md), [ADR-0008](/docs/adr/0008-database-dialect-and-pair-extension-seam.md), [ADR-0011](/docs/adr/0011-platform-owned-ddl-and-table-write-contract.md), [ADR-0018](/docs/adr/0018-backend-module-boundaries-and-agent-working-surface.md), [ADR-0022](/docs/adr/0022-verification-ladder-and-explicit-golden-updates.md), [ADR-0023](/docs/adr/0023-task-abandonment-drops-owned-target-tables.md), [ADR-0033](/docs/adr/0033-bounded-source-reads-cursor-fetch-and-keyset-chunks.md), [ADR-0036](/docs/adr/0036-module-table-owns-every-v1-obligation.md), [ADR-0037](/docs/adr/0037-bulk-source-reads-keyset-column-and-64-mib-cap.md)
