# The backend module table owns every v1 obligation

Supersedes the module table and the pure-core list of ADR-0018. Decided in #83. ADR-0018's enforcement rules, module context, session rule, and abstractions not drawn still hold.

ADR-0018's eleven modules predate ADR-0019 to ADR-0035, so several obligations had no owner. v1 has fourteen Java packages, three more than ADR-0018. `connection`, `environment`, and `condition` each have their own side-effect shell or pure fold, and none of them fits an existing row without making that module the widest one. Release is a sub-spec, not a package.

## Modules

| Package | Owns | Interface |
|---|---|---|
| `dialect` | Unchanged from ADR-0018 | Unchanged |
| `preflight` | Large-record and byte-estimate probes (ADR-0003). Kafka probes are suspended in v1. Row count is the source baseline, not a preflight probe | Plans and evaluates probes → evidence |
| `contract` | Table write contract (ADR-0011), read-only rendering (ADR-0026), and the projected list of objects an abandonment would drop (ADR-0023) | `assemble`, `renderDdl`, `prove`, projected list |
| `scheduling` | Box plan, admission (ADR-0002, ADR-0031), and the duration estimate and remaining time (ADR-0019, ADR-0034) | `plan`, `admit`, `predict(run-history rates)` |
| `connector` | The data-plane client: Connect REST, Kafka AdminClient, Schema Registry REST. Also owns the connector lifecycle; deleting connectors, topics, and subjects; projecting secrets into the ConfigProvider file (ADR-0006); observing Connect restarts (ADR-0032); the routing snapshot, normalized configuration, configuration fingerprint, and execution signature, derived in one place (ADR-0008); and the completion judgement, including the 24 h box limit (ADR-0001) | Box start/stop, deletes, data-plane facts, `judge` |
| `validation` | Data validation, source-baseline capture and drift checks (ADR-0006, ADR-0024), and manual deterministic sampling plans | `plan`, `evaluate`, baseline and drift plans |
| `diagnosis` | Error translation (ADR-0005) and diagnostic package content: scrubbing, manifest, bound (ADR-0028) | `diagnose`, `package(inputs)` |
| `gateway` | Frozen connections, transactions, timeouts, result-schema checks, the connection-check probe, and advisory locks | Executes typed SQL plans |
| `connection` | Credential encryption, per-backup DEK wrapping and erasure, the master key, and TLS material (ADR-0006). Holds no H2 tables | Encrypt, decrypt, wrap, erase |
| `environment` | The environment check: E0 to E7, the memory tier, and the master-key item (ADR-0027, ADR-0031, ADR-0035). Runs host probes (JMX, filesystem, Connector-J checksum) and evaluates the Kafka facts passed in | `check(kafkaFacts)` |
| `condition` | Runtime condition: items, the worst-value fold, the admission stop signal, the change record, the reclaimable-occupancy (待回收占用) aggregate, and restart counters (ADR-0021, ADR-0032) | `fold` |
| `workflow` | ADR-0018 row, plus these aggregates (extends ADR-0004): migration draft and stage gating (ADR-0020); task write freeze, task conclusion, and split snapshot (ADR-0024); database connections, credential versions, and the tombstone ledger (ADR-0006); target leases and target generation (ADR-0006, ADR-0023); rollback-window fact and key fingerprint (ADR-0035); backups; and the condition change record | Unchanged |
| `orchestration` | ADR-0018 row, plus: drivers for cleanup, discard, and abandonment, including the cleanup-retry background loop; diagnostic package assembly and export audit; gathering the inputs for `condition` and `environment`; the connection check (decrypt, then probe through `gateway`); the lease and advisory-lock guard as one step; the "continue admission" (继续准入) command; and triggering sampling | Use cases called by `web` |
| `web` | HTTP adapters, the installation-scoped status channel beside `RunProgressSource` (ADR-0016, ADR-0021), and the nonterminal-run query used by upgrade | — |

Release and upgrade (ADR-0035) are a non-Java `release` sub-spec: the script, Compose file, and `release.json`. It has no Java package and no ArchUnit rule. Its backend obligations sit in the `web`, `workflow`, and `environment` rows above.

## Dependencies and purity

- `workflow → connection` is one-way: backups use its crypto. `connection` never persists, so no cycle forms. `gateway` receives decrypted material from `orchestration` and does not depend on `connection`.
- No deep module calls another module's side effects, without exception. `orchestration` fetches Kafka facts through `connector.api` and passes them to `environment`. It gathers condition items from `connector`, `environment`, and `workflow`, then calls `condition.fold`.
- **Pure:** `dialect`, `contract`, `scheduling` (including `predict`), `diagnosis` (including `package`), `condition`, `connector.judge`, and the `validation` plan and evaluation halves, including the baseline and drift plans.
- **Effectful shell:** `gateway`; `connector`'s data-plane clients and ConfigProvider file; `workflow` repositories; `connection` (reads the master-key file); `environment` (host probes).

## Consequences

- The v1 spec has one sub-spec per Java package (fourteen), plus `release` and `frontend`.
- `orchestration` now schedules background jobs (the cleanup retries), and its README grows accordingly.
- Admission paused (准入已暂停) is a stop reason for admission, not a run status: the run stays running.

## Considered options

- **Put connections into `gateway`.** Rejected because crypto, the ledger, and connection CRUD would make it the widest module.
- **Give `connection` its own H2 tables.** Rejected because writes would either bypass the single-writer queue or create a `workflow ⇄ connection` cycle.
- **A separate `kafka` module.** Rejected because Kafka is a private implementation detail. Splitting the clients would spread the cleanup deletes across two modules.
- **Put `environment` inside `connector`.** Rejected because JMX, filesystem, and checksum probes are not data-plane calls.
- **Let `environment` call `connector.api` as an exception.** Rejected because the no-side-effect-calls rule stays unconditional.
- **Put the runtime condition fold in `orchestration`.** Rejected because it would be a second concern in an already-wide module, and the fold is pure.
- **Split the routing, fingerprint, and signature derivation across three owners.** Rejected because three derivation paths drift.
- **Put the source baseline in `preflight`.** Rejected because ADR-0003 says preflight is not the baseline.
