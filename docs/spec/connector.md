# connector — v1 sub-spec

The data-plane client and its pure completion judgement: Connect REST, Kafka AdminClient, Schema Registry REST, connector lifecycle, deletes, secret projection, restart marker, the single routing/config/fingerprint/signature derivation, and `judge`.

**Read first**: ADR-0036, ADR-0018 (enforcement, session rule), ADR-0001, ADR-0009, ADR-0010, ADR-0008 §Plans and execution requirements, ADR-0031 §Per-box bounds, ADR-0033, ADR-0037, ADR-0003 (Kafka settings paragraph), ADR-0006 (secret paragraph, §Recovery, §Cancellation and discard), ADR-0032, ADR-0021, ADR-0039, ADR-0005 (polling paragraph); technical plan §6.5, §7.2, §8. CONTEXT.md terms: box (箱), read complete (读取完成), write complete (写入完成), stuck (卡死), execution signature (执行签名), routing snapshot (路由快照), scheduling plan (调度计划), keyset column (键集列), source baseline (源基线), large record table (大记录表), admission paused (准入已暂停), discard (丢弃).

## Interface (`connector.api`)

ADR-0036 categories: box start/stop, deletes, data-plane facts, `judge`. Effectful unless marked pure.

- Derivation (pure): `executionSignature(contract, requirements) → ExecutionSignature` per table, before `scheduling.plan`; `deriveBox(box, contracts, requirements) → {RoutingSnapshot, NormalizedConfig (Source, Sink), ConfigFingerprint}` per planned box.
- Box start/stop: `createTopics(box)`, `startSink(box)`, `startSource(box)`, `stopSource(box)`, `stopSink(box)` → observed fact after an idempotent reconcile; `projectSecret(run, secretMaterial) → ProviderReference`.
- Deletes: `deleteTopic(unit)`, `deleteSubject(unit)`, `removeSecretProjection(reference)` → confirmed-absent | pending | refused(reason).
- Data-plane facts: `observeBox(box) → BoxFacts` (connector/task status and trace, topic end offsets, Sink lag); `observeSubject(unit) → SubjectFacts`; `plantRestartMarker()`; `checkRestartMarker() → PRESENT | RESTARTED | UNREACHABLE | REPLANT`; `kafkaFacts() → KafkaFacts` (E2 readiness, E3 plugins, E4 configuration, E6 log dirs, per-topic sizes); `listDbxResources(ownershipRecords) → owned | orphan`.
- `judge(BoxObservations, baseline, elapsed) → BoxVerdict` (pure): `IN_PROGRESS | READ_COMPLETE | WRITE_COMPLETE | SUSPECTED_STUCK | STUCK(zeroOutput) | DUPLICATE_DELIVERY | FAILED | OVER_TIME_LIMIT`.

## Consumes

- `dialect.api`: `pair.executionRequirements` (incl. bounded read) and `source.connectionSemantics`.
- `contract.api`: the approved table write contract as an input type; no call.
- `scheduling.api`: the box membership from `plan` as an input type; no call.

No other module's side effects: `orchestration` decrypts through `connection.decrypt` and hands the material to `projectSecret`, as it does for `gateway` (ADR-0036 §Dependencies and purity).

## Obligations

**Derivation**
1. Routing snapshot, normalized config, config fingerprint, and execution signature come from one derivation over frozen contract, requirement, and policy inputs; no second path elsewhere (ADR-0036, ADR-0008 §Plans).
2. Mandatory platform policies cannot be omitted or overridden by any requirement: run isolation, exact identifiers, envelope, `auto.create=false`, `auto.evolve=false`, bounded reads, no DLQ or skip; a missing or conflicting requirement is rejected (ADR-0008 §Plans, ADR-0033 §Mandatory policy, ADR-0009 §Ownership).
3. No connector-property pass-through exists in any input type (ADR-0009 §Ownership).
4. Names: connectors carry run and box ID; topics carry run ID and resolve to exactly one unit; a subject is `<topic>-value` under `TopicNameStrategy`, no key subject (ADR-0001, ADR-0010 §Subjects).
5. Topic legality (Kafka characters, 249 bytes, dot/underscore collision, run-local uniqueness); a table failing it gets a safe alias and a fixed `table.name.format`; the Sink `RegexRouter` strips the run prefix (TP §7.2).
6. Source fixed settings and Connector/J semantics per TP §6.5; Sink fixed settings per ADR-0011 and TP §7.4; Avro converter, same SR, `schemas.enable=true`; a converter override is a conflict (ADR-0010 §Schema contract).
7. Keyset read: ADR-0033 §Settings table, with `batch.max.rows` and N computed here from M ("the core injects"); bulk read (table without a keyset column): ADR-0037 §Bulk path; keyset-column choice (primary key, else lowest-named unique index) is in the signature (ADR-0037 §Choice).
8. Ordinary producer/consumer settings per ADR-0031 §Per-box bounds; large-record settings per ADR-0003 (Kafka settings paragraph) and ADR-0033 large-record column; topics get `max.message.bytes=26214400` (ADR-0003).
9. Every setting above, plus provider and mount identity and secret-reference semantics, enters the fingerprint and signature (ADR-0031, ADR-0033 §Settings, ADR-0006 secret paragraph).
10. Same inputs yield byte-identical outputs; recovery re-derives nothing from new defaults (ADR-0008 §Registration, ADR-0033 §Consequences).

