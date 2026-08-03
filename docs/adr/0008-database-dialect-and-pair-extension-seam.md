# Database dialect and database-pair extension seam

DBX v1 separates database-specific interpretation from the database-independent migration workflow. A source dialect, a target dialect, and an explicitly registered directed database pair compose the extension seam; each aggregate is made from fixed, strongly typed capabilities rather than a giant `Dialect` interface. This preserves reuse of endpoint behavior without treating any two registered endpoints as an automatically supported migration route.

## Ownership and control flow

The migration core owns task, run, table migration unit and box lifecycles; state transitions; scheduling; Kafka and Connect lifecycle; recovery; routing; validation disposition; and diagnostic evidence. It also owns the mandatory sequence:

1. run fresh, scope-specific source, target, Kafka, and Connect capability checks; every check and the active external-infrastructure and isolated target probes required by existing decisions must be `PROVEN` before contract approval or target DDL, and pair certification never substitutes for these per-run checks;
2. read normalized source metadata and all required exact preflight facts before source-baseline capture;
3. assemble and approve a table write contract draft;
4. confirm the source write freeze, capture the exact baseline, and freeze the approved contract and baseline in the migration run; the commitment remains valid through every selected table's validation terminal state or execution stop;
5. execute target DDL derived from that contract;
6. read the actual target structure and prove zero difference from the contract;
7. start the Sink only after proof succeeds.

Dialect capabilities can return facts, decisions, or plans, but cannot advance workflow or return an override that bypasses a gate. Required proof has the closed outcomes `PROVEN`, `INCONCLUSIVE`, and `REJECTED`; only `PROVEN` permits the core to continue. Validation disposition after data transfer is not a pre-execution override.

The source dialect interprets its database family's connection identity and permissions, metadata, original types, value and identifier semantics, exact preflight and baseline facts, extraction plans, and source-side validation facts. The target dialect interprets target types, identifiers and structures, renders an approved contract as DDL, reads and compares the actual target structure, derives target maintenance plans, and reads target-side validation facts. The directed database pair owns the `TypeMapper`, cross-endpoint identifier mapping, and compatibility decisions that require simultaneous knowledge of source extraction, Connect representation, JDBC binding, and target representation. It never owns migration lifecycle, scheduling, or safety gates.

Each aggregate composes fixed, strongly typed capabilities. V1 does not provide string-based capability lookup, reflective extension lookup, generic operation dispatch, optional default-success implementations, or untyped extension maps.

## Contract and mapping boundary

The table write contract has a database-independent semantic skeleton and versioned, strongly typed dialect descriptors. The common skeleton records source and target identity, ordered included columns, source-output-to-target mappings, Connect schema and JDBC binding decisions, nullability and default decisions, primary-key and identity or sequence intent, routing, warnings and incompatibilities, version identities, approval revision, and a deterministic content fingerprint. Source and target type details remain in descriptors identified by dialect and descriptor version. DDL and generated SQL are derived artifacts, never contract facts. This seam does not expand the already decided v1 target-structure boundary: the pre-transfer contract still describes only the minimal writable table, and unique constraints, ordinary indexes, foreign keys, comments, and collation remain outside that contract. Their separately decided post-migration delivery is not an executable path owned or extended by this seam.

The migration core is the only table write contract assembler. It combines normalized metadata, user mapping exceptions, mapping and identifier decisions, exact preflight evidence, and pair compatibility decisions; verifies completeness and evidence/version linkage; rejects conflicts and blocking findings; and creates a new deterministic draft revision. Dialects and pairs cannot create or approve a contract directly. V1 does not use arbitrary JSON, `Map<String, Object>`, or an opaque extension bag to avoid modeling database semantics.

`TypeMapper` remains a pure function over a source-column descriptor and structured mapping options, but returns a complete closed mapping decision rather than only a target type. A supported decision includes the target type descriptor, source extraction intent, Connect schema representation, JDBC binding decision, value semantics, required exact preflights, contract effects, and stable notices. An unsupported decision includes a stable reason and required evidence. The supported decision and its runtime representations are frozen in the contract fingerprint rather than recomputed from implementation defaults. `TypeMapper` does not execute preflight, generate SQL, schedule boxes, mutate workflow, or waive a gate. Mapping exceptions are inputs to a fresh decision and therefore regenerate the contract and require approval.

Source and target dialects interpret and quote identifiers under their own rules. The pair decides whether the source name and intended target name are exactly representable, require an explicit rename or safe alias, or are unsupported. The core freezes the approved coordinates in the contract and routing snapshot; later phases never rerun naming rules or infer coordinates from topic names, connector names, or exception text.

## Plans and execution requirements

Dialect capabilities produce immutable, typed, fingerprintable SQL plans with a closed operation kind, parameterized statements, typed parameter values, and a typed expected result schema that fixes columns, database types, cardinality, and nullability, together with timeout class, required privileges, and evidence policy. Database-specific identifier quoting is derived only from approved typed identifiers; ordinary values use parameter binding. The core database gateway binds the run's frozen connection and credential versions, controls transactions, read-only behavior, timeout and cancellation, executes only operations allowed in the current state, validates the parameter and result contracts, and persists evidence and audit facts. Dialects do not open connections, hold `JdbcTemplate`, manage transactions, or issue hidden queries.

