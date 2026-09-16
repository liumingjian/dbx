# environment — v1 sub-spec

Runs the environment check (环境自检) E0–E8: it probes the host, evaluates the Kafka facts passed in, and concludes each item. It detects and explains, and never remediates.

**Read first**: ADR-0036 (the `environment` row, §Dependencies and purity); ADR-0018 (enforcement, session rule); ADR-0027 (with its status line); ADR-0031 §Deployment memory tiers, §Observation; ADR-0035 §Release version, §Master key; ADR-0021 §Items and sources; ADR-0022. Rulings: #89 items 3, 4, 5. CONTEXT.md terms: Environment check, Environment check item conclusion (Satisfied 满足 / Unsatisfied 不满足 / Inconclusive 无法判定), Runtime condition (运行状况), Run snapshot, Migration run, Attention required (需要人工处理), Admission, Release version (发行版本).

## Interface (`environment.api`)

- `check(scope, kafkaFacts, inputs)`: ADR-0036's `check(kafkaFacts)`, widened at reconciliation with what `orchestration` gathers (ADR-0036 `orchestration` row): scope (startup, pre-admission, export); the run's largest table size (E6); the expected key fingerprint from `workflow`'s installation record and the present one from `connection.fingerprint` (E8); the run's capability-check results to list. `kafkaFacts` comes from `connector.kafkaFacts`. Returns one result per item E0–E8, each with a conclusion, observed value, expected value, expected-configuration snapshot version, catalog version, and guidance when the conclusion is not Satisfied. It also returns the effective Connect heap it read. Effectful: it runs host probes (JMX, filesystem, Connector/J checksum) (ADR-0036 `environment` row; ADR-0027 §Evidence).
- `sampleReadings(kafkaFacts)`: the E2, E6 and E7 readings for the runtime condition's 10 s sampling, with no conclusions. Effectful (E7 probe). Added at reconciliation (ADR-0021 §Items and sources).

## Consumes

`diagnosis.validateCatalog` (pure) for E0. Kafka facts arrive as the `kafkaFacts` argument (type from `connector.api`), and `environment` never calls `connector` (ADR-0036 §Dependencies and purity). `orchestration` calls `check` and hands the results on to `workflow`, `condition.fold` and `diagnosis.package(inputs)` (ADR-0036 `orchestration` row).

## Obligations

**Boundary**
1. `environment` references no package of `connector` other than the types `kafkaFacts` carries, and calls no module's side effects. (ADR-0036 §Dependencies and purity; ADR-0018 enforcement)
2. There is no code path that restarts a container, rewrites configuration, mounts `docker.sock` or contacts a host agent. Every probe is read-only apart from E7's writability test. (ADR-0027 intro; ADR-0005 via ADR-0027)
3. JMX is read only at the Compose-network addresses of Connect and Kafka and never at a host-published port. (ADR-0031 §Observation)

