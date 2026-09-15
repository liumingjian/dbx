---
status: accepted (amends ADR-0022's ladder with L4 and ADR-0031's tier reading; specifies ADR-0006's deployment secret for the built-in deployment)
---

# Offline release package, one release version, and in-place upgrade gated on no nonterminal run

DBX can never phone home, so everything it runs must arrive in one package and install with no network. Any upgrade recreates the Connect container, and ADR-0032 fails every running box when Connect restarts. So an in-place upgrade cannot preserve a migration run that is in progress. We decided: one offline tarball per platform, one 发行版本 (release version) that pins every component, upgrades refused while any migration run is nonterminal, and a one-command rollback limited to the 回退窗口 (rollback window) ([#81](https://github.com/liumingjian/dbx/issues/81)). 部署可以复杂，使用必须简单: the script carries the complexity, and the DBA never meets Kafka.

## Package and host

- **Package.** `dbx-<version>-<os>-<arch>.tar.gz` holds the `docker save` image tarballs, `compose.yaml`, an `.env` template, one `dbx` script (`install`, `upgrade`, `rollback`), `SHA256SUMS`, `THIRD-PARTY-LICENSES`, the SBOM and `release.json`. Images arrive by `docker load`. Install and operation never touch the network, and Compose runs with `pull_policy: never`.
- **v1 platform.** v1 targets macOS on Apple Silicon with Docker Desktop and ships `linux/arm64` images. The `cp-kafka`, `cp-kafka-connect` and `cp-schema-registry` images publish arm64 and amd64 variants. Other Docker runtimes (OrbStack, Colima) are unsupported. Linux x86_64 servers are a later release target outside v1. Supporting one means building one more artifact and rerunning certification and ADR-0031's measurements; it does not change this design.
- **Host prerequisites.** The script checks each of these and, if one fails, explains it in Chinese and exits:
  - the CPU architecture;
  - Docker Desktop is running;
  - Engine is 29 or later;
  - Compose is v2.20 or later;
  - "Start Docker Desktop when you sign in" is enabled, because without it `restart: unless-stopped` never runs after the Mac reboots.

  These checks are prerequisites for starting the stack at all. They are not environment check items: ADR-0027's checks still run at startup and before admission.
- **Install directory.** The default is `~/dbx/`, which a script flag can change. Docker Desktop shares `/Users` by default.

  | Path | Holds |
  |---|---|
  | `releases/<version>/` | One unpacked package per release. The current and previous releases are kept. |
  | `current` | Symlink to the running release. |
  | `secrets/` | The master key and the tombstone ledger. |
  | `backups/` | H2 backups. |
  | `drivers/` | The customer's Connector/J. |

  Kafka, Schema Registry and H2 data live in Docker named volumes.
- **Memory tier.**
  - The script reads the memory containers can see (`docker info` MemTotal), not the Mac's physical memory. It picks the highest ADR-0031 tier that memory satisfies and writes it to `.env` as `DBX_MEMORY_TIER`.
  - The DBA confirms the tier once. They may choose a lower tier, never a higher one.
  - ADR-0031's tier thresholds are read as container-visible memory. They are never loosened to fit Docker Desktop's default allocation.
  - Example: on the reference Mac mini, Docker Desktop exposes 15.6 GiB, so the script picks the 8 GiB tier. It then prints how to raise Docker Desktop's memory to at least 18 GiB for the recommended tier.

## Release version

- One `MAJOR.MINOR.PATCH` release version names the whole platform. Operators never see component versions.
- `release.json` is the bill of materials. It pins:
  - every image, by digest rather than by tag;
  - the JDBC Source/Sink and converter versions;
  - the Connector/J checksum allowlist;
  - the diagnosis catalog version and hash;
  - the expected-configuration snapshot version;
  - the reference throughput bands (ADR-0034);
  - the Flyway target schema version.

  Environment check items E3 and E4 compare against it.
- Where the version appears:
  - 关于 (About) shows the release version only, with the bill of materials collapsed;
  - the diagnostic package carries the whole `release.json` (ADR-0028);
  - the script prints the release version on every command.

## In-place upgrade

- **Refused while any migration run is nonterminal.** `dbx upgrade <package>` does the following in order:
  1. Verifies checksums and loads the images.
  2. Asks DBX's local API whether any migration run is nonterminal. If any is, it lists those runs in Chinese and exits without draining them or waiting for them.
  3. Stops the stack, repoints `current`, and starts the new release. DBX's startup then takes the pre-Flyway H2 backup and migrates (ADR-0004).

  The same refusal is enforced a second time at startup: a new DBX that finds a nonterminal run from an older release applies ADR-0008. That run becomes non-automatically recoverable, with its evidence preserved.
- **Allowed while a task write freeze is open, between runs.** The freeze is a commitment about the source database, not about the platform. Its next run gets a new execution signature under the new release. Signatures are frozen per run anyway (ADR-0002), and drift checks compare against baselines held in H2 (ADR-0024).
- **Volumes are kept.** The script never deletes the Kafka, Registry or H2 volumes. Startup reconciliation removes leftover topics and subjects under the existing cleanup rules. A future release that crosses an incompatible Kafka storage format declares the volume rebuild in its `release.json`, and its own upgrade performs it, still behind the same gate. v1 designs nothing for this.

## Failed upgrade and the rollback window

- `dbx rollback` is one command, run by whoever ran the upgrade. It:
  1. restores the pre-upgrade H2 backup, after proving tombstone-ledger continuity as ADR-0006 requires;
  2. repoints `current` to the previous release;
  3. starts the previous release's images.

  It never reverses a Flyway migration (ADR-0004, ADR-0012).
- **The rollback window** runs from the end of an upgrade until the first migration run is admitted under the new release. After that, rollback refuses, and the way out is a fixed forward release. Restoring the pre-upgrade backup at that point would silently discard post-upgrade runs, their outcomes, and which target tables DBX owns.

## Master key

- **First install.**
  - The script generates a random 256-bit key at `secrets/master.key` (mode 0600).
  - The key reaches DBX as a mounted file, never as an environment variable or through `.env`, which `docker inspect` exposes.
  - H2 stores only the key's fingerprint.
  - ADR-0006's tombstone ledger lives beside the key in `secrets/`, outside H2 and its backups.
- **Upgrade and rollback** never write `secrets/`; they only compare fingerprints. A missing or mismatched key makes an environment check item conclude 不满足 (unsatisfied): DBX serves the UI and refuses migrations (ADR-0027).
- **What the DBA keeps safe.** The DBA keeps the `secrets/` directory, copied off the machine. The script says so when an install finishes.
- **If the key is lost,** no stored credential version can be decrypted. The DBA re-enters each data source's password, which creates new credential versions (ADR-0006). History, contracts and baselines stay readable.

## Verification

ADR-0022 gains **L4 `packageTest`**. It runs on the Mac through `rexec` before every release tag is pushed, and a release without a green L4 receipt is not published. It does not replace L3. L4 runs these steps in order:

1. Build the package.
2. **Fresh install.** Install from the tarball with no registry and no network, then run a smoke migration.
3. **Upgrade.** Install the previous release, then upgrade to the new one. Assert that H2 migrated, the key fingerprint matches, and the history survived.
4. **Rollback.** Assert that the previous release comes back with its pre-upgrade history.

The first release has no previous release, so it runs only the fresh-install step.

## Considered options

- **rpm/deb with systemd.** Rejected: it adds a host-side supervisor, which ADR-0027 rules out.
- **Draining open runs before upgrading.** Rejected: a drain can take hours, and a bulk box may run for up to 24 hours (ADR-0001). A forced restart fails the boxes anyway (ADR-0032). Refusing the upgrade is simpler and easier to explain.
- **Refusing upgrades for the whole life of a task write freeze.** Rejected: that could block security fixes for weeks over a commitment that concerns the source database, not the platform.
- **Choosing the tier by the Mac's physical memory.** Rejected: containers see only the Docker VM's memory, so the script could pick a tier whose containers the kernel then kills.
- **Rollback at any time.** Rejected: it would silently drop post-upgrade runs.
- **arm64 and x86_64 both in v1.** Rejected: it doubles certification and calibration, and the machine v1 runs on is arm64.
