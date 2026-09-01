# DBX

DBX v1 is an offline, one-time full-migration platform for **MySQL 8.0 → PostgreSQL 15**. It presents migration as independently observable table migration units while using Kafka and Kafka Connect as an internal, run-isolated data plane.

The v1 goal is a controlled path for a DBA: discover source metadata, review automatic mappings and exact preflight evidence, approve a platform-owned target DDL contract, transfer data, validate each table, and retain actionable diagnostic evidence. DBX prioritizes correctness and explicit boundaries over silent coercion or broad configurability.

## Canonical documentation

Read these in order before implementation:

1. [`CONTEXT.md`](CONTEXT.md) — canonical domain language.
2. [`docs/technical-plan.md`](docs/technical-plan.md) — integrated v1 architecture, mapping, interfaces, operations, limits, and acceptance plan.
3. [`docs/adr/`](docs/adr/) — architectural decisions and their consequences.

When the technical plan and an ADR disagree, stop and reconcile the documents; do not choose silently.

## V1 at a glance

- Kafka + Confluent JDBC Source/Sink is the sole record path; DBX owns orchestration, completion, recovery, validation, and cleanup.
- Avro + Schema Registry fixes typed transport.
- DBX generates and executes target DDL from an immutable table write contract; Sink uses `auto.create=false` and `auto.evolve=false`.
- A table migration unit owns durable progress and results; a box is a disposable scheduling artifact.
- Exact source preflight enforces a 20 MiB (20,971,520-byte) value and row-payload boundary; Kafka uses a separate 25 MiB (26,214,400-byte) transport envelope.
- V1 has no CDC, no single-table sharding, no data-level checkpoint resume, and no second path for oversized records.
- A rerun is a new migration run and a complete copy of each selected table.
- Green means all enabled v1 validation checks passed under an externally maintained source write freeze; it does not claim full row-by-row proof.

## Project status

The v1 domain and technical specification are complete enough to hand off to implementation. Research, task, and prototype branches remain provenance and reproducibility evidence; production code should follow the canonical documents above.

## License

Project licensing is not yet declared. Third-party distribution constraints, including Confluent components and customer-provided MySQL Connector/J, are documented in the technical plan and must be resolved in every release package.
