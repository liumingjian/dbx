---
status: accepted (amends ADR-0022's ladder with L4 and ADR-0031's tier reading; specifies ADR-0006's deployment secret for the built-in deployment; per [#89](https://github.com/liumingjian/dbx/issues/89): the master-key item is ADR-0027's E8, and one installation record in H2 holds release version, key fingerprint, and rollback-window state; leftover cleanup never touches orphans (ADR-0001); per [#97](https://github.com/liumingjian/dbx/issues/97): rollback repoints and starts first and the previous release restores itself, the rollback window is mirrored to a script-readable file, and `dbx upgrade` pins a labelled pre-upgrade backup)
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
  - "Start Docker Desktop when you sign in" is enabled, because without it `restart: unless-stopped` never runs after the Mac reboots;
  - container-visible memory (`docker info` MemTotal) is at least 8 GiB, ADR-0031's floor tier ([#98](https://github.com/liumingjian/dbx/issues/98)). Below it no tier exists, so the script has no `DBX_MEMORY_TIER` to write; and a stack whose Connect and Kafka heaps exceed the VM's memory would be OOM-killed into a restart loop rather than serve a UI that could explain itself. The message names the observed MemTotal and tells the DBA to raise Docker Desktop's memory to at least 10 GiB and run install again.

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
  - ADR-0031's tier thresholds are read as container-visible memory: at least 8 GiB for the floor tier and at least 16 GiB for the recommended one. They are never loosened to fit Docker Desktop's default allocation.
  - Docker Desktop's configured allocation is not the MemTotal it exposes, so the figure the script asks the DBA for is the threshold plus 2 GiB: at least 10 GiB to reach the floor tier, at least 18 GiB to reach the recommended one. These are guidance in a message, never thresholds ([#98](https://github.com/liumingjian/dbx/issues/98)).
  - Example: on the reference Mac mini, Docker Desktop exposes 15.6 GiB, short of the 16 GiB threshold, so the script picks the 8 GiB tier. It then prints how to raise Docker Desktop's memory to at least 18 GiB for the recommended tier.

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
  3. Asks the same API, while the old release is still running, for a **pre-upgrade backup**: one labelled backup taken at this moment. This is the backup rollback restores. The hourly backup is not enough, because it may be nearly an hour stale, and the new release's pre-Flyway backup may never be taken at all if the new release never starts.
  4. Stops the stack, repoints `current`, and starts the new release. DBX's startup then takes the pre-Flyway H2 backup and migrates (ADR-0004).
  5. Writes the rollback-window file (below), naming the pre-upgrade backup from step 3.

  The same refusal is enforced a second time at startup: a new DBX that finds a nonterminal run from an older release applies ADR-0008. That run becomes non-automatically recoverable, with its evidence preserved.
- **Allowed while a task write freeze is open, between runs.** The freeze is a commitment about the source database, not about the platform. Its next run gets a new execution signature under the new release. Signatures are frozen per run anyway (ADR-0002), and drift checks compare against baselines held in H2 (ADR-0024).
- **Volumes are kept.** The script never deletes the Kafka, Registry or H2 volumes. Startup reconciliation removes leftover topics and subjects under the existing cleanup rules. A future release that crosses an incompatible Kafka storage format declares the volume rebuild in its `release.json`, and its own upgrade performs it, still behind the same gate. v1 designs nothing for this.

## Failed upgrade and the rollback window

- `dbx rollback` is one command, run by whoever ran the upgrade. **The script performs no cryptography and restores nothing.** Restoring means proving tombstone-ledger continuity, unwrapping a per-backup key under the master key, and reapplying the destruction ledger (ADR-0006); putting that in shell would move the master key out of the one module allowed to read it. So the script only does what needs no key, and the previous release restores itself:
  1. reads the rollback-window file and refuses if the window is closed;
  2. writes a **restore request** naming the pre-upgrade backup and its checksum;
  3. stops the stack, repoints `current` to the previous release, and starts its images.

  The previous release's DBX finds the restore request at startup and, before Flyway and before the queue or workers start, verifies the named backup against the recorded checksum, proves tombstone-ledger continuity, and restores. On success it deletes the request, so a container restarted by `restart: unless-stopped` never restores twice. On failure it renames the request aside, never retries it, and stops in ADR-0006's recovery mode. The restored backup predates the upgrade, so the previous release's Flyway sees its own schema version and migrates nothing. Rollback never reverses a Flyway migration (ADR-0004, ADR-0012).
- **The rollback window** runs from the end of an upgrade until the first migration run is admitted under the new release. After that, rollback refuses, and the way out is a fixed forward release. Restoring the pre-upgrade backup at that point would silently discard post-upgrade runs, their outcomes, and which target tables DBX owns.
- **The window is mirrored to a file the script can read**, in the install directory and outside `secrets/`. H2's installation record stays the authority for DBX and the UI; the file is the authority for the script, which must decide whether rollback is allowed precisely when the new release will not start and its local API answers nothing. "The API is silent, so assume the window is open" is unsafe: the API can also be down long after runs were admitted. DBX therefore writes the file closed **before** admitting the first run under the new release. That write order makes only the harmless divergence possible — a file that says closed while H2 says open costs one over-refused rollback; the dangerous inverse cannot occur.
- **When the previous release will not start either**, v1 stops rather than improvises. The script waits for the previous release's healthcheck and, on timeout, says in Chinese that neither release will start, points at the diagnostic package as the only support channel (ADR-0028), and leaves `current` on the previous release. It never swings `current` back. A third state change on a control plane whose H2 volume is already unjudgeable cannot be reasoned about, and the diagnostic package is worth more than another guess.

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
- **Installing below the 8 GiB floor and letting E5 conclude 不满足.** Rejected: there is no tier to write to `.env`, so the install has no lawful result, and the stack it would start crashloops instead of serving the UI that this option exists to provide. E5 keeps the post-install case, where memory shrinks below a tier that was satisfied at install time ([#98](https://github.com/liumingjian/dbx/issues/98)).
- **Rollback at any time.** Rejected: it would silently drop post-upgrade runs.
- **Restoring H2 from the `dbx` script, before repointing `current`.** Rejected: it needs the master key, AES-GCM, and the tombstone ledger in shell. Letting the previous release restore itself keeps one restore path for both triggers, the failed upgrade and ordinary H2 corruption.
- **Treating an unreachable local API as an open rollback window.** Rejected: the API can be down for reasons unrelated to the upgrade, and the mistake destroys admitted runs.
- **Swinging `current` back to the new release when the previous release will not start.** Rejected: at that point neither release is known good and H2's content is unjudgeable.
- **arm64 and x86_64 both in v1.** Rejected: it doubles certification and calibration, and the machine v1 runs on is arm64.