Source dialect, target dialect, and pair capabilities declare typed execution requirements. The core rejects missing or conflicting requirements, injects every mandatory platform policy, and validates the normalized final Source and Sink configurations before provisioning; it alone produces the routing snapshot, configuration fingerprint, and execution signature. The normalized connector configuration and execution signature are derived from the same frozen Connect-schema, JDBC-binding, extraction, routing, and platform-policy decisions in the approved contract, never from recomputed defaults. No capability may omit or override the 20 MiB source payload boundary, 25 MiB Kafka envelope, `auto.create=false`, large-record isolation and single-record polling, run-isolated naming, or the prohibition on silent skip, truncation, DLQ success, and a second data path. V1 exposes no arbitrary connector-property pass-through.

## Registration, persistence, and recovery

V1 uses an explicit compile-time catalog with stable identifiers and strict version matching for source dialect aggregates, target dialect aggregates, and directed database-pair aggregates. Connection probing identifies the actual database product and version before an exact catalog selection. Missing, ambiguous, uncertified, or version-incompatible entries are unsupported; there is no nearest-version or default fallback.

A database pair is directed, versioned, explicitly registered, and independently certified. Registering both endpoint dialects does not create a pair automatically. A pair records its source and target dialect identifiers, mapping version, and certification version. V1 excludes Java SPI or `ServiceLoader`, runtime plugin JARs, classpath scanning, configured implementation class names, hot loading, user-authored SQL dialects, and configuration-driven mapping rules.

Every approved contract snapshot records its contract schema version, source and target dialect and descriptor versions, pair and mapping versions, approval revision, and content fingerprint. Recovery loads that snapshot through an explicitly compatible versioned codec and never rereads source metadata or reruns mapping for the same migration run. A compatible reader preserves the original version identity and semantics rather than silently upgrading them. If the installed implementation cannot interpret the snapshot, the run becomes non-automatically recoverable with its evidence preserved; applying new mapping rules requires a new migration run, fresh facts, a new contract, and new approval. Contract versions required by nonterminal runs cannot be removed.

## Certification and extension promise

The migration core owns a mandatory certification suite; a pair supplies real database environments and fixtures but cannot select which safety gates apply. Certification covers deterministic mapping boundaries, real source metadata and exact preflight, DDL execution and zero-difference target introspection, identifier and permission failures, the complete Source/Kafka/Connect/Schema Registry/Target path, large-record boundaries, query-mode projection and rename, empty tables, repeated delivery, core gate enforcement, serialized contract compatibility, recovery, and version-upgrade regression. H2 substitutes, disabled tests, empty "not applicable" implementations, SQL-string-only assertions, and caught failures reported as success do not certify a pair.

Adding a database pair that fits the existing offline full-migration model may add or reuse endpoint capabilities, add typed descriptors and codecs, implement and certify the directed pair, and register it in the static catalog. Product-specific diagnosis rules or capability-driven UI text may be added when the new pair introduces facts the existing catalog or presentation cannot express, but they are not unconditional registration requirements. The pair must not copy or rewrite migration workflow, scheduling, lifecycle, recovery, or safety gates. A genuinely cross-database concept may evolve shared typed protocols and requires every affected pair to recertify; a requirement that changes consistency, lifecycle, scheduling, or transport—such as CDC, single-table sharding, or a second data path—requires a separate architectural decision.

## Consequences

This seam makes the safety sequence structurally reusable and keeps database-specific SQL and semantics out of workflow code. It deliberately accepts compile-time registration, explicit modeling work, and real integration-test cost in exchange for preventing a new database pair from bypassing correctness gates or hiding semantics in configuration.

The extension promise is therefore that a conforming pair does not require the migration workflow core to be rewritten, not that the repository or shared type model will never change. Strongly typed descriptors, codecs, catalog assembly, fixtures, and certification evidence are expected extension work.

## Rejected alternatives

- **One giant dialect object or pair object** was rejected because it would duplicate endpoint behavior and accumulate workflow, SQL, connector, and lifecycle responsibilities.
- **Automatically composing any registered source and target dialect** was rejected because endpoint correctness does not prove cross-endpoint representation and binder compatibility.
- **Dialect-created contracts or dialect-controlled workflow** was rejected because every pair could then omit evidence or bypass approval and structural proof.
- **Untyped descriptor and connector configuration bags** were rejected because they hide semantics, weaken deterministic snapshots, and create unsafe escape hatches.
- **Dialect-owned JDBC execution** was rejected because hidden side effects would bypass frozen connections, transaction policy, target generations, auditing, and state-machine guards.
- **Dynamic plugin loading and permissive version fallback in v1** were rejected because they expand the trust and compatibility boundary before a second database pair exists.
