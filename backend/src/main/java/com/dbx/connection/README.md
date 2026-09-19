# connection

Credential encryption, per-backup DEK wrapping and erasure, the master key, and TLS material; holds no H2 tables.

Depends on: none. `connection` is the leaf of the graph (ADR-0036 §Dependencies and purity) — it names no other module, opens no database, HTTP or Kafka connection, and writes no file. Its only side effect is reading the mounted `secrets/master.key`; everything it produces is a value its callers store. That leaf position is what makes ADR-0006's erasure promise true: no module that owns H2 or the append-only ledger can reach the master key, so a wrapped key cannot linger there recoverable forever.

## Entry points (`com.dbx.connection.api`)

`ConnectionCrypto.overSecretsDirectory(Path)` is the only way in. It has exactly six entry points, and an ArchUnit check in `ConnectionBoundaryTest` refuses a seventh — the interface widens only as a decision.

- `encrypt(SecretMaterial)` → `Ciphertext`: AES-256-GCM, self-contained, one per credential version (凭据版本)
- `decrypt(Ciphertext)` → `Decryption` = `SecretMaterial | WrongOrLostKey | CorruptCiphertext` (two distinct failures, never one with a reason string)
- `wrap(BackupId)` → `IssuedBackupKey` (a fresh `DataEncryptionKey` plus the `WrappedKey` the caller stores beside the backup artifact, never in the ledger)
- `unwrap(WrappedKey)` → `Unwrapping` = `DataEncryptionKey | MasterKeyWrongOrMissing | WrappedFormCorruptOrErased` (exactly two failures: restore `secrets/` versus abandon this backup)
- `erase(WrappedKey)` → `ErasureInstruction`: validated and returned, never carried out here; idempotent
- `fingerprint()` → `KeyFingerprint`: reveals nothing about the key, stable across releases, compared by upgrade

A missing or wrong-length master key is thrown, not returned: `MasterKeyUnavailable` and `MasterKeyMalformed` (both `MasterKeyFailure`), because until `secrets/` is fixed no entry point can do anything. Types holding key bytes or decrypted material redact their `toString` structurally, and no failure carries the material.

An entry point its slice has not landed throws `NotImplementedInSlice` naming itself and the slice; it never returns an empty value.

## Layout

- `api/` — every public type, one file per type
- `masterkey/` — `MasterKeyCrypto`, the one implementation: the key-file loader (slice 2), credential crypto (slice 2), per-backup keys (slice 3). TLS material added no code in slice 4: a client private key and its passphrase are credential material, sealed under the credential label like a password (obligation 20)

## Contract test

`ConnectionContractTest` (`src/test/java/com/dbx/connection/api/`) is the primary documentation of these entry points: the not-implemented ledger, the distinct failure types, structural redaction, and the master-key, credential, DEK and TLS cases — every one of them live, since the module is finished. `ConnectionBoundaryTest` holds obligations 1–5 and the exactly-six check; `ConnectionBoundaryRuleFixtureTest` watches each of those rules fail against `com.dbx.archfixture`.

## Read

- Sub-spec: [docs/spec/connection.md](/docs/spec/connection.md)
- ADRs: [ADR-0006](/docs/adr/0006-versioned-connections-recovery-and-reruns.md), [ADR-0018](/docs/adr/0018-backend-module-boundaries-and-agent-working-surface.md), [ADR-0022](/docs/adr/0022-verification-ladder-and-explicit-golden-updates.md), [ADR-0035](/docs/adr/0035-offline-release-package-one-release-version-and-gated-in-place-upgrade.md), [ADR-0036](/docs/adr/0036-module-table-owns-every-v1-obligation.md)