**Conclusions**
4. Every item concludes exactly one of Satisfied, Unsatisfied or Inconclusive. (ADR-0027 §Conclusions)
5. A probe that errors, times out or returns unparseable data concludes Inconclusive and never Satisfied. (ADR-0027 §Conclusions)
6. The result exposes one verdict, "migrations may start", which is true only when every item is Satisfied. No input or flag waives an item, and E8 can never be waived. (ADR-0027 §Conclusions; #89 item 5; CONTEXT Environment check item conclusion)
7. Guidance on a non-Satisfied item names the item, the observed and expected values, the exact action, and who acts (the DBA or the server administrator). (ADR-0027 §Consequence of failure)
8. Each non-Satisfied item carries a stable diagnosis code in phase `ENVIRONMENT_CHECK`. Codes are never reused and are not counted in the external-translation rule families. (ADR-0027 §Diagnoses; ADR-0005 §Rule catalog; #89 item 1)
9. User-facing guidance text is zh-CN, keyed by message keys. (#89 item 6; ADR-0030)

**Items**
10. E0: the diagnosis catalog passes ADR-0005's startup validation through `diagnosis.validateCatalog` (unique codes, message keys, enum values, version constraints, compilable restricted regexes), and its version and hash match `release.json`. (ADR-0027 §Catalog; ADR-0005 §Rule catalog; ADR-0035 §Release version)
11. E1: a Connector/J JAR is present in the mounted drivers directory, and its `{version, SHA-256}` is an entry of the `release.json` allowlist, which holds certified 8.x versions only. A missing JAR or a checksum not on the allowlist concludes Unsatisfied. (ADR-0027 §Catalog; #89 item 4; ADR-0035 §Package and host)
12. E2: Kafka, Connect and Schema Registry become ready within a bounded readiness budget, judged from `kafkaFacts`. (ADR-0027 §Catalog)
13. E3: the Connect plugin inventory in `kafkaFacts` equals the `release.json` inventory. (ADR-0027 §Catalog; ADR-0035 §Release version)
14. E4: platform-level configuration from `kafkaFacts` is compared with the expected-configuration snapshot named by `release.json`. The snapshot covers exactly these categories: broker `message.max.bytes` and `replica.fetch.max.bytes`, topic auto-create disabled, the Connect client override policy, the Connect converter configuration, the Schema Registry compatibility mode (`BACKWARD`), the default replication factor, `min.insync.replicas`, and log retention. Values are read from the snapshot, not hard-coded. Per-connector configuration and worker/JVM flags are excluded. (#89 items 3, 4; ADR-0027 §Catalog; ADR-0032 §Consequences)
15. E5 (the memory tier): Unsatisfied when container-visible memory (`docker info` MemTotal) is below the threshold of the tier named by `DBX_MEMORY_TIER` — 8 GiB for the floor tier, 16 GiB for the recommended tier — or when the effective Connect or Kafka heap read from JMX `java.lang:type=Memory` differs from that tier's heap. Memory below the 8 GiB floor is refused by install (`release` I1), so E5's below-threshold case is memory that fell below its tier after install. (ADR-0031 §Deployment memory tiers, §Observation; ADR-0035 §Memory tier, §Host prerequisites; #98)
16. E5 reports the effective Connect heap it read. It is the heap that admission and the box target size use for the run. (ADR-0031 §Platform memory budget; #89 item 2)
17. E6: Kafka log-dir free space, from `describeLogDirs` in `kafkaFacts`, is at least 50 GB at startup and, before admission, at least twice the run's largest table. (ADR-0027 §Catalog; technical plan §11.2)
18. E7: the H2 metadata directory is writable and has headroom. (ADR-0027 §Catalog)
19. E8: the master key is present and its fingerprint matches the fingerprint in the installation record, both at startup and before admission. A missing key or a mismatch concludes Unsatisfied. Both fingerprints arrive in `inputs` (#89 item 5; ADR-0035 §Master key)
20. The result lists, and never re-evaluates, the per-run capability-check results passed in; there is no separate reachability item. (ADR-0027 §Catalog)
21. No item checks container memory limits, clock or timezone, or the bounded-read behaviour of ADR-0033. (ADR-0027 §Catalog; ADR-0033 §Proof after an upgrade)

**Evidence**
22. A pre-admission result carries, per item, the conclusion, observed and expected values, expected-snapshot version and catalog version, in a form `workflow` can freeze into the run snapshot unchanged. (ADR-0027 §Evidence; CONTEXT Run snapshot)
23. `check` is repeatable, so a fresh result taken at export sits beside the frozen one in the diagnostic package. (ADR-0027 §Evidence; ADR-0028 §Scopes)
24. The E2, E6 and E7 readings can be obtained by the runtime condition's 10 s sampling. `sampleReadings` serves that sampling and concludes nothing, so no continuous environment check exists. (ADR-0021 §Items and sources, §Considered options)

## Verification

- Boundary (1–3): L1 `check`, `EnvironmentArchTest` (ArchUnit: no `connector` side-effect types, no Docker client, JMX target restricted to the configured Compose hosts).
- Conclusions (4–9): L1, `EnvironmentContractTest`, covering the verdict fold, Inconclusive on probe failure, no waiver path, and guidance fields and codes per item.
- E0, E1, E7, E8 (10, 11, 18, 19): L1, `HostProbeTest` against temp directories, fixture catalogs and fixture `release.json` files.
- E2, E3, E4, E6 (12–14, 17): L1, `KafkaFactsEvaluationTest` over fixture `kafkaFacts` and a fixture expected-configuration snapshot.
- E5 (15–16): L1 `MemoryTierTest` for the tier comparison at both thresholds and for a heap that differs from the tier's; L2 `seamTest` `JmxHeapProbeSeamTest` against a real Kafka container's JMX.
- Scope (20–21): L1, `EnvironmentContractTest` checks the item list is exactly E0–E8 plus the capability-check entries.
- Evidence (22–24): L1, `EnvironmentContractTest` covering result shape and repeatability. The end-to-end freeze and export are proven at L3 `e2eTest` under `workflow` and `orchestration`.

## Slices

1. **api + contract skeleton.** Add the `environment.api` result types (item ids E0–E8, conclusion, observed and expected values, versions, guidance, code, effective heap), a `check(scope, kafkaFacts, inputs)` and `sampleReadings` that return Inconclusive for every item, `EnvironmentContractTest` for obligations 4–6 and 22, `EnvironmentArchTest`, and the README. Blocked by `connector` slice 1 (the `kafkaFacts` types).
2. **Kafka-facts items E2, E3, E4, E6.** Pure evaluators within the module, plus `KafkaFactsEvaluationTest`. Depends on slice 1.
3. **Filesystem items E0, E1, E7.** Catalog validation, Connector/J checksum against the allowlist, the H2 directory probe, and `sampleReadings`, plus `HostProbeTest`. Depends on slice 1; blocked by `diagnosis` slice 2 (`validateCatalog`).
4. **E5 memory tier.** JMX probe, tier comparison, and effective-heap output, plus `MemoryTierTest` and `JmxHeapProbeSeamTest` (L2). Depends on slice 1.
5. **E8 master key.** Compares the two fingerprints in `inputs`. Depends on slice 1.
6. **Guidance and codes.** Map each non-Satisfied item to its `ENVIRONMENT_CHECK` code and zh-CN message keys. Depends on slices 2–5, and is blocked by `diagnosis` slice 4 (the `ENVIRONMENT_CHECK` codes).

## Conflicts resolved

- ADR-0027 E5 "host memory at least 8 GB" → the memory-tier check against container-visible memory and the effective heaps (ADR-0031 §Observation; ADR-0035 amendment note).
- ADR-0031's ≥16 GiB tier against ADR-0035's "at least 18 GiB" → the threshold is 16 GiB of MemTotal; 18 GiB is guidance for the Docker Desktop allocation, which exceeds the MemTotal it exposes (#98).
- ADR-0036's row "E0 to E7, the memory tier, and the master-key item" read as a separate tier item → the tier item *is* E5 (ADR-0031 §Observation), and the master-key item is E8 (#89 item 5).
- ADR-0027's catalog E0–E7 has no key item, and ADR-0035 says "an environment check item" → E8 (#89 item 5; ADR-0027 status line).
- ADR-0027 "JVM heap unobservable" → heap is read over JMX (ADR-0031), while worker/JVM flags stay out of E4 (ADR-0032 §Consequences).
- ADR-0003's active capability check for external Kafka → suspended in v1 and replaced by this check (ADR-0003 status note; technical plan §4 step 2).
- corpus-audit §5, where `environment` consumes `connector` → `kafkaFacts` is passed in, and there is no such call (ADR-0036 §Dependencies and purity).
- ADR-0005, where the phase list lacks `ENVIRONMENT_CHECK` and there are "20" families → the phase is added (ADR-0027) and there are 21 families, with environment codes counted in none of them (#89 item 1).

- Undecided list-or-evaluate of capability-check results → lists them (ADR-0027 §Catalog "lists"); `orchestration`'s connection check concludes them.

## Implementer decides

- E2 readiness budget and E7 headroom threshold: bounded values in the release configuration, shown as the item's expected value (ADR-0027 §Evidence).
- How E5 reads container-visible memory: never through `docker.sock` or a host agent (ADR-0027).

## Open items

None. D-25 is settled in [#98](https://github.com/liumingjian/dbx/issues/98): the ≥16 GiB tier's threshold is 16 GiB of MemTotal, and it lives in obligation 15.
