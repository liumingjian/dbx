# Error translation and diagnostic evidence

DBX v1 separates observed failure facts, their operator-facing interpretation, and workflow state. This preserves truthful table timelines while keeping translation rules evolvable.

## Decision

An **error occurrence** is an append-only fact. It records the observed phase, trusted scope, time, correlation, evidence source, and immutable evidence reference. A diagnosis does not rewrite the occurrence.

A **diagnosis** is a versioned interpretation of an occurrence. It has a stable, never-reused diagnosis code, catalog version, source kind, one primary phase, one primary root-cause domain, trusted scope, human-facing message keys, and an evidence summary. The source kinds are:

- `STRUCTURED`: a fact produced directly by DBX, such as a preflight conclusion, a target-contract reverse-check difference, a box `STUCK` diagnosis, or a validation result;
- `EXTERNAL_TRANSLATION`: a rule matched against a Connect REST trace or REST operation failure;
- `SYSTEM_FALLBACK`: `DBX-UNKNOWN` when no rule is trustworthy, or `DBX-RULE-CONFLICT` when same-strength rules disagree.

The catalog and presentation protocol are shared by all three source kinds. Only `EXTERNAL_TRANSLATION` counts toward the first-release set of 20 rule families.

## Classification

The primary phase is one of:

- `CONNECTION`
- `METADATA_READ`
- `PREFLIGHT`
- `TARGET_PREPARATION`
- `CONTRACT_CHECK`
- `CONNECTOR_PROVISIONING`
- `TRANSFER`
- `COMPLETION_DETECTION`
- `VALIDATION`
- `CLEANUP`

The primary root-cause domain is one of:

- `USER_INPUT`
- `SOURCE_DATABASE`
- `TARGET_DATABASE`
- `KAFKA_CONNECT`
- `KAFKA`
- `RUNTIME_ENVIRONMENT`
- `PLATFORM`

A diagnosis has exactly one primary phase and one primary root-cause domain. Scope is independent of cause. The routing snapshot is the only authority for table and field coordinates. DBX first uses its structured context, then corroborates with topic, connector, and exception coordinates. If a coordinate cannot be matched uniquely, the diagnosis remains at box or connector scope. It never guesses a table or field.

A failure attributed to one table produces that table's `FAILED` outcome. An unattributed shared-box failure produces one box-level occurrence; unfinished members become `BLOCKED_BY_BOX_FAILURE` and refer to it. A diagnosis never changes workflow state by itself. Red, orange, and yellow retain their existing preflight/risk meanings rather than becoming runtime severity levels.

## Evidence and matching

Evidence precedence is:

1. DBX structured evidence;
2. stable protocol, database, or HTTP codes;
3. the deepest trustworthy cause in the exception chain;
4. anchored text patterns constrained by component, phase, and version.

A broad wrapper such as `Exiting WorkerSinkTask due to unrecoverable exception` cannot defeat a more specific nested cause. Same-strength conflicts produce `DBX-RULE-CONFLICT`, not an arbitrary choice. Unknown errors remain blocking when the workflow result is blocking, but their copy explicitly says the cause is not identified.

The platform polls active connector/task status every five seconds and queries immediately after connector mutations. It persists the last status response and complete trace before connector deletion. Repeated observations with the same normalized fingerprint are aggregated with first-seen time, last-seen time, and count rather than appended as duplicate timeline cards. Platform-level REST operations may use bounded retries for known transient failures such as rebalance `409`; exhaustion is a user-visible failure. The translation layer never changes configuration, skips records, or starts a new run automatically.

V1 consumes DBX structured failures, Connect REST status/trace, and Connect REST operation responses. It does not collect worker or broker logs, enable business-value logging, or use DLQ as the default migration path. A failed record fails the relevant migration scope rather than being skipped for a green result.

## Rule catalog

The catalog is a versioned JSON resource shipped in the platform distribution. It is the sole v1 rule source: it is not overridden by the metadata database, environment variables, or a customer UI. Startup validates unique codes, message keys, enum values, version constraints, and compilable restricted Java regular expressions. Adding or correcting a rule requires a catalog and fixture release, not matcher-code changes. Rule codes are never reused. Historical diagnoses retain their catalog version; a later interpretation is appended rather than overwriting history. V1 ships Chinese text and reserves locale keys for later translations, falling back to Chinese when another locale is unavailable.

The first 20 external-translation rule families are:

1. Source/target database unreachable.
2. Database authentication failure.
3. Database permission denied.
4. Database or schema does not exist.
5. Unsupported source field type.
6. `BIGINT UNSIGNED` source read overflow.
7. Unrepresentable source date/time value.
8. Invalid or unusable Source incrementing column.
9. Source task has neither an assigned table nor a query.
10. Connect rejects generated connector configuration.
11. Kafka unreachable or metadata unavailable.
12. Kafka authentication or authorization failure.
13. Kafka transport capability/configuration is below the required envelope.
14. Serialized record exceeds the 25 MiB transport envelope.
15. Sink target table is missing.
16. Sink target column is missing or has an exact identifier mismatch.
17. Sink field type is incompatible with the approved write contract.
18. Sink writes `NULL` into a non-null target column.
19. Sink value exceeds the target column length.
20. Sink primary-key or unique-key conflict.

These are a coverage baseline derived from the research catalog and real prototype fixtures, not a claim about telemetry frequency. Each rule requires positive, negative, overlap, and redaction fixtures.

## Operator presentation and support

The default error card answers, in order: what happened, where it happened, what is affected, and what one action is recommended. It shows the stable diagnosis code and occurrence times. Raw SQLState, exception classes, connector/task/topic coordinates, rule evidence, retry summary, and the redacted exception chain remain expandable technical detail. Identical normalized failures are deduplicated without removing their occurrence history.

Unknown and conflicting diagnoses show no speculative cause. They instruct the operator to preserve topics and target data, download the diagnostic package, and contact support. The package is a local ZIP containing bounded timeline context, entity relationships, diagnosis/catalog evidence, redacted connector configuration and fingerprints, routing and contract snapshots, versions, state/offset/lag/count observations, retry summaries, raw REST responses, and checksums. It excludes credentials, tokens, private keys, record values, SQL parameter values, worker/broker logs, and automatic external upload.

## Consequences

DBX can explain common failures without requiring DBA access to Connect logs or SSH, while preserving raw evidence for support. Shared connector boxes remain efficient, but a failure can stop at box scope when the routing snapshot cannot prove a table. The fixed rule catalog and five-second observation cadence make diagnoses reproducible; new signatures require catalog and fixture releases. Unknown failures remain honest and actionable rather than being translated into unsafe guesses.

## Rejected alternatives

- A single mutually exclusive phase-by-responsibility tree was rejected because phase, cause, scope, and workflow outcome answer different questions.
- Customer-editable rules and database overrides were rejected because an unsafe regular expression or action would make diagnosis non-reproducible and transfer complexity to non-developer DBAs.
- Worker/broker log ingestion and default DLQ were rejected because they create deployment and privacy coupling and would permit skipped records or hidden failures.
- Topic-name parsing and one-table blame were rejected because shared boxes, safety aliases, quoted identifiers, and connector traces do not guarantee unique coordinates.
- Automatic recovery driven by translated text was rejected; retries are bounded execution mechanics and reruns are explicit new migration runs.
