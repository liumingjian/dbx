# gateway — v1 sub-spec

Executes typed SQL plans against the source MySQL and target PostgreSQL databases. It covers frozen connections, transactions, timeouts and cancellation, parameter and result-schema checks, the connection-check probe, and advisory locks. It is an effectful shell (ADR-0036 §Modules, §Dependencies and purity).

**Read first**: ADR-0036; ADR-0018 §Enforcement, §Session rule; ADR-0008 §Plans and execution requirements, §Registration; ADR-0006 §Connection and credential model, §Capability checks, §Write-freeze contract and endpoint identity, §Recovery, §Rerun semantics, §Target concurrency; ADR-0023; ADR-0026; ADR-0005; ADR-0028; ADR-0012; ADR-0022. CONTEXT.md terms: database connection (数据库连接), credential version (凭据版本), connection check (连接校验) and its outcomes, TLS mode (TLS 模式), target generation (目标代际), structural proof (结构证明), preflight inconclusive reasons (query timeout 查询超时, permission denied 权限不足, connection lost 连接中断), diagnostic package (诊断包).

## Interface (`gateway.api`)

ADR-0036 names one entry point: **executes typed SQL plans**.

- `execute(plans, binding, mode)` → typed result per plan, or a typed execution failure with evidence. Effectful.
  - `plans`: immutable typed SQL plans from `dialect.api` (operation kind, parameters, expected result schema, timeout class, required privileges, evidence policy).
  - `binding`: the frozen binding. It holds the structured connection snapshot, the decrypted credential and TLS material handed over by `orchestration`, and, for a run, the expected database instance identity.
  - `mode`: a single read-only statement; one transaction; or one transaction guarded by an advisory lock, which carries a lock plan, reread plans, the facts the operator confirmed, and action plans.
  - The connection-check probe and target capability probes are plans passed to `execute`; there are no further entries (final at reconciliation).

## Consumes

- Calls no other module's entry point. It takes `SqlPlan` values from `dialect.api` and, in `binding`, the `source.connectionSemantics` output `orchestration` passes (ADR-0018 `dialect` row; ADR-0036).
- It does not depend on `connection`, `workflow`, or `connector` (ADR-0036 §Dependencies and purity).

## Obligations

**Binding and connections**
1. Opens connections only from the structured fields of a database connection: host, port, product, TLS, username, semantic JDBC settings, and timeouts. It accepts no JDBC URL and no free-form parameter (ADR-0006 §Connection and credential model).
2. Sets every parameter that affects returned types or values: encoding, session timezone, `tinyInt1isBit`, zero-date handling, and TLS verification. The value-affecting set is `source.connectionSemantics` from the binding; reports it as a fingerprintable fact (ADR-0006 §Connection and credential model; TP §6.5).
3. Supports exactly three TLS modes: TLS disabled (不启用 TLS), server authenticated (校验服务端证书), and mutual (双向证书校验). Authentication is username and password only. Tokens, Kerberos, SSH tunnels, and multi-host URLs are rejected (ADR-0006 §Connection and credential model; CONTEXT TLS mode).
4. Never decrypts a credential or reads the master key. Secret and TLS material arrive only in `binding` (ADR-0036 §Dependencies and purity).
5. Opens source connections read-only and never issues a write or lock statement against the source (ADR-0006 §Capability checks).
6. Validation reads use dedicated read-only connections (TP §9.1).
7. Reports the effective server character set, timezone, TLS state, product and version, stable instance identity, and driver version. It reads them from the server and never trusts configuration text (ADR-0006 §Connection and credential model).
8. When `binding` carries an expected instance identity, execution is refused if the observed identity differs from it, and the typed failure names the change. There is no failover fallback (ADR-0006 §Write-freeze contract and endpoint identity).

