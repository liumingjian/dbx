# environment

The environment check and the memory tier: host probes (JMX, filesystem, Connector-J checksum) plus evaluation of the Kafka facts passed in — `check(kafkaFacts)`.

- Sub-spec: [docs/spec/environment.md](/docs/spec/environment.md)
- ADRs: [ADR-0018](/docs/adr/0018-backend-module-boundaries-and-agent-working-surface.md), [ADR-0021](/docs/adr/0021-runtime-condition-observes-and-never-adjudicates.md), [ADR-0022](/docs/adr/0022-verification-ladder-and-explicit-golden-updates.md), [ADR-0027](/docs/adr/0027-environment-check-detects-and-explains-without-host-control.md), [ADR-0031](/docs/adr/0031-platform-memory-budget-admission-and-bounded-in-flight-records.md), [ADR-0035](/docs/adr/0035-offline-release-package-one-release-version-and-gated-in-place-upgrade.md), [ADR-0036](/docs/adr/0036-module-table-owns-every-v1-obligation.md)
