# release — v1 sub-spec

Ships DBX as one offline package per platform under one release version (发行版本), and installs, upgrades and rolls it back with one `dbx` script and one Compose file. No Java package, no ArchUnit rule (ADR-0036).

**Read first**: ADR-0035, ADR-0036 (`release` paragraph), ADR-0031 §Deployment memory tiers, ADR-0022, ADR-0027, ADR-0032, ADR-0006 (master key, tombstone ledger), ADR-0003 §transport, #89 items 3, 4, 5, 9. CONTEXT.md terms: Release version (发行版本), Rollback window (回退窗口), Environment check (环境自检), Platform memory budget (平台内存预算), 迁移平台, Diagnostic package (诊断包), Migration run, Task write freeze.

## Artifacts

- `dbx-<version>-<os>-<arch>.tar.gz`: image tarballs, `compose.yaml`, `.env` template, `dbx`, `SHA256SUMS`, `THIRD-PARTY-LICENSES`, SBOM, `release.json` (ADR-0035 §Package).
- `release.json`: the bill of materials. Read by `environment` (E1, E3, E4), `diagnosis` (carried whole) and `web` (关于) (ADR-0035 §Release version, ADR-0028).
- Release configuration: the expected-configuration snapshot E4 compares; `release.json` pins its version (#89 item 4).
- `compose.yaml` + `.env`: the four services, every tier-dependent heap driven by `DBX_MEMORY_TIER` (ADR-0031 §Consequences).
- `dbx install [--dir]`, `dbx upgrade <package>` (gated), `dbx rollback` (window only). Every command prints the release version; operator output is Chinese (ADR-0035).
- Install directory (default `~/dbx/`): `releases/<version>/`, `current`, `secrets/`, `backups/`, `drivers/`. Kafka, Schema Registry and H2 data sit in Docker named volumes (ADR-0035 §Install directory).

## Consumes

Specified in those modules' sub-specs; slice numbers are theirs.

- `web` slice 3: the nonterminal-run query upgrade calls (ADR-0036 `web` row, ADR-0035 §In-place upgrade step 2); a local-API read of the installation record (#89 item 5).
- `workflow` slices 3, 8, 9: the single installation record in H2 (release version, key fingerprint, rollback-window state: opened at upgrade end, closed at first admission with run id and time); the pre-Flyway startup backup (ADR-0004); hourly backups keeping the last 48 (#89 items 5, 9; ADR-0036 `workflow` row).
- `environment` slices 3, 4, 5: E1 against the allowlist, E3/E4 against `release.json` and the release configuration, the memory-tier item against `DBX_MEMORY_TIER` and JMX heaps, E8 key present with matching fingerprint (ADR-0027, ADR-0031 §Observation, #89 items 4, 5).

## Obligations

**Package (P)**
- P1. The package holds exactly ADR-0035's members; images arrive by `docker load`; every service sets `pull_policy: never`; install and operation make no network call (ADR-0035 §Package).
- P2. v1 ships one `linux/arm64` package for macOS on Apple Silicon with Docker Desktop (ADR-0035 §v1 platform).
- P3. No bundled MySQL Connector/J; the JDBC connector package is curated of unused drivers (technical plan §11.4).
- P4. `SHA256SUMS` covers every other member; install and upgrade verify it before any other step (ADR-0035 §In-place upgrade).

**`release.json` and release configuration (R)**
- R1. `release.json` pins every image by digest (incl. `cp-kafka-connect:7.9.0`, ADR-0032), JDBC Source/Sink and converter versions, the Connector/J allowlist, diagnosis catalog version and hash, expected-configuration snapshot version, reference throughput data, Flyway target schema version; nothing operator-facing shows component versions (ADR-0035 §Release version).
- R2. Allowlist entries are `{Connector/J version, SHA-256}`, 8.x only, each with its own L3 bounded-read scenario (#89 item 4, ADR-0033 §Proof after an upgrade).
- R3. Reference throughput data: three shape bands, two anchor row lengths, the shared ceiling and the reference machine spec, versioned together (ADR-0034).
- R4. The release configuration holds only platform-level values observable through AdminClient or service REST: broker `message.max.bytes` and `replica.fetch.max.bytes` = 26214400, topic auto-create disabled, Connect client overrides allowed, converter config, SR compatibility `BACKWARD`, default replication factor, `min.insync.replicas`, log retention; no per-connector config (#89 items 3, 4; ADR-0003; ADR-0010).
- R5. The Compose file applies exactly the release configuration's values.

**Compose (C)**
- C1. From `DBX_MEMORY_TIER`: Connect heap 3 / 6 GiB and Kafka heap 1 / 2 GiB at the 8 / ≥16 GiB tiers; Connect `-XX:MaxDirectMemorySize=512m` (ADR-0031 §Deployment memory tiers).
- C2. All four JVMs run `-XX:+ExitOnOutOfMemoryError` under `restart: unless-stopped`; Connect's healthcheck is a REST probe (ADR-0032).
- C3. Connect and DBX JVMs run in UTC (technical plan §6.5).
- C4. Connect and Kafka JMX ports are on the Compose network only, never published (ADR-0031 §Observation).
- C5. `secrets/master.key` reaches DBX as a mounted file, never an env var or `.env`; the ConfigProvider directory is mounted for DBX and Connect only; `drivers/` for Connect; `docker.sock` never (ADR-0035 §Master key, ADR-0006, ADR-0027).

**Install (I)**
- I1. Before starting anything, install checks CPU architecture, Docker Desktop running, Engine ≥ 29, Compose ≥ v2.20 and "Start Docker Desktop when you sign in"; each failure is explained in Chinese with a nonzero exit; these are not environment check items (ADR-0035 §Host prerequisites).
- I2. Install picks the highest ADR-0031 tier that `docker info` MemTotal satisfies (never physical memory), prints how to raise Docker Desktop's memory if the recommended tier is missed, takes one DBA confirmation allowing only a lower tier, and writes `DBX_MEMORY_TIER` to `.env` (ADR-0035 §Memory tier).
- I3. First install only: a random 256-bit key at `secrets/master.key`, mode 0600; the final message tells the DBA to copy `secrets/` off the machine (ADR-0035 §Master key).
- I4. Install creates the directory layout, points `current` at the unpacked release, and starts the stack (ADR-0035 §Install directory).

**Upgrade (U)**
- U1. Upgrade runs ADR-0035's steps in order: verify and load; query nonterminal runs; stop, repoint `current`, start.
- U2. Any nonterminal migration run: upgrade lists them in Chinese and exits without draining or waiting, with `current` and the stack unchanged. An open task write freeze with no nonterminal run does not block (ADR-0035 §In-place upgrade).
- U3. After upgrade `releases/` holds only the new and the previous release (ADR-0035 §Install directory).
- U4. Upgrade and rollback never delete the Kafka, Registry or H2 volume, never write `secrets/`, never reverse a Flyway migration (ADR-0035, ADR-0004).

**Rollback (B)**
- B1. Rollback reads the rollback-window state and, once the window is closed, refuses and names the fixed-forward-release path (ADR-0035 §Failed upgrade, #89 item 5).
- B2. Inside the window, in order: prove tombstone-ledger continuity, restore the pre-upgrade H2 backup, repoint `current` to the previous release, start its images (ADR-0035 §Failed upgrade, ADR-0006).

**Release gate (G)**
- G1. A release tag is pushed only with a green L4 `packageTest` receipt from the Mac via `rexec`, in addition to L3 (ADR-0022).
- G2. Certification proves the Connect restart-marker protocol on the pinned Connect image (ADR-0032 §Consequences).

## Verification

All rungs run on the Mac through `rexec`. L4 `packageTest` scenarios, in order (ADR-0035 §Verification):

- `build` (P1–P4, R1–R5): members, checksums, and image digests that match `release.json`.
- `freshInstall` (C1–C5, I1–I4): offline install with no registry, a smoke migration, tier and effective heaps agree, key mode 0600, no key in `docker inspect`.
- `upgrade` (U1–U4): install the previous release, upgrade; H2 migrated, key fingerprint matches, history survived, volumes and `secrets/` unchanged. Variant: one nonterminal run means refusal and no change.
- `rollback` (B1–B2): the previous release returns with its pre-upgrade history. Variant: one admitted run first means refusal.
- The first release runs only `build` and `freshInstall` (ADR-0035 §Verification).
- R2 is checked by the per-entry L3 `e2eTest` bounded-read scenario; G2 by the restart-detection `e2eTest` on the release images.

## Slices

1. **Package build and `release.json`**: P1–P4, R1–R3, L4 `build`. No blockers.
2. **Compose, `.env` template, release configuration**: C1–C5, R4, R5. Blocked by slice 1.
3. **`dbx install`**: I1–I4, the L4 harness, `freshInstall`. Blocked by slice 2; `environment` slices 3 (E1), 4 (tier), 5 (E8); `workflow` slice 3 (installation record); `orchestration` slice 5 and `web` slice 4 (smoke migration); D-25, D-26.
4. **`dbx upgrade`**: U1–U4, L4 `upgrade`. Blocked by slice 3; `web` slice 3 (nonterminal-run query, installation read); `workflow` slice 9 (window opens).
5. **`dbx rollback`**: B1–B2, L4 `rollback`. Blocked by slice 4; `workflow` slices 8 (backups) and 9; `orchestration` slice 5 (closes the window at first admission); D-23, D-24.
6. **Release gate**: G1–G2, the tag-push check for an L4 receipt, certification on the release images. Blocked by slice 3; `connector` slice 7 (marker).

## Conflicts resolved

- ADR-0003's 4 GiB Connect heap on ≥ 8 GiB → tier heaps 3 / 6 GiB, thresholds read as container-visible memory (ADR-0031, ADR-0035 notes).
- ADR-0027's E5 "host memory ≥ 8 GB" → the memory-tier item, read against `docker info` MemTotal (ADR-0031, ADR-0035).
- ADR-0035's unnumbered master-key item → E8, never waivable (#89 item 5).
- ADR-0035/0036's "rollback-window fact and key fingerprint" → one installation record that also holds the release version (#89 item 5).
- ADR-0010's "one tested mode" → `BACKWARD` (#89 item 3).
- ADR-0027's "release allowlisted checksum" → `{version, SHA-256}` entries, 8.x only (#89 item 4).
- The corpus audit's proposed `release` home for OOM flags, UTC and key placement → a non-Java sub-spec, backend halves in `web`, `workflow`, `environment` (ADR-0036).

- Old-release nonterminal run at startup, ownerless here → `orchestration` recovery marks it not automatically recoverable (`orchestration` obligation 40; ADR-0035; ADR-0008).
- Upgrade and rollback proof before a second release → the first release runs only `build` and `freshInstall` (ADR-0035 §Verification).

## Implementer decides

- Schema Registry and DBX heap flags: within ADR-0031's RSS budgets (0.75 / 1 GiB), set from `DBX_MEMORY_TIER`.
- R4 values (replication factor, `min.insync.replicas`, retention, converter settings): satisfiable by the single-node Kafka and observable via AdminClient or service REST (#89 item 4; ADR-0035).

## Open items

- **D-23** (T6): who decrypts the per-backup DEK and proves ledger continuity when `dbx rollback` restores H2 outside a running DBX. Blocks slice 5.
- **D-24** (T6): rollback when the new release never starts, so the local API cannot report the window. Blocks slice 5.
- **D-25** (T7): the MemTotal threshold of the ≥16 GiB tier (ADR-0031 16 GiB vs ADR-0035's 18 GiB advice). Blocks slice 3.
- **D-26** (T7): below the 8 GiB tier, install refuses, or installs and E5 concludes 不满足. Blocks slice 3.