**Plan execution**
9. Checks that parameters match the plan's declared types and count before execution. Binds values only as parameters. Never concatenates a value into SQL text (ADR-0008 §Plans and execution requirements).
10. Checks every result against the expected result schema: columns, database types, cardinality, and nullability. A mismatch returns a typed failure, never a partial or coerced result (ADR-0008 §Plans and execution requirements).
11. Issues no statement that is not part of a plan (no hidden queries), and executes no plan whose operation kind is not allowed in the current state (ADR-0008 §Plans and execution requirements).
12. Applies the timeout of each plan's timeout class. Preflight timeouts are an advanced deployment setting (ADR-0003). Validation SQL defaults to 30 minutes (TP §9.1).
13. On timeout: calls `Statement.cancel()`, closes the connection, records the database backend or thread identity, and reports whether termination was confirmed or unconfirmed (TP §9.1; ADR-0003).
14. Classifies failures into typed reasons. These include query timeout (查询超时), permission denied (权限不足), connection lost (连接中断), result-schema mismatch, and identity change. The failure carries SQLState, the vendor error code, the exception class, and the exception chain as evidence for `diagnosis` (ADR-0005 error card; CONTEXT preflight inconclusive reason). MySQL 1114 must surface as its vendor code (#89 item 1).
15. Never retries a plan whose operation kind mutates. DDL, `TRUNCATE`, `DROP`, and baseline reads are never repeated blindly. Read-only retries under the ten-minute budget are `orchestration`'s (ADR-0006 §Recovery; `orchestration` obligation 39).
16. Logs no row or parameter values. Evidence carries coordinates, the plan fingerprint, timings, and codes only (ADR-0005 §sources; ADR-0028).
17. Returns evidence and audit facts to the caller and writes no H2 state (ADR-0018 §Dependency direction; see Conflicts 1).

**Transactions and advisory locks**
18. Runs a transactional plan sequence in one transaction: commit on full success, roll back on any failure. No connection outlives a call (ADR-0008 §Plans; ADR-0012 §Persistence boundary).
19. Guarded mode acquires a PostgreSQL transaction-scoped advisory lock first. It then runs the reread plans in the same transaction and compares the reread facts with the confirmed facts field by field. It runs the action plans only if they are equal. On any difference it rolls back and returns the observed facts (ADR-0006 §Rerun semantics, §Target concurrency).
20. Guarded mode is the only way to run a plan for target creation, truncation, structural checks, target generation changes, discard, abandonment drops, and the failed-structural-proof drop (ADR-0006 §Target concurrency; ADR-0023; ADR-0026).
21. Returns the `pg_class` OID of each table it creates, and the `pg_namespace` OID of each schema it creates, as result facts (ADR-0023; #89 item 10).

**Probes**
22. The connection-check probe returns the facts of Obligation 7, plus success or a typed failure. It records no outcome itself: the check succeeded / check failed decision is recorded by `orchestration` (ADR-0036 `gateway` and `orchestration` rows).
23. Executes target capability probe plans (create, insert, read, truncate, drop on an isolated probe object) like any other plan. It never touches a production target table (ADR-0006 §Capability checks).

## Verification

- Obligations 1–4, 9–11, 14 (classification from fixed SQLState and vendor-code samples), 16, 17, and 19 (the comparison): L1, `GatewayContractTest` against fake JDBC drivers, plus ArchUnit rules forbidding `gateway` → `connection`, `workflow`, or `connector`.
- Obligations 5–8, 13, 14, 22: L2, `GatewayMySqlSeamTest` against MySQL 8. It covers the read-only source, effective session facts, an identity mismatch, a timeout with confirmed cancellation, and a permission-denied error.
- Obligations 12, 15, 18–21, 23: L2, `GatewayPostgresSeamTest` against PostgreSQL 15. It covers the advisory lock contending across two sessions, a drift abort that rolls back, the OIDs, and the probe object lifecycle.
- A change to `gateway` needs L1 and L2 green (ADR-0022 §Who runs which rung).

## Slices

1. **`api` + `GatewayContractTest` skeleton.** Covers `execute`, the frozen binding, the three modes, typed results and failures, and evidence. Tests are red or pending until slices 2–4 land. Blocked by `dialect` slice 1 (typed SQL plan type).
2. **Binding and connection-check probe** (Obligations 1–8, 22). Blocked by slice 1; `dialect` slice 5 (`source.connectionSemantics`); D-8 (pooling vs budgets).
3. **Plan execution** (Obligations 9–17). Covers parameter and result checks, timeout classes, cancellation with termination confirmation, and failure classification. Blocked by slice 2.
4. **Transactions, advisory-lock guard, probes** (Obligations 18–21, 23). Blocked by slice 3.

## Conflicts resolved

1. ADR-0008 §Plans said the gateway "persists evidence and audit facts". ADR-0018 §Dependency direction wins, and ADR-0036 keeps it: deep modules return results, and only `orchestration` calls `workflow.api.command`. `gateway` returns the evidence and `orchestration` persists it.
2. `corpus-audit.md` §5 proposed the entry points `probeIdentity` and `withAdvisoryLock`. ADR-0036's Interface column wins: its only entry point is "Executes typed SQL plans". The probe and the lock are expressed as plans and modes of `execute`.
3. ADR-0008 had the "core database gateway" bind credential versions, which could mean it resolves them itself. ADR-0036 §Dependencies and purity wins: `gateway` receives decrypted material from `orchestration`.

## Implementer decides

- How product and version are read before a dialect is selected: no dialect plan, never trusting configuration text (ADR-0008 §Registration; ADR-0006).
- Fetch strategy for DBX's own source reads: bounded in DBX's heap; value-affecting parameters equal `source.connectionSemantics` (ADR-0033; ADR-0006).
- How the allowed operation kinds reach `execute`: caller-supplied, never by depending on `workflow` (ADR-0008 §Plans; ADR-0036).
- The closed set of timeout classes and their other defaults: preflight stays configurable, validation 30 min, every timeout maps to 查询超时 (ADR-0003; TP §9.1).

## Open items

- **D-8** (T2): whether DBX's own JDBC connections, and any pooling, count against scheduling's connection budgets (ADR-0002). Blocks slice 2.
