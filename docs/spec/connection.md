# connection — v1 sub-spec

Credential crypto for DBX: AES-256-GCM encryption of credential material, per-backup DEK wrapping, unwrapping and erasure, the master key, and TLS material; it persists nothing.

**Read first**: ADR-0036 (§Modules row `connection`, §Dependencies and purity), ADR-0006 (§Connection and credential model, backup paragraph of §Recovery of the same execution), ADR-0035 §Master key, ADR-0018 (§Enforcement, §Module context, §Session rule), ADR-0022; CONTEXT.md terms: database connection (数据库连接), credential version (凭据版本), connection check (连接校验), TLS mode (TLS 模式), run snapshot (运行快照).

## Interface (`connection.api`)

Names follow ADR-0036's Interface column ("Encrypt, decrypt, wrap, erase"), plus `fingerprint` and `unwrap`. Parameter shapes are fixed by slice 1's `ConnectionContractTest`, within the bounds below.

- `encrypt(secret material) → ciphertext`: AES-256-GCM under the master key. Effectful, because it reads the master-key file.
- `decrypt(ciphertext) → secret material | typed failure`: the inverse of `encrypt`. Effectful for the same reason.
- `wrap(backup) → fresh per-backup DEK plus its wrapped form`: generates a distinct data-encryption key and wraps it under the master key. Effectful.
- `unwrap(wrapped DEK) → DEK | typed failure`: the inverse of `wrap`, needed to read an encrypted backup. Effectful. Added by [#97](https://github.com/liumingjian/dbx/issues/97): unwrapping is the only way back into a backup, and the master key must not leave this module to do it (ADR-0006 §Connection).
- `erase(wrapped DEK) → erasure instruction`: validates that the wrapped form belongs to the present master key and returns the instruction that makes the DEK unrecoverable. It performs no erasure itself, because `connection` persists nothing; `workflow` carries the instruction out (#97; ADR-0006 §Connection).
- `fingerprint() → key fingerprint`: the present master key's fingerprint. Effectful (reads the key file). Added at reconciliation: `connection` alone reads the key, while `workflow` stores the fingerprint and E8 and upgrade compare it (ADR-0035 §Master key; #89 item 5).

## Consumes

None. `connection` is a leaf: it calls no other module's `api` (ADR-0036 §Dependencies and purity).

Known callers: `workflow` calls `wrap`/`erase` for backups and `unwrap` when restoring one; `orchestration` calls `encrypt` when saving a credential version, `decrypt` for `gateway` bindings and `connector.projectSecret`, and `fingerprint` for E8 and the installation record (ADR-0036 rows `workflow`, `orchestration`).

## Obligations

### A. Boundaries

1. `connection` depends on no other DBX module. In particular it never references `workflow`, so `workflow → connection` stays one-way (ADR-0036 §Dependencies and purity; ADR-0018 §Enforcement).
2. `connection` holds no H2 tables. It references no `JdbcTemplate`, no Flyway migration and no repository. Database connection records, credential versions, the tombstone ledger, backups and the key fingerprint are `workflow`'s (ADR-0036 §Modules, rows `connection` and `workflow`; §Considered options).
3. `gateway` and `connector` never reference `connection`. `gateway` receives decrypted material from `orchestration` (ADR-0036 §Dependencies and purity).
4. `connection` calls no other module's side effects, and it opens no database, HTTP or Kafka connection. Its only side effect is reading the master-key file (ADR-0036 §Dependencies and purity, "Effectful shell").
5. Only `connection.api` is referenced from outside the package (ADR-0018 §Enforcement).
6. The module ships a `README.md` of at most 40 lines. It lists the six entry points, the contract test location, "depends on: none", and the ADRs named under **Read first** (ADR-0018 §Module context).

### B. Master key

7. The master key is read only from the mounted file `secrets/master.key`. There is no environment-variable, `.env` or H2 fallback (ADR-0035 §Master key; ADR-0006 §Connection and credential model: "never stored in H2, an image, or a diagnostic package").
8. A key file that is not exactly 256 bits is rejected with a typed failure, never silently padded or truncated (ADR-0035 §Master key: "random 256-bit key"; ADR-0006: AES-256-GCM).
9. A missing key file makes every entry point fail with a typed "master key unavailable" failure. It never generates a key. Generating the key is the release script's job (ADR-0035 §Master key, "First install").
10. `connection` never writes to `secrets/` (ADR-0035 §Master key: "Upgrade and rollback never write `secrets/`"; ADR-0036: "`connection` never persists").
11. Key bytes and decrypted secret material never appear in `toString`, exception messages or log output. They must stay out of the diagnostic package (ADR-0006 §Connection and credential model; ADR-0028).

### C. Credential encryption

12. `encrypt` uses AES-256-GCM, and `decrypt(encrypt(x)) = x` for any credential material (ADR-0006 §Connection and credential model).
13. A ciphertext that was altered, truncated, or produced under a different master key does not decrypt. The result is a typed failure, never wrong plaintext (ADR-0006, AES-256-GCM authentication; ADR-0035 §Master key, "If the key is lost").
14. "Wrong or lost key" and "corrupt ciphertext" are distinct typed failures. The caller uses them to lead the DBA to re-enter the password, which creates a new credential version (ADR-0035 §Master key, "If the key is lost"; ADR-0006).
15. Encryption is per credential version: the output is a self-contained value that `workflow` stores immutably. `connection` keeps no state between calls (ADR-0006: "immutable credential versions"; ADR-0036: "never persists").

### D. Per-backup DEKs

16. Each `wrap` returns a DEK that differs from every DEK issued before it. It returns the wrapped form for `workflow` to store beside the backup artifact, not in the ledger; the ledger tracks the key by identity and fingerprint only (ADR-0006 §Connection, as amended by #97).
17. `unwrap(wrap(k)) = k` under the same master key.
18. `unwrap` has exactly two typed failures, and they are distinct: **master key wrong or missing**, and **wrapped form corrupt or erased**. They lead the DBA to different actions — restore `secrets/` from the copy kept off the machine, versus abandon this backup and choose another — so an erased key must never surface as a key problem (ADR-0006; obligation 14's precedent).
19. `erase` returns an instruction and writes nothing. `connection` holds no state between calls and never touches `backups/` or the ledger (ADR-0036: "never persists").
19a. Applying an erasure instruction makes the DEK unrecoverable: afterwards no path through `connection.api` returns it, even with the master key or its separately protected backup (ADR-0006).
19b. `erase` is idempotent. Producing an erasure instruction for an already-erased DEK succeeds without error, because cleanup is retried (ADR-0006 §Recovery: "cleanup is idempotent").

### E. TLS material

20. TLS material for the three TLS modes enters `connection` only through `encrypt`/`decrypt`. The mutual-TLS client private key and its passphrase are secret and encrypted like credential material; `workflow` stores the ciphertext (ADR-0006 §Connection and credential model; ADR-0005 "private keys"; `workflow` obligation 16).

## Verification

All obligations are verified at L1 (`check`). `connection` has no container seam: its only side effect is reading a file, which a temporary directory covers. ADR-0022's L2 duty names only the `gateway`, `connector` and `workflow` shells (ADR-0022, "Agent session" rule).

| Group | Rung | Test |
|---|---|---|
| A (1–5) | L1 | ArchUnit rules of ADR-0018 §Enforcement, plus `ConnectionBoundaryTest` for 2–4 (no `JdbcTemplate`, no HTTP or Kafka client, no inbound reference from `gateway` or `connector`, no outbound module reference) |
| A (6) | L1 | README-limit test (ADR-0018) |
| B (7–11) | L1 | `ConnectionContractTest` master-key cases over a temporary `secrets/` directory: missing file, wrong length, environment variable ignored, directory unchanged after every call, redacted `toString` and exception text |
| C (12–15) | L1 | `ConnectionContractTest` credential cases: round trip; bit flip, truncation and foreign key each give their typed failure; two distinct failure types; no instance state |
| D (16–19b) | L1 | `ConnectionContractTest` DEK cases: distinct DEKs across wraps, unwrap round trip, foreign-key unwrap and corrupt-wrap give the two distinct failures, erased DEK unrecoverable, erase writes nothing, erase twice |
| E (20) | L1 | `ConnectionContractTest` TLS case, plus an ArchUnit check that `connection.api` exposes exactly six entry points |

No golden files.

## Slices

1. **`api` + `ConnectionContractTest` skeleton**: the six entry-point signatures, the typed failure types (8, 9, 13, 14), the README (6), and boundary tests A1–A5. Contract cases are present but disabled per slice. Blocked by: none.
2. **Master key and credential encryption**: the key-file loader (B7–B11), `encrypt`, `decrypt` and `fingerprint` (C12–C15), and their contract cases enabled. Blocked by: slice 1.
3. **Per-backup DEKs**: `wrap`, `unwrap` and `erase` (D16–D19b) and their contract cases enabled. Blocked by: slice 2.
4. **TLS material**: E20 and its contract case. Blocked by: slice 2.

Consumers: `workflow` slice 8 waits on slice 3; `orchestration` slice 2 waits on slice 2.

## Conflicts resolved

See [`conflicts.md`](conflicts.md#connection) — provenance only; every winning ruling is already an obligation above.

## Implementer decides

- DEK wrapping scheme, GCM nonce policy, and associated data: never reuse a nonce under one key; altered or foreign ciphertext never decrypts (ADR-0006).
- Fingerprint algorithm: reveals nothing about the key and stays stable across releases, because upgrade compares it (ADR-0035 §Master key).

## Open items

None. D-22 and D-23 are settled in [#97](https://github.com/liumingjian/dbx/issues/97).
