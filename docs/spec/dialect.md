# dialect — v1 sub-spec

MySQL 8.0 source dialect (源方言), PostgreSQL 15 target dialect (目标方言), and the directed database pair (数据库对): facts, mapping decisions, and immutable typed SQL plans; never opens connections.

**Read first**: ADR-0036 → ADR-0018 (dialect row, enforcement) → ADR-0008 → ADR-0011 → ADR-0003 → ADR-0033 → ADR-0037 → ADR-0022; TP §6, §7.1, §7.3, §9.2–9.3. CONTEXT.md terms: source dialect, target dialect, database pair, mapping rule, preflight, table write contract, structural proof, supplemental SQL, keyset column, source baseline, large-record envelope, execution signature, Supported, Unsupported.

## Interface (`dialect.api`)

ADR-0036/0018 describe this interface without naming entry points; the names below are this spec's. All are pure.

- `catalog.select(sourceProductVersion, targetProductVersion) → DatabasePair | Unsupported`; `catalog.list() → supported pair descriptors` (ADR-0008 §Registration; #46 Q1; added at reconciliation for the connection form)
- `pair.map(SourceColumn, MappingOptions) → Supported | Unsupported` (`TypeMapper`)
- `pair.mapIdentifier(sourceCoordinate, mappingRule?) → Exact | Renamed | Unsupported`
- `pair.executionRequirements(mappingDecisions) → ExecutionRequirements` (includes bounded read)
- `pair.validationCapabilities() → ValidationCapabilities`
- `pair.descriptorCodec(version) → DescriptorCodec | Unsupported`
- `source.metadataPlan(scope) → SqlPlan`; `source.normalizeMetadata(rows) → SourceTableMetadata`
- `source.capabilityPlans(scope)`, `source.preflightScanPlan(table, obligations)`, `source.baselinePlan(table, keysetColumn?)`, `source.validationFactPlans(items)`, `source.samplingPlan(key, n) → SqlPlan(s)`
- `source.keysetCandidates(SourceTableMetadata) → ordered candidates`
- `source.queryProjection(approvedColumns, mappingRules) → ProjectionSql` (the prune/rename `SELECT … AS …` projection; #92)
- `source.connectionSemantics(MappingOptions) → ConnectionSemantics`
- `target.ddlPlan(TargetTable)`, `target.catalogReadPlan(coordinates)`, `target.capabilityProbePlans(schema, probeName)`, `target.maintenancePlans(...)`, `target.validationFactPlans(items)`, `target.samplingLookupPlan(keys) → SqlPlan(s)` (TP §9.3; lookup added at reconciliation for `validation`)
- `target.normalizeCatalog(rows) → TargetTableFacts`
- `target.supplementalStatements(deferredStructures) → Statement[]`; `target.leastPrivilegeSql(missing) → text`
- `target.sinkSettings() → SinkSettings`

## Consumes

None: `dialect` is the bottom of the dependency graph (ADR-0018 §Dependency direction).

## Obligations

**Purity and shape**
1. No dependency on another module, `JdbcTemplate`, an HTTP client, or a clock (ADR-0018 §Enforcement; ADR-0036 §Dependencies and purity).
2. Fixed, strongly typed capabilities only: no string lookup, reflection, SPI, default-success stub, `Map<String,Object>`, or JSON bag (ADR-0008 §Ownership; TP §3.2).
3. Nothing advances workflow, creates or approves a contract, or returns a gate override; proof outcomes are `PROVEN|INCONCLUSIVE|REJECTED` (ADR-0008 §Ownership).
4. Each `SqlPlan` is immutable and fingerprintable: closed operation kind, parameterized statements, typed parameters, typed result schema (columns, types, cardinality, nullability), timeout class, required privileges, evidence policy (ADR-0008 §Plans).
5. Values are always bound; identifiers are always quoted, and only from approved typed identifiers (ADR-0008 §Plans; TP §7.1).

**Catalog and versions**
6. Compile-time catalog: MySQL 8.0 source, PostgreSQL 15 target, one directed pair recording dialect ids, mapping version, and certification version (ADR-0008 §Registration).
7. Missing, ambiguous, uncertified, or version-incompatible → `Unsupported`; no fallback and no automatic composition (ADR-0008 §Registration).
8. Codecs read their own versions and never upgrade them silently (ADR-0008 §Registration).
9. A source dialect without a bounded-read declaration cannot be registered (ADR-0033).

**TypeMapper**
10. Every MySQL 8.0 `data_type` gets a closed result: never null, never an exception (TP §6.1).
11. Results match the TP §6.2–6.4 rows. `BIT(n≥8)`, geometry, `VECTOR`, and non-whitelisted types are `Unsupported` (TP §6.2–6.4).
12. `Supported` carries the target type, extraction intent, Connect/Avro representation, JDBC binder, value semantics, required preflights, contract effects, alternatives, and notices. `Unsupported` carries a stable reason and the evidence it needs (ADR-0008 §Contract; ADR-0010).
13. `MappingOptions` holds exactly the two switches; alternatives only widen the value domain (TP §6.1).

**Identifiers**
14. Names are exact. An overlong schema or table name becomes `<utf8-prefix>_<hash12>` (≤63 bytes, cut at a code-point boundary); an overlong column name is `Unsupported`; names are never inferred from topic or connector names (TP §7.1; ADR-0008 §Contract).

**Source plans**
15. The metadata plan reads types, keys, indexes, defaults, auto-increment, comments, charset, and collation, keeping the raw `information_schema` facts (TP §4 step 3; TP §6.1).
16. Preflight obligations per table merge into minimal bounded scans, with one aggregate query for the envelope using `COALESCE(OCTET_LENGTH(CAST(E AS BINARY)),0)` per value and per row sum (ADR-0003; TP §6.6 checks 1–7).
17. That scan also yields each keyset candidate's min and max plus the row-length facts for check 8 (ADR-0037; TP §6.6).
18. Keyset candidates are single integer, `NOT NULL`, unique columns: the primary key first, then the unique index with the lowest name in the source collation (ADR-0037 §Choice).
19. The baseline plan reads the exact `COUNT(*)` and the keyset column's min and max (ADR-0037; TP §4 step 8).
19a. `queryProjection` renders the prune and rename projection, `SELECT <expr> AS <alias>, …`, from approved typed identifiers under obligation 5's quoting rules. It is the **only** renderer of that projection: `preflight`'s envelope scan measures these expressions (`preflight` `plan`) and `connector.deriveBox` places the same text into the Source `query` and `query.mode` properties and fingerprints it. It renders no `FROM`-clause filter, no `WHERE`, and no value transform (TP §7.1; ADR-0036 §Amended by #92).
19b. The metadata plan's statistics carry the 预估行数 and `DATA_LENGTH` that `preflight` uses for the bulk cap and the byte estimate; the projection and the envelope scan both cover exactly the approved selected columns (ADR-0002 ¶4, ADR-0037 as amended by #92).
20. Capability plans need only ADR-0006's read-only privileges (ADR-0006 §Capability checks).
21. Connection semantics are the fingerprinted TP §6.5 Connector/J settings, including `useCursorFetch=true` and no `defaultFetchSize` (TP §6.5; ADR-0033).
22. The bounded-read requirement declares cursor fetch, byte-derived fetch sizing, `LIMIT` keyset chunks, and bulk reads only within the 64 MiB cap (ADR-0033 §Settings; ADR-0037 §Bulk path).
23. Validation and sampling plans, including the target key lookups, follow TP §9.2–9.3 (at most 300 numeric columns per batch, key-order rules, typed key lookups).

**Target plans**
24. `ddlPlan` builds only the minimal writable table: exact types; `NOT NULL` except the approved relaxation; the primary key or the approved candidate; identity, or an owned sequence for `numeric(20,0)`; `CHECK` for `ENUM`; whitelisted defaults (`CURRENT_TIMESTAMP(n)` → `LOCALTIMESTAMP(n)`) (ADR-0011 §DDL; TP §7.3).
25. Supplemental statements are executable, with foreign keys last. `ON UPDATE CURRENT_TIMESTAMP` and collation appear as comments only. Pruned or out-of-scope objects are commented out with a reason (ADR-0026 §Supplemental SQL).
26. `normalizeCatalog` returns everything structural proof compares, plus the `pg_class` OID (TP §7.4; ADR-0023).
27. Probe plans use an isolated, uniquely named object. Least-privilege SQL is text for the DBA, never a plan (ADR-0006 §Capability checks).
28. Maintenance plans:
    - never use `CASCADE`;
    - drop by OID;
    - drop a schema only when its `pg_namespace` OID matches and it is empty;
    - `setval` handles `is_called=false`;
    - grant only on exact DBX-owned objects.

    (ADR-0023; #89 item 10; ADR-0006; TP §7.3)
29. Sink settings fix `auto.create=false`, `auto.evolve=false`, `insert.mode=insert`, `pk.mode=none`, `delete.enabled=false`, `quote.sql.identifiers=always`, and UTC (ADR-0011 §Sink contract; TP §6.5).

**Requirements**
30. Execution requirements cannot omit or override the 20/25 MiB envelopes, `auto.create=false`, large-record isolation, run-isolated naming, or the ban on skip, DLQ, or a second data path (ADR-0008 §Plans; ADR-0009).

## Verification

- **1–5**: L1 ArchUnit rules and `DialectContractTest` (plan shape, fingerprint determinism, hostile identifiers and values).
- **6–9**: L1 `DialectCatalogContractTest`.
- **10–13**: L1 golden set 1 (type-mapping matrix; ADR-0022), exhaustive type-list test, widening and determinism property tests (TP §15.1).
- **14**: L1 `IdentifierMappingContractTest`.
- **15–23**: L1 `SourceDialectContractTest`, including `#queryProjection*`: pruned column absent, renamed column aliased, hostile identifiers quoted, byte-identical to the text `deriveBox` places. The bounded-read effect is proven at L3 in the bounded-read scenario (ADR-0033 §Proof; ADR-0037).
- **24–29**: L1 `TargetDialectContractTest`. PostgreSQL 15 execution of TP §15.2 runs at L2 in `contract` slice 8 through `gateway`; `dialect` is pure and has no L2 (ADR-0022).
- **30**: L1 `PairContractTest`.
- **Pair certification**: L3 `e2eTest`, required on merge into `main` when `dialect` changes (ADR-0022; ADR-0008 §Certification).

## Slices

1. **api skeleton**: value types, stubbed entry points, `DialectContractTest`, ArchUnit purity, README. Blocks nothing.
2. **Catalog, versions, codecs, `catalog.list`** (6–9). After 1.
3. **TypeMapper** with golden set 1 (10–13). After 1.
4. **Quoting and identifiers** (5, 14). After 1.
5. **Source metadata, capability plans, connection semantics, keyset candidates, query projection, bounded read** (15, 18, 19a–19b, 20–22). After 3, 4.
6. **Preflight scan, baseline, validation and sampling plans** (16–17, 19, 23). After 3, 5.
7. **Target DDL, supplemental statements, Sink settings** (24–25, 29). After 3, 4.
8. **Target catalog, probe, maintenance, validation plans** (26–28). After 7.
9. **Pair execution requirements and validation capabilities** (30). After 3, 5.

No slice is blocked by another module.

## Conflicts resolved

- ADR-0008 "core alone produces routing snapshot, fingerprint, signature" → `connector` derives them; the dialect only supplies requirements (ADR-0036 `connector` row).
- ADR-0008 "target dialect renders DDL … reads and compares" → `contract` owns `renderDdl` and `prove`, fed by `ddlPlan` and `normalizeCatalog` (ADR-0036 `contract` row; ADR-0018 §Dependency direction).
- ADR-0011 body puts the Sink settings under the contract → they belong to the target dialect (ADR-0011 status note; ADR-0018).
- ADR-0033's settings table read as covering all reads → keyset reads only; bulk reads take an empty suffix, `2147483647`, and the 64 MiB cap (ADR-0037).
- ADR-0001 "usable monotonic incrementing column" → keyset column, with no monotonicity requirement (ADR-0037).
- `MAX(LENGTH(column))` → ADR-0003's byte formula (TP §6.6).
- ADR-0018 purity list → ADR-0036's list (ADR-0036).
- ADR-0008 step 1 infrastructure probes → suspended in v1 (ADR-0008 v1 note; ADR-0003 status).
- Supplemental statements as a dialect entry (ADR-0026 "the same pure function … as the contract") → `target.supplementalStatements` renders; `contract.assemble` calls it from the same snapshot (ADR-0036 `contract` row).
- `batch.max.rows` and N (ADR-0033 "the core injects") → computed from M in `connector.deriveBox`, the one normalized-configuration derivation; the dialect declares only the requirement (ADR-0036 `connector` row; obligation 22).

- TP §7.1 leaves the prune/rename projection's renderer unnamed → `source.queryProjection` renders it, `connector.deriveBox` only places and fingerprints it, because the projection has two consumers and `preflight` reaches only `dialect.api` (#92; ADR-0036 §Amended by #92).

## Open items

_None._
