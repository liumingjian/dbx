# connection — v1 sub-spec

Credential crypto for DBX: AES-256-GCM encryption of credential material, per-backup DEK wrapping and erasure, the master key, and TLS material; it persists nothing.

**Read first**: ADR-0036 (§Modules row `connection`, §Dependencies and purity), ADR-0006 (§Connection and credential model, backup paragraph of §Recovery of the same execution), ADR-0035 §Master key, ADR-0018 (§Enforcement, §Module context, §Session rule), ADR-0022; CONTEXT.md terms: database connection (数据库连接), credential version (凭据版本), connection check (连接校验), TLS mode (TLS 模式), run snapshot (运行快照).

## Interface (`connection.api`)

Names follow ADR-0036's Interface column ("Encrypt, decrypt, wrap, erase"), plus `fingerprint`. Parameter shapes are fixed by slice 1's `ConnectionContractTest`, within the bounds below.

- `encrypt(secret material) → ciphertext`: AES-256-GCM under the master key. Effectful, because it reads the master-key file.
- `decrypt(ciphertext) → secret material | typed failure`: the inverse of `encrypt`. Effectful for the same reason.
- `wrap(backup) → fresh per-backup DEK plus its wrapped form`: generates a distinct data-encryption key and wraps it under the master key. Effectful.
- `erase(wrapped DEK) → erasure result`: makes that per-backup DEK unrecoverable. Effectful. What `erase` can do without persistence is D-22.
- `fingerprint() → key fingerprint`: the present master key's fingerprint. Effectful (reads the key file). Added at reconciliation: `connection` alone reads the key, while `workflow` stores the fingerprint and E8 and upgrade compare it (ADR-0035 §Master key; #89 item 5).

## Consumes

None. `connection` is a leaf: it calls no other module's `api` (ADR-0036 §Dependencies and purity).

Known callers: `workflow` calls `wrap`/`erase` for backups; `orchestration` calls `encrypt` when saving a credential version, `decrypt` for `gateway` bindings and `connector.projectSecret`, and `fingerprint` for E8 and the installation record (ADR-0036 rows `workflow`, `orchestration`).

## Obligations

### A. Boundaries

1. `connection` depends on no other DBX module. In particular it never references `workflow`, so `workflow → connection` stays one-way (ADR-0036 §Dependencies and purity; ADR-0018 §Enforcement).
2. `connection` holds no H2 tables. It references no `JdbcTemplate`, no Flyway migration and no repository. Database connection records, credential versions, the tombstone ledger, backups and the key fingerprint are `workflow`'s (ADR-0036 §Modules, rows `connection` and `workflow`; §Considered options).
3. `gateway` and `connector` never reference `connection`. `gateway` receives decrypted material from `orchestration` (ADR-0036 §Dependencies and purity).
4. `connection` calls no other module's side effects, and it opens no database, HTTP or Kafka connection. Its only side effect is reading the master-key file (ADR-0036 §Dependencies and purity, "Effectful shell").
5. Only `connection.api` is referenced from outside the package (ADR-0018 §Enforcement).
6. The module ships a `README.md` of at most 40 lines. It lists the five entry points, the contract test location, "depends on: none", and the ADRs named under **Read first** (ADR-0018 §Module context).

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

16. Each `wrap` returns a DEK that differs from every DEK issued before it. It returns the wrapped form for `workflow` to record in the destruction ledger (ADR-0006 §Recovery of the same execution: "a distinct data-encryption key whose wrapped form is tracked by the destruction ledger").
17. Unwrapping a DEK needs the master key. A wrapped DEK under a different master key does not unwrap (ADR-0006 §Connection and credential model).
18. Once a DEK is erased, no path through `connection.api` recovers it, even with the master key or its backup (ADR-0006: "The separately protected master-key backup cannot recover an erased per-backup key").
19. `erase` is idempotent. Erasing an already-erased DEK succeeds without error, because cleanup is retried (ADR-0006 §Recovery: "cleanup is idempotent").

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
| D (16–19) | L1 | `ConnectionContractTest` DEK cases: distinct DEKs across wraps, foreign-key unwrap fails, erased DEK unrecoverable, erase twice |
| E (20) | L1 | `ConnectionContractTest` TLS case, plus an ArchUnit check that `connection.api` exposes exactly five entry points |

No golden files.

## Slices

1. **`api` + `ConnectionContractTest` skeleton**: the five entry-point signatures, the typed failure types (8, 9, 13, 14), the README (6), and boundary tests A1–A5. Contract cases are present but disabled per slice. Blocked by: none.
2. **Master key and credential encryption**: the key-file loader (B7–B11), `encrypt`, `decrypt` and `fingerprint` (C12–C15), and their contract cases enabled. Blocked by: slice 1.
3. **Per-backup DEKs**: `wrap` and `erase` (D16–D19) and their contract cases enabled. Blocked by: slice 2; D-22, D-23.
4. **TLS material**: E20 and its contract case. Blocked by: slice 2.

Consumers: `workflow` slice 8 waits on slice 3; `orchestration` slice 2 waits on slice 2.

## Conflicts resolved

- ADR-0006: "the master key is supplied independently as a deployment secret" → ADR-0035 §Master key: a mounted file `secrets/master.key`, with only its fingerprint in H2. ADR-0006's own note defers to ADR-0035.
- ADR-0036 row `environment`: "E0 to E7 … and the master-key item" → #89 item 5: the master-key item is E8. That is `environment`'s concern, not this module's.
- corpus-audit.md §5 "proposed `connection`": connection CRUD and archive, the tombstone ledger, the master-key fingerprint, and the secret projection → ADR-0036 §Modules. CRUD, the ledger and the fingerprint belong to `workflow`; the ConfigProvider projection belongs to `connector`; `connection` "holds no H2 tables".
- ADR-0018 §Pure core: "side effects are confined to `gateway`, the Connect REST client, and the `workflow` repositories" → ADR-0036 §Dependencies and purity adds `connection` (it reads the master-key file) as an effectful shell.
- Who calls `encrypt` on a new credential version → `orchestration` before its `workflow.api.command` (it is the only command caller; ADR-0036 names only backups as `workflow`'s crypto use).
- How `connector` gets plaintext → from `orchestration`, like `gateway`; `connector` never references `connection` (ADR-0036 §Dependencies and purity).
- `decrypt` gated on tombstone reapply (ADR-0006) → the caller sequences it; `connection` holds no state to know (ADR-0036 "never persists").

## Implementer decides

- DEK wrapping scheme, GCM nonce policy, and associated data: never reuse a nonce under one key; altered or foreign ciphertext never decrypts (ADR-0006).
- Fingerprint algorithm: reveals nothing about the key and stays stable across releases, because upgrade compares it (ADR-0035 §Master key).

## Open items

- **D-22** (T6): what `erase` does when `connection` persists nothing (clear in-memory material, or return an instruction `workflow` executes on the wrapped form). Blocks slice 3.
- **D-23** (T6): the restore path: an unwrap entry, who restores after H2 corruption, and who decrypts backups for `dbx rollback` (ADR-0006, ADR-0035). Blocks slice 3.
