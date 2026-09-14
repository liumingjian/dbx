# Diagnostic package: two scopes, a manifest, and no data values

> Amends ADR-0005's "Operator presentation and support" (#56). ADR-0005's inclusion and exclusion lists still hold.

Customers are air-gapped and DBX never phones home, so the **diagnostic package** (诊断包) is the only remote-support channel. We decided it has two scopes, can be exported at any time, never contains a data value even on request, and ships with a human-readable manifest so a DBA can see what leaves the network before sending it.

## Shape

- **Scopes.** An *installation package* covers the environment check (the latest startup conclusions and a fresh check taken at export, per ADR-0027), the runtime condition's change record and orphan `dbx-` resources (ADR-0021), and versions. A *run package* is an installation package plus one migration run's snapshot, timeline, error occurrences, diagnoses, and evidence from ADR-0005. Because every run package carries the installation part, support always sees the environment.
- **Trigger.** Either scope can be exported at any time: the installation package from 系统设置 (system settings), the run package from that run's 运行快照 (run snapshot) tab. An unknown or conflicting diagnosis additionally shows a prominent export action on its error card. Export stays available when a known diagnosis looks wrong, because the translation layer can be wrong too.
- **No data values, no opt-in.** The package carries coordinates only: schema, table, column, source and target type, and observed length or magnitude. It carries no record value and no primary-key value, and there is no "include the failing row" choice. Support replies with a query the DBA runs locally to find the row. Raw Connect REST responses and traces pass through the same value scrubber as ADR-0005's exception chain, for example the `(id)=(123)` in `Key (id)=(123) already exists`. A segment the scrubber cannot prove clean is replaced whole by a placeholder.
- **Identifiers and topology are included.** Host names or IPs, ports, database usernames, and database, table, and column names appear as they are. Connection faults need host and port, and a pseudonymized name would break the local query. The manifest names these fields explicitly.
- **Manifest.** Each package holds a Chinese `README` manifest. It lists every file, what category of content each holds, what the package excludes, and what was truncated. The UI shows the same manifest before export. Its job is to earn trust before the DBA sends the package, not to help with debugging. Everything else is machine-readable JSON with checksums.
- **Reproduction.** The package records the DBX release version, the diagnosis catalog version and the hash of the catalog file, and the expected-configuration snapshot version. Support already holds the catalog for each release, and the hash reveals a catalog edited in place.
- **Bound.** A package is at most 50 MB. When it would exceed that, DBX drops coalesced progress samples first and then older timeline entries. It keeps every terminal evidence item, and the manifest states what was cut.
- **Audit.** Every export appends one event recording its time, scope, and package checksum. The event goes on the run's timeline for a run package, and beside the runtime condition's change record for an installation package. It changes no state.

## Considered options

- **Opt-in or default-included failing-row values.** Rejected because a non-developer DBA would make a data-export compliance call in one click, and a package cannot be recalled once sent.
- **Pseudonymized hosts and identifiers with a local mapping file.** Rejected because it breaks the local query and gives the DBA one more artifact to keep.
- **Export only on unknown diagnoses.** Rejected because it assumes every translated diagnosis is right.
- **A customer-readable report.** Rejected as cost without benefit. The manifest is the only part the customer needs to read.