**Lifecycle**
11. Topics are created before connectors; Sink starts before Source; Source stops before Sink; "stop" deletes the connector (ADR-0001, ADR-0010 §Subjects).
12. Each mutation re-reads state first: an existing connector with a matching fingerprint is accepted, a mismatch is a conflict, and timeout or `409` re-reads with bounded retry; never blind recreation (ADR-0001, ADR-0006 §Recovery, ADR-0005 polling paragraph).
13. A connector missing before its recorded boundary is reported, never recreated (ADR-0001, ADR-0006 §Recovery).
14. Last status response and full trace are returned before each connector deletion so the caller can persist them (ADR-0005 polling paragraph).
15. After the first Source record, subject and schema ID are read back and proven semantically equal to the contract's expectation; an unexpected version, strategy, or shared subject is a failure fact (ADR-0010 §Subjects, #89 item 3).

**Deletes**
16. Topic, subject, connector, and secret-file deletes act only on resources named by the ownership records the caller passes; a subject only after its topic is confirmed absent and no retained topic shares it (ADR-0010 §Cleanup, ADR-0006 §Cancellation and discard).
17. Unknown `dbx-` connectors are listed as orphans and never deleted, including at startup (ADR-0001, ADR-0035 §Volumes).
18. Absence is confirmed by a re-read, not by the delete response; an already-absent resource is success (ADR-0001, ADR-0023 §Failure).

**Secrets**
19. Connector config holds only ConfigProvider references, never a literal password; the file is written atomically and versioned, verified via a non-secret provider probe through Connect, and removed only after every referencing connector is confirmed stopped (ADR-0006 secret paragraph, TP §11.3).
20. REST responses must preserve references; a resolved secret in a response is a failure (ADR-0006 secret paragraph).

