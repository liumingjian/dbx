# Kafka and Kafka Connect as the sole v1 data plane

DBX v1 moves every nonempty supported source row through one data plane: Confluent JDBC Source writes to Kafka and Confluent JDBC Sink writes from Kafka to PostgreSQL. DBX is the control plane. It discovers metadata, proves preconditions, assembles contracts, creates target structures and topics, generates connector configurations, schedules boxes, observes completion, validates results, diagnoses failures, and cleans up resources; it never becomes a second record-copy engine.

## Ownership and boundaries

A Source connector owns only extraction according to the approved contract and execution signature. Kafka owns durable run-isolated transport. Schema Registry owns the Avro schemas used by that transport. A Sink connector owns only JDBC insertion into the already-proven target table. None of them owns a migration task, migration run, table migration unit, business outcome, retry decision, or cleanup policy.

DBX starts Sink before Source and stops Source before Sink. It determines read complete and write complete from external facts under the rules in ADR-0001; connector `RUNNING` status and Source-offset REST responses are health or diagnostic evidence, never completion gates. Recovery, cancellation, discard, target generation, and rerun follow ADR-0006. Connect remains independently deployed and continues running while the DBX process is unavailable, but DBX may reconcile an execution only when its persisted intent and external identities remain provably continuous.

Every connector, topic, configuration fingerprint, routing snapshot, and cleanup request belongs to one immutable migration run. Connector names include run and box identity; topics include run identity and resolve to exactly one table migration unit. A box may share one Source and one Sink across compatible tables, but the topics, progress, validation, outcome, and cleanup remain table-owned.

DBX generates normalized connector configurations from an approved table write contract, execution requirements, and mandatory platform policy. V1 exposes no arbitrary connector-property pass-through. A dialect or database pair may supply typed requirements but cannot omit or override run isolation, exact identifier handling, the large-record envelope, `auto.create=false`, `auto.evolve=false`, evidence retention, or lifecycle gates.

## Failure and evidence

A record cannot be silently skipped, truncated, redirected to a successful side channel, or treated as migrated because Connect continued processing. V1 does not use a dead-letter queue as a success path. A record-level failure stops the truthfully attributable table or box scope, preserves topics and target data, and enters the diagnostic model in ADR-0005. Unknown or shared failures never invent table-level blame.

The 20 MiB source support boundary and 25 MiB Kafka transport envelope, including exact preflight, large-record isolation, worker and client settings, and external-cluster active proof, are owned by ADR-0003. Kafka retention never deletes unvalidated data. Admission, disk pressure, and rolling scheduling are owned by ADR-0002. A failure of Kafka, Connect, or Schema Registry therefore reduces availability but never relaxes correctness gates or selects another transport automatically.

## Deployment

The built-in deployment packages Kafka, Connect, and Schema Registry as separate processes beside DBX. A customer-managed deployment is accepted only after the fresh capability, configuration, secret-projection, and transport checks required by ADRs 0003, 0006, and 0008 succeed. Certification of a connector version or database pair never substitutes for these run-local checks.

JDBC Source and Sink versions, converters, drivers, worker policies, and normalized connector settings are compatibility inputs. Upgrades must rerun the real MySQL 8.0 → Kafka/Avro → PostgreSQL 15 certification path rather than relying on configuration acceptance or connector health alone.

## Consequences

The architecture keeps record movement out of the application and allows DBX restarts without automatically interrupting active connectors. It also makes Kafka, Connect, Schema Registry, their capacity, and their compatibility part of the correctness boundary. Deployments may be operationally heavier, but users receive one observable lifecycle, one diagnostic model, and one validation path.

Rows above the supported envelope, installations that cannot prove the required Kafka/Connect capabilities, and executions that lose external continuity fail closed. V1 accepts those hard boundaries rather than maintaining two progress models, two recovery protocols, and two classes of migration result.

## Rejected alternatives

- **Application-managed JDBC-to-JDBC copy** and **file staging** were rejected because either would create a second data plane with duplicate progress, retry, error, recovery, and validation semantics.
- **Bypassing Kafka for large rows** was rejected because size-dependent routing would make one table cross two incomparable execution paths.
- **Connect status or Source offsets as completion authority** was rejected because connectors remain running and offsets may be delayed or absent.
- **DLQ or silent skip as a green result** was rejected because an offline migration cannot claim completeness after dropping records.
- **Connector-owned table creation or schema evolution** was rejected because Connect cannot prove the approved cross-database write contract.
