# Avro and Schema Registry lifecycle

DBX v1 uses Connect schemas serialized by the Confluent Avro converters and registered in Schema Registry for every data topic. JSON, schemaless records, Java serialization, and application-defined payload envelopes are not alternative v1 paths. The Avro representation is part of the approved table write contract because source extraction, Connect schema, Avro logical types, JDBC Sink binding, and the exact PostgreSQL target type must agree.

## Schema contract

Values use `io.confluent.connect.avro.AvroConverter` with Schema Registry and schemas enabled. Data records carry the Confluent Avro wire format and a registered value schema. Keys are not used as a second representation of table identity or as the JDBC Sink primary-key contract: v1 fixes Sink `pk.mode=none`, and the approved value schema carries every selected output field in contract order. Tombstones and delete semantics are not part of the offline full-copy path.

The database pair produces a closed mapping decision for each source column. That decision fixes the Source extraction expression, Connect physical or logical type, Avro representation, JDBC binder family, target type, nullability semantics, and required preflight. The migration core freezes those decisions and their version identities in the table write contract. Converter defaults are never allowed to recompute or silently change a frozen mapping during recovery.

DBX validates the normalized worker and connector converter configuration before provisioning. Source and Sink must resolve the same Schema Registry service and compatible converter policy. `schemas.enable=false`, JSON converter substitution, an unregistered custom logical-type handler, and arbitrary converter overrides are configuration conflicts, not degraded modes.

## Subjects, versions, and compatibility

Each run-isolated table topic owns one value subject under the fixed `TopicNameStrategy`: `<topic>-value`. Because every topic includes the immutable migration run identity and resolves to one table migration unit, its subject is likewise run- and table-isolated. V1 does not use record-name subjects shared across topics and does not create a key subject for the null-key data path.

DBX explicitly creates the topic before starting connectors, derives the expected subject name deterministically, and records it with the table migration unit. The first Source record registers the frozen value schema. DBX then reads the subject and schema identifier back and proves that the observed schema is semantically equal to the contract-derived expectation before treating schema registration as healthy evidence. Unexpected prior versions, an incompatible schema, an unknown subject strategy, or a subject shared by retained topics blocks or fails the affected scope; DBX never deletes or rewrites an unproven subject.

Run isolation means v1 does not evolve a data topic's schema in place. A mapping, metadata, converter, or contract change requires a new migration run, fresh topic, and fresh subject. Schema Registry compatibility mode is therefore not an evolution mechanism for one run: DBX reads and records the effective mode during capability checks but relies on exact contract equality for the single produced schema. The built-in deployment fixes one tested mode in its release configuration; changing that mode is a deployment compatibility change and must pass the same certification suite. Customer-managed installations must prove that DBX can create, read, register, and delete isolated subjects with equivalent exact-schema semantics.

The contract snapshot records converter identity, converter configuration fingerprint, subject strategy, expected schema fingerprint, and observed schema identifier. Recovery reads those persisted identities and current Registry facts. It never regenerates the original schema from new mapping defaults. If the installed codec cannot interpret the frozen contract or the observed subject no longer matches it, continuity is unprovable and the run fails safely.

## Cleanup and diagnostics

Topic ownership precedes subject ownership. After a successful table validation, DBX requests topic deletion and confirms the topic absent before deleting its `<topic>-value` subject. Failed, blocked, cancelled, stuck, inconclusive, and accepted-risk tables retain both topic and subject until separately confirmed discard. Cleanup retries are asynchronous and do not alter the migration result.

A subject may be deleted only from authoritative ownership records and only when no retained topic references it. Registry unavailability delays cleanup; it never permits Kafka retention to remove unvalidated evidence. Diagnostic packages may include schema identifiers, fingerprints, converter versions, compatibility observations, and redacted Registry responses, but never record values or credentials.

The 25 MiB transport envelope includes Avro and protocol overhead and remains governed by ADR-0003. Converter or Registry upgrades must rerun exact 20 MiB payload and near-25 MiB transport boundary tests plus every logical-type and JDBC-binding fixture.

## Consequences

Avro preserves typed Connect records and avoids per-record JSON schema expansion, while Schema Registry makes the serialization contract externally observable. The cost is another required service, explicit subject lifecycle, converter-version certification, and hard failure when schema identity cannot be proved.

Run-isolated subjects deliberately trade schema reuse for deterministic recovery and cleanup. V1 does not promise cross-run schema evolution; each new run receives a fresh immutable schema context.

## Rejected alternatives

- **JSON or schemaless payloads** were rejected because they weaken type evidence and create unacceptable schema/payload overhead for whole-database migration.
- **Application-defined Avro serialization** was rejected because it would create a second producer/consumer implementation outside Connect.
- **Record-name or shared subjects** were rejected because shared lifecycle would make per-table evidence retention and cleanup ambiguous.
- **In-place topic schema evolution** was rejected because a migration run is immutable; changed mappings require a new run.
- **Treating Schema Registry as optional after registration** was rejected because recovery must still prove the serializer contract and preserve diagnostic evidence.