**Facts and restart marker**
21. `observeBox` serves the 5 s status poll and the 10 s offset/lag sample; no target counts (ADR-0005, ADR-0002, TP §8).
22. Marker: `PUT /admin/loggers/dbx.incarnation {"level":"ERROR"}` before a run's first box and after every detected restart, retried until 200 (ADR-0032 as amended by #90).
23. `checkRestartMarker` classification exactly per ADR-0032 marker bullet: Connect JSON 404 or 200 with `last_modified: null` → `RESTARTED`; connection error, timeout, 5xx, non-JSON 404 → `UNREACHABLE`; 200, non-null `last_modified`, level ≠ `ERROR` → `REPLANT`. No member-id fallback (ADR-0032, #90).
24. `kafkaFacts` reads only via AdminClient and service REST: E2, E3, E4 categories per #89 item 4 (including SR mode `BACKWARD`, #89 item 3), E6 via `describeLogDirs` (ADR-0027 §Shape).

**Judge**
25. `READ_COMPLETE`: every topic end offset equals its unit's baseline, Source tasks healthy, stable for two 10 s polls; an offset above the baseline is `DUPLICATE_DELIVERY` (ADR-0001, ADR-0037 §Bulk).
26. `WRITE_COMPLETE`: Source absent, Sink lag 0, every target count equals the baseline, stable for two polls (ADR-0001).
27. `RUNNING` status and Source-offset REST data never make a completion verdict (ADR-0001, ADR-0009).
28. No progress while healthy: `SUSPECTED_STUCK` at 2 min, `STUCK` at 10 min, flagging a zero-record box for ADR-0032's two-box rule (ADR-0001, ADR-0032 §Wedges).
29. Time while Kafka, Connect, or SR is unreachable, or while a task fails on the database, accrues no stuck time (ADR-0021, ADR-0039).
30. A box running 24 h is `OVER_TIME_LIMIT` (ADR-0001 bulk paragraph).
31. `judge` reads no clock, HTTP client, or `JdbcTemplate`; time arrives as input (ADR-0018 §Enforcement, ADR-0036).

## Verification

- Derivation 1–10: L1 `ConnectorContractTest` + `DerivationTest` (policy override rejected, determinism, per-class settings tables, topic-legality cases).
- Judge 25–31: L1 `JudgeTest` on fixed observation sequences; ArchUnit pure rule for `judge` and the derivation.
- Lifecycle, deletes, facts 11–18, 21, 24: L2 `seamTest` (`ConnectRestClientSeamTest`, `KafkaAdminSeamTest`, `SchemaRegistrySeamTest`) against Testcontainers Kafka, Connect, and SR.
- Secrets 19–20: L2 `ConfigProviderSeamTest`.
- Marker 22–23: L1 classifier table in `RestartMarkerTest`; L2 against Connect; L3 `e2eTest` marker protocol on the pinned image with a real JVM kill (ADR-0032 §Consequences, TP §15.3).
- Bounded reads (7): L3 bounded-read scenario (ADR-0033, ADR-0037 §Consequences).

## Slices

1. `api` types and entry-point signatures + `ConnectorContractTest` skeleton, ArchUnit purity for `judge` and derivation. Blocked by `dialect` slice 1, `contract` slice 1, `scheduling` slice 1 (input types).
2. Derivation (1–10), L1. Needs 1; blocked by `dialect` slices 5 and 9; D-4 (query projection).
3. `judge` (25–31), L1. Needs 1.
4. Connect REST client: start/stop, reconcile, status poll, trace capture (11–14, 21), L2. Needs 2.
5. Kafka AdminClient + SR client: topics, offsets, lag, subject read-back, deletes, orphans, `kafkaFacts` (15–18, 24), L2. Needs 2.
6. ConfigProvider projection (19–20), L2. Needs 4.
7. Restart marker (22–23), L1 + L2; L3 certification on merge to `main`. Needs 4.

## Conflicts resolved

- ADR-0018 `connector` row (Connect REST only) → ADR-0036 row adds AdminClient, SR, deletes, secrets, marker, derivation.
- ADR-0008 "the core alone produces" / audit's `contract.routingSnapshot` → ADR-0036: one derivation in `connector`.
- ADR-0003 large-record overrides on every connector → ADR-0031: large-record boxes only.
- ADR-0001 "usable monotonic incrementing column" → ADR-0037 keyset column; ADR-0033 `LIMIT N`/100 ms for all → ADR-0037 bulk: empty suffix, `2147483647`, 64 MiB cap.
- ADR-0021 ten-minute grace on any platform outage → ADR-0032: Connect restart fails boxes at once, grace for unreachable only; ADR-0039 adds SR.
- ADR-0001 stuck deletes connectors only → ADR-0032/0039: zero-output flag feeds the admission pause.
- ADR-0001 two-minute warning on the box → ADR-0021: shown on units only; `judge` only reports it.
- #79 "every poll" and `describeClassicGroups` fallback → ADR-0032 as amended by #90: 5 s status poll, no fallback.
- ADR-0010 "one tested mode" → #89 item 3: `BACKWARD`.
- ADR-0035 "startup removes leftover topics and subjects" vs ADR-0001 orphans → only ownership-recorded resources are deleted; orphans stay.
- ADR-0003 external-cluster probe, ADR-0006 shared-mount and ADR-0009 customer-deployment clauses → suspended in v1 (ADR-0009 status note).

- ADR-0036 lists only `connector.judge` as pure → the derivation is pure too: the effectful-shell list names only the data-plane clients and the ConfigProvider file (ADR-0036).

## Implementer decides

- Retry count and backoff for `409`/timeout on Connect REST mutations: bounded, inside ADR-0006's ten-minute budget, exhaustion a visible failure (ADR-0005 polling paragraph).

## Open items

- **D-4** (T1): whether `deriveBox` or a `dialect` entry renders the prune/rename Source query (TP §7.1). Blocks slice 2.
- **D-20** (T5): the diagnosis code for `OVER_TIME_LIMIT` (ADR-0001) is `diagnosis`'s; `judge` only reports it.
