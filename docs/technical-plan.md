# DBX v1 technical plan

## 1. Purpose and authority

This document is the implementation handoff for DBX v1: an offline, one-time full migration from MySQL 8.0 to PostgreSQL 15. It integrates the Wayfinder decisions into one build sequence; it does not replace their decision records.

The sources of authority are:

1. [`CONTEXT.md`](../CONTEXT.md) owns domain language.
2. [`docs/adr/`](adr/) owns architectural decisions, invariants, consequences, and rejected alternatives.
3. This document owns the integrated component plan, detailed v1 mapping/validation specification, implementation order, and acceptance strategy.
4. Closed GitHub decisions and `research/*`, `task/*`, and `prototype/*` branches are provenance. They explain why a rule exists but do not override the repository documents.

If two repository documents conflict, implementation stops until the documents are reconciled. Code must not silently choose the convenient interpretation.

### Decision index

| Concern | Authority |
|---|---|
| External completion and connector lifecycle | [ADR-0001](adr/0001-external-completion-and-connector-lifecycle.md) |
| Box packing, rolling admission, and resource gates | [ADR-0002](adr/0002-resource-bounded-box-scheduling.md) |
| Exact 20 MiB preflight and 25 MiB Kafka envelope | [ADR-0003](adr/0003-preflight-gated-large-record-envelope.md) |
| Relational state, table-unit state machine, H2, and Flyway | [ADR-0004](adr/0004-relational-migration-state-machine.md) |
| Error translation and diagnostic evidence | [ADR-0005](adr/0005-error-translation-and-diagnostic-evidence.md) |
| Connections, credentials, recovery, cancellation, discard, and reruns | [ADR-0006](adr/0006-versioned-connections-recovery-and-reruns.md) |
| Source/target dialect and directed database-pair seam | [ADR-0008](adr/0008-database-dialect-and-pair-extension-seam.md) |
| Kafka and Kafka Connect as the sole data plane | [ADR-0009](adr/0009-kafka-connect-v1-data-plane.md) |
| Avro and Schema Registry lifecycle | [ADR-0010](adr/0010-avro-schema-registry-lifecycle.md) |
| Table write contract, platform DDL, and Sink contract | [ADR-0011](adr/0011-platform-owned-ddl-and-table-write-contract.md) |
| Spring JDBC persistence without JPA | [ADR-0012](adr/0012-spring-jdbc-no-jpa.md) |
| Whole-table execution without sharding | [ADR-0013](adr/0013-single-table-execution-granularity.md) |
| Frontend table substrate and `DataTable` entry point | [ADR-0015](adr/0015-table-substrate-and-dbxtable-boundary.md), [ADR-0025](adr/0025-antd-table-with-a-thin-datatable-entry-point.md) |
| Frontend contract, mock boundary, and progress transport | [ADR-0016](adr/0016-frontend-contract-mock-boundary-and-progress-transport.md) |
| Design system, conclusion indicators, and Chinese typography | [ADR-0017](adr/0017-ant-design-substrate-conclusion-indicators-and-chinese-typography.md) |
| Backend module rules and agent working surface | [ADR-0018](adr/0018-backend-module-boundaries-and-agent-working-surface.md) |
| Backend module table | [ADR-0036](adr/0036-module-table-owns-every-v1-obligation.md) |
| Five-stage wizard and run-organised task console | [ADR-0020](adr/0020-five-stage-wizard-and-task-console.md) |
| Runtime condition | [ADR-0021](adr/0021-runtime-condition-observes-and-never-adjudicates.md) |
| Verification ladder and golden files | [ADR-0022](adr/0022-verification-ladder-and-explicit-golden-updates.md) |
| Task abandonment | [ADR-0023](adr/0023-task-abandonment-drops-owned-target-tables.md) |
| Cross-window runs, task write freeze, and task conclusion | [ADR-0024](adr/0024-cross-window-batches-as-runs-under-a-task-write-freeze.md) |
| Read-only contract rendering and supplemental SQL | [ADR-0026](adr/0026-read-only-contract-rendering-and-task-level-supplemental-sql.md) |
| Environment check: detect and explain, no host control | [ADR-0027](adr/0027-environment-check-detects-and-explains-without-host-control.md) |
| Diagnostic package: scopes, manifest, and no data values | [ADR-0028](adr/0028-diagnostic-package-scopes-manifest-and-no-values.md) |
| Preflight finding impact and conclusion palette | [ADR-0029](adr/0029-preflight-finding-impact-approval-as-acceptance-and-conclusion-palette.md) |
| Operator-facing vocabulary | [ADR-0030](adr/0030-operator-facing-vocabulary-is-domain-language.md) |
| Platform memory budget and bounded in-flight records | [ADR-0031](adr/0031-platform-memory-budget-admission-and-bounded-in-flight-records.md) |
| Out of memory and Connect restarts | [ADR-0032](adr/0032-out-of-memory-crashes-the-worker-and-fails-its-running-boxes.md) |
| Bounded Source reads and keyset chunks | [ADR-0033](adr/0033-bounded-source-reads-cursor-fetch-and-keyset-chunks.md) |
| Bulk Source reads: keyset column and 64 MiB cap | [ADR-0037](adr/0037-bulk-source-reads-keyset-column-and-64-mib-cap.md) |
| Duration estimate and remaining-time estimate | [ADR-0019](adr/0019-migration-duration-estimate-before-the-run.md), [ADR-0034](adr/0034-duration-estimate-replays-the-scheduling-plan-on-shape-rates.md), [ADR-0038](adr/0038-duration-estimate-waits-for-preflight-and-history-is-per-source.md) |
| Offline release package, release version, and upgrade | [ADR-0035](adr/0035-offline-release-package-one-release-version-and-gated-in-place-upgrade.md) |
| Admission paused | [ADR-0039](adr/0039-admission-paused-is-a-run-fact-that-requires-attention.md) |

ADR-0007 and ADR-0014 are superseded (by ADR-0020 and ADR-0017).

Detailed mapping, DDL, validation, and identifier rules below canonicalize the accepted conclusions of [“MySQL 8.0 → PostgreSQL 15 类型映射矩阵定稿”](https://github.com/liumingjian/dbx/issues/11), [“DDL 生成器与 Sink 写入契约的一致性保证方案”](https://github.com/liumingjian/dbx/issues/12), [“数据校验规格定稿”](https://github.com/liumingjian/dbx/issues/16), [“MySQL database → PG schema 落点规则与标识符策略”](https://github.com/liumingjian/dbx/issues/17), and [“DDL 的列属性与表约束规格”](https://github.com/liumingjian/dbx/issues/23). Later end-to-end evidence at commit [`9768f8a`](https://github.com/liumingjian/dbx/commit/9768f8ac6dc6eb59ec68d0817ede2803c93e6a19) supersedes earlier research assumptions where they disagree.

## 2. Scope and invariants

DBX v1 supports one directed database pair: MySQL 8.0 to PostgreSQL 15. A migration task selects one source database and one target database/schema. A migration run is one immutable attempt over whole selected tables. It is not CDC, a continuous replication system, a general ETL engine, or a full database-structure converter.

The non-negotiable invariants are:

- Kafka + Connect is the only data path for every nonempty table.
- One table migration unit is the durable observable result; a box is a disposable run-local scheduling group.
- One table remains whole. V1 does not shard it across range queries, topics, or connectors.
- A run freezes connections, credentials, metadata decisions, contracts, baseline, routing, configuration fingerprints, and scheduling plan.
- DBX proves a table write contract and creates the target before Sink starts. Sink never creates or evolves target structure.
- Source writes are externally frozen from exact baseline capture until every selected table reaches a validation terminal state or execution stops. A run's write freeze nests inside the task write freeze (整库冻结承诺), which covers the task's whole scope, including already-migrated tables, across every run (ADR-0024). At run-freeze expiry without extension, DBX fails the unfinished units (ADR-0006).
- Connect status and Source offsets do not mean complete. DBX observes read complete and write complete externally.
- A technical validation result is immutable. Accepting risk is a separate audited disposition and never turns red or inconclusive evidence green.
- Recovery may continue only the same provably continuous execution. A data rerun is a new migration run and a full copy of each selected table into a newly owned target generation.
- A failed record is never skipped for a successful result. There is no DLQ-success mode, silent truncation, or fallback transport.

## 3. Architecture

### 3.1 Runtime topology

```text
Browser
   |
DBX API / application services
   |-- migration core and state machine
   |-- dialect + directed database-pair catalog
   |-- metadata, mapping, preflight, contract, DDL, validation
   |-- scheduler, lifecycle reconciler, diagnostics, cleanup
   |-- Spring JDBC -> file-backed H2
   |
   |-- JDBC read/check ------------------------------> MySQL 8.0
   |-- JDBC DDL/introspection/validation ------------> PostgreSQL 15
   |-- REST -----------------------------------------> Kafka Connect
   |-- Admin/consumer APIs --------------------------> Kafka
   `-- REST -----------------------------------------> Schema Registry

Kafka Connect:
MySQL -> JDBC Source -> Avro/value subject -> Kafka topic
      -> JDBC Sink -> pre-created PostgreSQL table
```

DBX and Connect are separate processes. Connect may continue moving records while DBX restarts. H2 remains the control-plane recovery authority; Kafka, Connect, Schema Registry, MySQL, and PostgreSQL provide current observed facts used to reconcile persisted intent.

### 3.2 Component responsibilities

Module ownership is [ADR-0036](adr/0036-module-table-owns-every-v1-obligation.md)'s table of fourteen Java packages, under ADR-0018's enforcement rules. Every obligation in this plan has exactly one owner there; this document does not keep a second component table.

Capabilities and plans are closed, strongly typed, fingerprintable values. Core semantics must not be carried in `Map<String, Object>`, arbitrary JSON, configured implementation class names, or customer-supplied connector properties.

## 4. End-to-end safety sequence

A new migration run follows this order:

1. Select saved source and target connections with a successful latest connection check, one MySQL database, one PostgreSQL database and target schema.
2. The environment check (环境自检, ADR-0027) proves the built-in installation at startup and again before a run is admitted; its conclusions freeze into the run snapshot. It is not a per-run capability probe of the data plane: v1 has no customer-managed infrastructure.
3. Read normalized source metadata, including original type, identifiers, keys, indexes, defaults, auto-increment facts, comments, charset, and collation.
4. Apply automatic mapping and execute every required exact preflight. Preflight may run before the write freeze because it proves support, not a data baseline. The duration estimate first appears once preflight completes (ADR-0038).
5. Apply bounded mapping rules. A rule makes the preflight of the tables it touches stale; they must preflight again before execution (ADR-0020).
6. Assemble and display each table write contract, preflight findings, and read-only DDL rendering; supplemental SQL is generated from this stage (ADR-0026).
7. Approve supported contracts and exclusions; approval accepts the non-blocking findings (ADR-0029).
8. Confirm the accountable, time-bounded external write freeze inside the task write freeze; capture exact per-table `COUNT(*)` and keyset-column terminal values (ADR-0037); freeze the run snapshot and immutable scheduling plan.
9. Acquire target leases, create the schema when authorized, render and execute target DDL, then prove zero structural difference from each contract.
10. Create topics, start Sink and prove healthy, start Source and prove healthy.
11. Observe transfer. On exact, stable read completion, remove Source. On exact, stable write completion, remove Sink.
12. Validate each write-complete table immediately while the write freeze remains valid.
13. Project the run result from table outcomes. Request successful topic deletion; after absence is confirmed, request the associated Schema Registry subject deletion.
14. Preserve all evidence that is not eligible for success cleanup. Cancellation stops execution but does not discard it; 收尾取消 (finishing cancellation) admits no new box and lets transferring tables finish inside a valid freeze (ADR-0024). Discard is separate, run-scoped, and ownership-proven. Abandonment (废弃) is the task-level decision that drops the target tables DBX still owns (ADR-0023).

No gate can be converted to success by a dialect, connector setting, warning acknowledgement, or validation disposition.

## 5. Persistent model and recovery

The persistent aggregates and ownership are specified by ADR-0004 and ADR-0006, extended in ADR-0036's `workflow` row:

- **Database connection / credential version**: reusable endpoint semantics and immutable secret material versions.
- **Migration draft**: the server-side, discardable wizard working set with its stage gating (ADR-0020); it produces no run.
- **Migration task**: selected source database, target schema, current user mapping rules, task-level conversion switches, and approved intent; its abandonment lifecycle (ADR-0023); its task write freeze, task conclusion, and split snapshot (ADR-0024); and the write-once schema-created fact (creating run, time, and the schema's `pg_namespace` OID).
- **Migration run**: immutable connections, environment check conclusions, write freeze, baseline, contracts, accepted findings, routing, connector policy, and scheduling plan for one attempt; an open admission pause (准入已暂停) is a durable run fact (ADR-0039).
- **Installation record**: the single row holding the release version, master-key fingerprint, and rollback-window state (ADR-0035).
- **Runtime condition change record** (ADR-0021).
- **Table migration unit**: one table's metadata, preflight, baseline, contract, progress, validation executions, outcome, errors, and table-owned cleanup.
- **Box**: immutable run-local membership, ordering, execution signature, connectors, observed lifecycle, resource occupancy, and box diagnosis.
- **Routing snapshot**: the only authority mapping connector/topic coordinates to source and target objects and fields.
- **Validation execution/item**: retained attempts and five-state technical evidence.
- **Error occurrence / diagnosis**: immutable fact and versioned interpretation.
- **Timeline event / stage attempt**: durable transition evidence and stage timing, not raw poll samples.
- **Cleanup request / target generation / lease**: independently retried resource ownership and destructive-action protection; a target generation records the table's OID (ADR-0023).

V1 has no evidence retention: evidence is immutable and tasks are never deleted, so H2 grows with use, which is a sizing note in the operating guide. Hourly H2 backups keep the last 48.

Table-unit phases and outcomes, box checkpoints, and run projections are defined only in ADR-0004. Implementations should reference those enums directly rather than create a second workflow in controllers or UI code.

Every external mutation follows: commit intent, perform the bounded idempotent operation, reread facts, commit observation. Startup obtains the single-instance lease, pauses admission, loads nonterminal state and cleanup, reconciles all external systems, rebuilds actual resource occupancy, and only then resumes the immutable queue.

## 6. MySQL 8.0 to PostgreSQL 15 mapping

### 6.1 Pure mapping protocol

`TypeMapper` is pure: `map(SourceColumn, MappingOptions) -> Supported | Unsupported`. Input preserves unmodified `information_schema` facts, including `data_type`, `column_type`, unsigned flag, lengths, precision/scale, temporal precision, charset/collation, nullability, default, `extra`, and original ordinal. A supported result contains target type, Source extraction intent, Connect/Avro representation, JDBC binder, required preflights, contract effects, alternatives, and notices. An unsupported result contains a stable reason and required evidence.

`MappingOptions` contains only the two v1 task switches: treat `TINYINT(1)` as Boolean and convert zero dates to `NULL`. An alternative may only widen the default value domain, never narrow it. Every MySQL 8.0 `data_type` must return a closed result—never `null`, an omitted field, or an exception fallback.

### 6.2 Numeric types

| MySQL | Connect / Avro | PostgreSQL | Rules |
|---|---|---|---|
| `TINYINT`, `TINYINT(M != 1)` | INT8 | `smallint` | Signed |
| `TINYINT(1)`, `BOOL`, `BOOLEAN` | INT8 | `smallint` | Default switch off |
| `TINYINT(1)`, `BOOL`, `BOOLEAN` | BOOLEAN | `boolean` | Task switch on; all values must preflight to `{0,1}` |
| `TINYINT UNSIGNED` | INT16 | `smallint` | |
| `SMALLINT` | INT16 | `smallint` | |
| `SMALLINT UNSIGNED` | INT32 | `integer` | |
| `MEDIUMINT` | INT32 | `integer` | |
| `MEDIUMINT UNSIGNED` | INT64 | `bigint` | |
| `INT`, `INTEGER` | INT32 | `integer` | |
| `INT UNSIGNED` | INT64 | `bigint` | |
| `BIGINT` | INT64 | `bigint` | |
| `BIGINT UNSIGNED` | INT64 | `numeric(20,0)` | Exact `MAX <= 2^63-1` preflight; Source cannot read larger values even though target type can store them |
| `FLOAT`, unsigned variants | FLOAT32 | `real` | |
| `DOUBLE`, `REAL`, unsigned variants | FLOAT64 | `double precision` | |
| `DECIMAL(p,s)`, `NUMERIC(p,s)` | Decimal logical type | `numeric(p,s)` | Preserve precision and scale exactly |
| `DECIMAL` without explicit parameters | Decimal | `numeric(10,0)` | MySQL default |
| `BIT(1..7)` | INT8 | `smallint` | Proven connector representation |
| `BIT(n >= 8)` | — | Unsupported | Values can truncate or overflow before Sink |

`numeric.mapping=none` is explicit. It does not fix MySQL `BIGINT UNSIGNED`; `jdbcCompliantTruncation=true` remains mandatory and not user-overridable.

### 6.3 Character, binary, and special types

| MySQL | Connect / Avro | PostgreSQL | Rules |
|---|---|---|---|
| `CHAR(M)` | STRING | `char(M)` | Sampling comparison removes trailing U+0020 only |
| `VARCHAR(M)` | STRING | `varchar(M)` | `M` is character length; no width preflight needed |
| `TINYTEXT`, `TEXT`, `MEDIUMTEXT`, `LONGTEXT` | STRING | `text` | 20 MiB value/row preflight applies |
| Character column with binary charset/collation representation | BYTES | `bytea` | Determined from source metadata, not target preference |
| `ENUM(...)` | STRING | `text` + `CHECK` | Exact illegal/sentinel-value preflight |
| `SET(...)` | STRING | `text` | No combinatorial `CHECK` |
| `JSON` | STRING | `json` | JSON cast is proven, but UTF-8 connector baseline is mandatory; do not claim byte fidelity without E2E evidence |
| `BINARY(M)`, `VARBINARY(M)` | BYTES | `bytea` | |
| `TINYBLOB`, `BLOB`, `MEDIUMBLOB`, `LONGBLOB` | BYTES | `bytea` | 20 MiB value/row preflight applies |

`jsonb` and `text` may be offered only as structured widening/semantic alternatives with matching validation semantics. The E2E prototype observed mojibake under an incorrect default connection, so release certification must prove Chinese and emoji through the fixed UTF-8 chain before the UI or report describes JSON text preservation.

Geometry and its subtypes, MySQL 8.4 `VECTOR`, and every type outside the whitelist are unsupported. A table can proceed only by explicitly pruning an unsupported column, which puts that table alone into Source query mode.

### 6.4 Temporal types

| MySQL | Connect / Avro | PostgreSQL | Rules |
|---|---|---|---|
| `DATE` | Date | `date` | Zero-date policy applies |
| `DATETIME(n)` | Timestamp | `timestamp(min(n,3)) without time zone` | Preserve wall-clock fields; milliseconds only |
| `TIMESTAMP(n)` | Timestamp | `timestamptz(min(n,3))` | Compare as UTC instant; milliseconds only |
| `TIME(n)` | Time | `time(min(n,3)) without time zone` | Exact values must lie in `[00:00:00, 24:00:00)` |
| `YEAR` | Date | `date` | Value is `YYYY-01-01`; proven Connect representation |

Connect logical time uses milliseconds, so microseconds are an explicit v1 loss boundary. DDL records precision `3`, not a misleading `6`. The platform retains the original MySQL type because Connect cannot distinguish `DATETIME` from `TIMESTAMP` after conversion.

### 6.5 Fixed connection and connector baseline

Source Connector/J semantics are fingerprinted:

- `tinyInt1isBit` and `transformedBitIsBoolean` follow the task Boolean switch.
- `jdbcCompliantTruncation=true`.
- `zeroDateTimeBehavior=EXCEPTION` by default or `CONVERT_TO_NULL` under the approved task switch.
- `connectionTimeZone=UTC`, `forceConnectionTimeZoneToSession=true`, and `preserveInstants=true`.
- Unicode/UTF-8 is forced; blob-to-string compatibility switches remain off.
- `yearIsDateType=true`.
- `useCursorFetch=true` on every Source connection (ADR-0033).

Source reads are bounded in bytes by M, the table's exact largest row byte length from preflight:

| Setting | Keyset read (ADR-0033) | Bulk read (ADR-0037) |
|---|---|---|
| `batch.max.rows` (also the fetch size) | `clamp(4 MiB ÷ M, 1, 1024)`, power of two; 1 for a large record table | same |
| `max.buffer.size` | equal to `batch.max.rows`; 4 for a large record table | same |
| `query.suffix` | `LIMIT N`, N = `clamp(64 MiB ÷ M, 1, 131072)`, power of two | empty |
| `poll.interval.ms` | 100 | `2147483647` |

A table with a keyset column (键集列) is read in keyset chunks; the rest are read in bulk. All of these settings are part of the execution signature.

JDBC Source fixes `numeric.mapping=none`, `db.timezone=UTC`, `timestamp.granularity=connect_logical`, and `quote.sql.identifiers=always`. JDBC Sink fixes UTC session semantics plus the write settings in ADR-0011. Connect and DBX JVMs run in UTC. The capability check reads effective server/session settings instead of trusting configuration text.

### 6.6 Exact preflight

The mapper emits preflight obligations and the source dialect combines all obligations for a table into the minimum bounded scans. Required checks are:

1. Every selected value and the selected row payload are within the exact 20 MiB source-byte boundary in ADR-0003.
2. Under the Boolean switch, every selected `TINYINT(1)` value is `0`, `1`, or `NULL`.
3. Every selected `BIGINT UNSIGNED` maximum is at most `2^63-1`.
4. Every selected MySQL `TIME` value lies within the PostgreSQL/Connect time-of-day domain.
5. Every selected `ENUM` value belongs to its declared set and is not an illegal sentinel.
6. Date/time columns contain no zero date unless the explicit conversion-to-NULL option is approved.
7. Primary-key target width and auto-increment sequence bounds satisfy the DDL rules in section 7.
8. A table without a keyset column fits one bulk read: baseline row count × M is at most 64 MiB; otherwise it is a 阻塞 finding (ADR-0037).

Each result is a 预检发现 with one impact, 阻塞, 数据有损, or 仅行为差异 (ADR-0029). ADR-0003's byte formula and one-table aggregate requirements supersede the older shorthand `MAX(LENGTH(column))`. Failure to finish an exact scan is `INCONCLUSIVE`, not a warning. A preflight result is not the source baseline and may become stale before the write freeze; a runtime value that changes beyond a proven boundary still fails loudly and requires a new run.

## 7. Table contract, identifiers, routing, and target DDL

### 7.1 Mapping rules and identifiers

One migration task maps one MySQL database to one PostgreSQL schema. The wizard pre-fills the source database name; it never defaults to `public`. DBX creates a missing selected schema when the management account proves permission.

Schema, table, and column names are preserved character-for-character and always double-quoted. PostgreSQL reserved words need no special path because quoting is mandatory. Reports include correctly quoted sample SQL for operators.

PostgreSQL identifiers are limited to 63 bytes. An overlong column name is unsupported because Sink must address the exact Connect field; pruning that column is the only v1 escape. Overlong schema/table names are deterministically renamed as `<utf8-prefix>_<hash12>`, where `hash12` is the first 12 lowercase hexadecimal characters of SHA-256 over the length-prefixed UTF-8 source database/schema/table coordinate and the prefix is truncated only at a UTF-8 code-point boundary so the complete target name is at most 63 bytes. DBX checks the actual target namespace character-for-character for collisions; any collision is blocking rather than resolved by another implicit rename. The mapping rule, full source/target coordinate, algorithm version, and resulting name are frozen in the contract and routing snapshot and shown as a source-to-target row in review and the final report. A first-run target name collision is blocking; only ADR-0006's rerun path may reuse and clear a DBX-owned table.

A single structured mapping-rule model covers table rename, column prune, column rename, and target-type override, with `AUTO` or `USER` origin. User intent overrides an automatic rule. V1 does not support regular expressions. Column prune and rename are Source query expressions (`SELECT ... AS ...`) in an isolated box, never DDL-only renames.

### 7.2 Topic routing

The normal topic name contains run identity and the source table name; a Sink `RegexRouter` strips the run prefix to derive the approved target table. Topic names must satisfy Kafka's allowed characters, 249-byte limit, dot/underscore metric-collision rule, run-local uniqueness, and absence/non-reuse checks.

A table with an illegal, overlong, colliding, or explicitly exceptional topic uses a DBX-generated safe alias in an isolated box and a fixed `table.name.format` mapped to the real quoted target table. The ordinary DBA flow does not expose topic names. If an exception input is shown, it is pre-filled and cannot proceed until all naming and non-reuse checks pass.

The routing snapshot, not parsing the topic name, is authoritative for diagnostics, recovery, validation, and cleanup.

### 7.3 Minimal writable table

Before transfer DBX creates:

- ordered columns and exact mapped types;
- source `NOT NULL`, except a per-column relaxation when zero-date-to-NULL is enabled and exact preflight observed affected rows;
- the source primary key, or one unambiguous all-non-null unique-index candidate pre-selected and checked by default for operator approval; the operator may decline it and knowingly take the no-primary-key path;
- auto-increment as `GENERATED BY DEFAULT AS IDENTITY` for integer targets;
- an explicit owned `bigint` sequence for an auto-increment `BIGINT UNSIGNED` mapped to `numeric(20,0)`.

Post-transfer DBX aligns identity/sequence state with `setval`, including the empty-table `is_called=false` case. If the source's next unsigned auto-increment value exceeds signed `bigint`, preflight blocks rather than creating a sequence that cannot continue correctly.

If there is no source primary key, the candidate is declined, or there is not exactly one safe candidate, DBX creates no key and records a 仅行为差异 finding: the table cannot reject duplicate delivery, selected-table reruns must clear the target, key checks are not applicable, and any green result has weaker coverage than a keyed table. A candidate whose estimated PostgreSQL B-tree entry can exceed 2704 bytes is not created and records a 数据有损 finding.

Supported defaults are literal constants and `CURRENT_TIMESTAMP(n)` rendered as `LOCALTIMESTAMP(n)` for the mapped timestamp precision. Unknown/generated expressions, `ON UPDATE CURRENT_TIMESTAMP`, and zero-date defaults are omitted and reported; DBX never generates maintenance triggers.

DBX does not create unique constraints other than the selected primary key, ordinary indexes, foreign keys, comments, or collation as part of migration. It captures them and delivers an executable supplemental SQL script to the DBA with the migration result; the script is never executed by the v1 migration workflow. Its content, timing (generated from stage 4, frozen per run), and its single task-level download are decided by ADR-0026.

Finding impacts share one meaning (ADR-0029): 阻塞 stops the table; 数据有损 means the target holds or can do less, such as relaxed nullability or an uncreatable primary key; 仅行为差异 means values are intact and only later writes behave differently, such as omitted defaults, narrowed sequence continuation, absent secondary structures, or a no-primary-key table.

### 7.4 Structural proof and Sink

After DDL, the target dialect reads PostgreSQL catalogs and compares object kind, exact identifiers and columns, normalized exact types/parameters, nullability, supported defaults, primary-key order, identity/sequence/default/ownership, routing evaluation, and Connect/JDBC binder compatibility. Any difference blocks Sink: the unit ends 迁移失败 with the difference as evidence, never as a preflight finding (ADR-0026). The unit then drops the table its own run just created, under the advisory lock, only while the generation is still this run's, the table has zero rows, and no Sink was created (ADR-0006). A fabricated production insert is forbidden.

Sink uses `auto.create=false`, `auto.evolve=false`, `insert.mode=insert`, `pk.mode=none`, `delete.enabled=false`, and `quote.sql.identifiers=always`. Configuration, contract, and routing fingerprints are persisted together.

## 8. Scheduling, lifecycle, and capacity

Tables are first grouped by identical execution signature. Query-mode, naming-exception, and large-record tables are isolated. Remaining tables are sorted by conservative estimated bytes and packed by LPT with at most 50 tables per box. Exactly empty tables create no topic or connector but still receive DDL and validation.

The run plan is immutable. Rolling admission starts the next eligible box whenever the strictest of six budgets permits: Connect tasks, active boxes, source connections, target connections, Kafka disk, and the cumulative platform memory budget (平台内存预算), where each box reserves Connect heap computed from its settings and its tables' row sizes (ADR-0031). Defaults and formulas remain those in ADR-0002: twice logical CPUs for Connect tasks, at most 10 connector-active boxes, independent database budgets based on 10% of `max_connections` clamped to 4–20 with two reserved connections, and real disk rechecks before admission. The box target size divides the Kafka disk budget by the computed maximum concurrency `min(10, Connect tasks ÷ tasks per box, source connection budget, target connection budget, ⌊(effective heap − B) ÷ R_narrow⌋)`, with `R_narrow` ADR-0031's narrow ordinary box reservation at the effective heap the environment check read.

Planned transfer bytes use `1.5 * max(MySQL DATA_LENGTH, frozen row count * average row length)` with bounded sampling when statistics are unusable. This is capacity planning, not correctness evidence.

Kafka disk uses 60% of available capacity for new admission. At 80% filesystem usage the runtime condition turns 需留意, with no banner and no interruption (ADR-0021). At 90% usage or under 10 GB free, DBX stops producing Sources, allows healthy Sinks to drain, and fails affected execution rather than deleting unvalidated data. Retention is not backpressure or cleanup.

Progress samples topic and Sink offsets every 10 seconds. Expensive target counts occur at completion, validation, recheck, or manual diagnosis boundaries. Time estimates follow ADR-0019 as amended by ADR-0034 and ADR-0038. The duration estimate appears only once the draft's preflight completes and always shows the minimum window. It replays the scheduling plan on per-shape stream rates under one shared ceiling: from this source data source's history where it has enough samples, else the reference table the release ships (three shape bands, two anchor row lengths, the ceiling), which makes it 低置信. The remaining-time estimate replays the unfinished plan at observed rates, falling back to 无法预估 with its reason when no longer credible. One backend estimator feeds every surface. Estimates never drive correctness or `STUCK` decisions.

Read/write completion, connector ordering, stable-poll rules, ten-minute `STUCK`, idempotent REST reconciliation, one-pass bulk behavior, and cleanup retries are defined by ADR-0001; its two-minute no-progress warning shows only on the unit (ADR-0021). Kafka, Connect, or Schema Registry unreachable for ten continuous minutes fails the affected boxes (ADR-0021, ADR-0039); database unreachability is run-scoped under ADR-0006's ten-minute budget. A Connect restart fails its running boxes at once, detected by the restart marker on the 5 s status poll (ADR-0032). A second Connect restart, or two consecutive zero-output stuck boxes, opens an admission pause: the run shows 需要人工处理 until the DBA chooses 继续迁移 (ADR-0039). Loss of required connector/topic/offset/target continuity invokes ADR-0006: the old run fails safely and a selected-table rerun starts from a clean target generation.

## 9. Validation and diagnosis

### 9.1 Validation precondition and sequence

Validation starts only after Source is absent, Sink lag is zero, exact target count reached the baseline, and Sink is absent. The source write freeze remains valid while final source and target scans run. Validation uses dedicated read-only JDBC connections.

Validation SQL has a default 30-minute timeout as an advanced system setting. Source and target endpoints each permit at most two tables to validate concurrently. On timeout DBX calls `Statement.cancel()`, closes the dedicated connection, records the database backend/thread identity, and does not release the validation slot or finalize the table until query termination is confirmed. Timeout, connection failure, or unconfirmed cancellation is `INCONCLUSIVE`; DBX never substitutes sampling or a skipped pass.

For each table, record `sourceBaselineCount`, `sourceFinalCount`, and `targetCount`. A changed final source count is `INCONCLUSIVE / SOURCE_CHANGED`; a stable source with a different target count is `FAIL / ROW_COUNT_MISMATCH`; all equal passes the row-count item. Equality does not prove no same-count updates or delete/inserts, so reports never claim full row equality.

### 9.2 Automatic checks

For every migrated exact numeric column, compare `COUNT(column)`, `SUM`, `MIN`, and `MAX` after normalizing JDBC results to arbitrary-precision decimals and using numeric comparison. All-NULL is represented as count zero and null aggregates. Use batches of at most 300 exact-numeric columns (four expressions each) with matching source/target batches. Floating types and Booleans are not exact numeric aggregate assertions.

For a primary key:

- verify each component's non-nullness;
- use a proven target primary-key/unique constraint as uniqueness evidence, otherwise group by all typed components and detect any count above one;
- compare `MIN/MAX` only for single keys with provably equivalent ordering: integers, exact decimals, dates, and byte-ordered binary keys;
- mark string/collated and composite-key extrema not applicable rather than manufacture a cross-database order.

A table without a key records `NOT_APPLICABLE / NO_PRIMARY_KEY` and reduced coverage. It can pass the checks applicable to it, but the report must not imply row identity or duplicate protection.

### 9.3 Manual deterministic sampling

Manual sampling defaults to a target of 1000 rows and requires a primary key. Numeric single keys use deterministic evenly spaced seek thresholds with arbitrary-precision arithmetic. Other keys use the first 500 and last 500 source rows under the typed source key order. Keys are normalized and deduplicated; the report states the actual sample count.

Each target lookup uses typed parameterized equality for every key component and must find exactly one row. Value comparison is semantic:

- `NULL` only equals `NULL`;
- integers and decimals use arbitrary-precision numeric equality;
- float/double compare their actual Java IEEE values without an invented tolerance;
- `CHAR` removes trailing U+0020, while `VARCHAR/TEXT` does not;
- `DATE`, `TIME`, and `DATETIME` compare `Local*` values at millisecond precision;
- `TIMESTAMP` compares UTC `Instant` milliseconds;
- binary compares exact bytes;
- `json`/`text` compares the certified text representation; `jsonb` ignores object-key order, applies PostgreSQL's duplicate-key last-value semantics, preserves array order, and compares numbers with arbitrary-precision numeric equality;
- approved Boolean and zero-date conversions compare their target semantics.

A sample not run is `NOT_RUN` and does not prevent automatic green. Once run, its failure or inconclusive result participates in the table conclusion.

### 9.4 Results and disposition

Every validation item stores type, status, stable reason, values/evidence, start/end, duration, batches, and error. States are `PASS`, `FAIL`, `INCONCLUSIVE`, `NOT_APPLICABLE`, and `NOT_RUN`. Required failure dominates; otherwise required inconclusive dominates; otherwise all enabled applicable required checks must pass. Timeouts, cancellation failures, source drift, or unavailable proof are inconclusive, never skipped success.

The green wording is: **all enabled v1 validations passed**. Reports state coverage such as aggregate-only or aggregate plus sampled rows. An operator may add a reasoned accepted-risk disposition to a failed or inconclusive table, yielding `COMPLETED_WITH_ACCEPTED_RISK`; the original items remain unchanged and the result is amber, not green.

### 9.5 Diagnosis

ADR-0005 separates error occurrence, diagnosis, and workflow outcome. Structured DBX evidence outranks protocol/database/HTTP codes, deep causes, and constrained text patterns. The first release ships 21 versioned external-translation rule families (the 21st is MySQL 1114, 源库临时空间耗尽, from ADR-0033's bounded reads) with positive, negative, overlap, and redaction fixtures. Routing snapshots provide coordinates; shared failures remain box-scoped when table attribution is unproven.

The operator sees what happened, where, affected scope, and one action, with technical evidence expandable. Diagnostic packages are local, bounded, and redacted; credentials and record/parameter values never enter them. Their scopes, trigger, manifest, and bound are in [ADR-0028](adr/0028-diagnostic-package-scopes-manifest-and-no-values.md).

## 10. Operator journey

The migration wizard (ADR-0020) builds a server-side migration draft in five gated stages and ends at execution:

1. **连接与数据库** — select saved verified connections, one source database, and one target schema.
2. **迁移范围** — searchable, deterministic table selection and explicit exclusions (ADR-0025); no regular expressions. No duration estimate yet.
3. **预检** — exact preflight findings with their impact; the duration estimate first appears here.
4. **映射规则** — zero exceptions by default; table and column exceptions in a per-table drawer with read-only DDL. A rule makes the touched tables' preflight stale.
5. **执行确认** — scope, exclusions, contracts, open findings, the duration estimate with its minimum window, and the accountable expiring write-freeze confirmation inside the task write freeze; an estimate upper bound beyond the freeze's limit warns but never blocks.

The stage sequence is a safety gate, not decorative navigation. Unsupported or inconclusive preflight cannot be acknowledged away; DDL cannot be edited; Sink cannot start before structural proof; accepted risk cannot change a technical result; remigration creates a new run.

A run is observed on the task detail page, not in the wizard. A task header above the run switcher holds everything task-scoped: task status, the task conclusion (整库结论) with a per-table drawer, the 下载补建 SQL button, and 废弃 in a danger menu. The five tabs below it are purely run-scoped, led by 运行监控 and 校验报告; boxes, connectors, and topics stay internal.

The shell is a flat sidebar (迁移任务, 数据源, 系统设置) and a top bar carrying the runtime condition indicator (ADR-0021). V1 has no authentication and ships zh-CN only, with the language switch hidden. The run progress source and the installation-scoped status channel both poll every 10 s, plus a refetch after each user command (ADR-0016).

## 11. Deployment and operations

### 11.1 Built-in infrastructure

The built-in Docker Compose deployment contains DBX, Kafka, Connect, and Schema Registry, plus customer-provided MySQL Connector/J mounted through the installation flow. PostgreSQL and MySQL are customer endpoints. V1 ships only this deployment; customer-managed Kafka, Connect, and Schema Registry are v2. Its fitness is proven by the environment check of ADR-0027 (E0–E8), which detects and explains but never remediates.

The Connect worker's heap is set by ADR-0031's memory tier, which ADR-0035's install script chooses from container-visible memory. Ordinary boxes use ADR-0031's bounded producer and consumer settings. Only large record tables keep ADR-0003's 128 MiB producer buffer, the 25/50 MiB fetch limits, and single-record Sink polling. Brokers keep the fixed 25 MiB message limits.

### 11.2 Kafka storage

Capacity planning must account for the conservative table estimate, concurrent admitted boxes, currently retained failed/unvalidated topics, actual replication factor, Avro/protocol overhead, and observed compression. Environment check item E6 requires at least 50 GB free at startup and at least twice the run's largest table before admission (ADR-0027); admission also applies ADR-0002's 60/90 percent gates to current measured free space.

A successful table releases estimated disk only after topic absence is externally confirmed. Failed validation, cancellation, accepted risk, cleanup delay, and diagnostic retention continue counting against capacity. Operators must provision for retained evidence; retention must not silently solve pressure.

### 11.3 Metadata, secrets, and backups

One DBX instance owns the file-backed H2 database. Flyway finishes before workers/reconciliation. Required backups precede schema upgrades and destructive actions and occur hourly by default during operation, keeping the last 48. H2 corruption or missing control truth fails closed.

Credential versions use AES-256-GCM with a master key mounted as a file in the install's `secrets/` directory; H2 holds only its fingerprint, and environment check item E8 proves the key is present and matches, at startup and before admission (ADR-0035). Connect receives only ConfigProvider references to run-local secret projections. The credential destruction ledger and per-backup key erasure prevent old backups from reviving destroyed secrets.

### 11.4 Distribution and licensing constraints

The adopted Confluent components may be distributed for customer private deployment under the researched CCL constraints, but CCL is not OSI-approved and procurement must review it. DBX must not bundle MySQL Connector/J in a proprietary distribution; installation requires the customer to provide the JAR with version and checksum verification. The JDBC connector package must be curated to remove unused bundled database drivers rather than inherit unrelated Oracle, SQL Server, SQLite, or other license obligations.

A release includes third-party notices and an SBOM, fixes the tested component/image versions, and reruns certification after any driver, connector, converter, Kafka, Schema Registry, MySQL, or PostgreSQL change. The offline package, the one release version and its bill of materials, in-place upgrade, rollback and the master key's placement are decided by ADR-0035. Whether to certify an Aiven JDBC + Apicurio escape route remains an unresolved decision, not a v1 assumption. Licensing provenance is retained on [`research/ccl-licensing`](https://github.com/liumingjian/dbx/tree/research/ccl-licensing).

## 12. Known limits

- **Database pair**: only MySQL 8.0 to PostgreSQL 15 is certified.
- **Consistency**: source stability depends on an accountable external write freeze. DBX cannot prove that same-count updates did not occur.
- **Mode**: offline one-time full copy only; no CDC or incremental synchronization.
- **Rerun**: a rerun creates a new migration run and fully recopies each selected table after controlled target clearing. No data checkpoint resume after lost continuity.
- **Single-table throughput**: no single-table sharding. One table is limited by one extraction stream and its Source/Kafka/Sink/target path; large records additionally use single-record polling. No throughput SLA is promised: the release's reference table and its machine spec (ADR-0034, ADR-0035) let an operator estimate a window; they do not guarantee its length.
- **Bulk reads**: a table without a keyset column migrates only while baseline rows × its largest row byte length fit 64 MiB (ADR-0037).
- **Metadata growth**: no evidence retention; H2 grows with every run and must be sized for it.
- **Record size**: every selected source value and pre-serialization row payload must be at most 20 MiB (20,971,520 bytes). Kafka uses a separate 25 MiB (26,214,400-byte) envelope.
- **Time precision**: temporal values are supported to milliseconds; microseconds are not preserved.
- **Unsigned range**: target `numeric(20,0)` preserves the declared `BIGINT UNSIGNED` target domain, but current Source reading supports only actual values through `2^63-1`.
- **Types**: whitelist only; geometry, `BIT(n >= 8)`, and unknown types require column pruning or exclusion.
- **Validation claim**: green means all enabled v1 checks passed, not full row-by-row identity. Unkeyed tables have weaker duplicate and row-identity evidence.
- **Target structure**: migration creates a minimal writable table. Unique constraints beyond the selected primary key, ordinary indexes, foreign keys, comments, and collation are supplemental SQL, not automatically executed.
- **Data shaping**: no row filters, arbitrary value transforms, masking, user SQL, or large-record bypass.
- **Operations**: no broad worker/broker log ingestion and no DLQ-success path. Only the built-in Kafka, Connect, and Schema Registry are supported.
- **Language**: the interface ships zh-CN only.
- **Extensibility**: static certified dialect/pair catalog only; no dynamic plugins, arbitrary connector settings, or automatic endpoint composition.

## 13. V2 candidates and unresolved decisions

The following are intentionally not designed by v1:

- CDC/incremental execution and a second data path;
- consistent single-table sharding and shard lifecycle;
- additional directed database pairs and dynamic extension mechanisms;
- an instance-level group spanning multiple source databases;
- cross-task reusable mapping templates and a whole-instance selector;
- direct execution of indexes, unique constraints, foreign keys, comments, or collation after load; v1 still generates and delivers their executable supplemental SQL;
- the commercial shell: authentication, license, members and roles, audit log;
- customer-managed Kafka, Connect, and Schema Registry;
- the en-US interface (message keys already exist);
- a throughput SLA (v1 publishes only the reference table) and a trial run that measures throughput without writing the target;
- Linux x86_64 server deployment (v1 ships `linux/arm64` for macOS with Docker Desktop, ADR-0035);
- procurement policy and a continuously certified Aiven/Apicurio fallback.

These remain Wayfinder fog or future initiatives. No placeholder SPI or permissive configuration should commit the codebase to an answer prematurely.

## 14. Implementation sequence

1. Establish Java 21, Spring Boot, Gradle Kotlin DSL, React 18/TypeScript/Vite/Ant Design/TanStack Query, and version-locked test environments without adding feature abstractions.
2. Implement closed domain value types, pure MySQL/PostgreSQL dialect/pair mapping, identifiers, preflight plans, table write contract, fingerprints, DDL, and structural difference reporting.
3. Apply Flyway schema and Spring JDBC repositories for connections, immutable runs, table units, boxes, contracts, evidence, cleanup, revisions, and leases; implement the single-writer command queue and transition service.
4. Implement database gateways, capability checks, credential encryption/projection, source metadata, exact preflight, baseline, target DDL, and structural proof.
5. Implement Kafka/Schema Registry/topic ownership, connector configuration, routing snapshots, lifecycle reconciliation, external completion, cleanup, and recovery.
6. Implement immutable scheduling and rolling resource admission.
7. Implement validation plans/executions, diagnosis catalog, timeline/report projections, and diagnostic export.
8. Implement the five-stage wizard and the run-organised task console against the real application APIs.
9. Run the complete certification suite and produce an installation/operation release only from a passing fixed version set.

## 15. Acceptance and test strategy

§15.1–15.3 are rungs L1–L3 of the verification ladder, and the L4 `packageTest` closes §15.3; budgets, who runs each rung, and golden-file rules: ADR-0022.

### 15.1 Pure and persistence tests

- Golden case for every type-matrix row and important source-metadata variant.
- Exhaustive MySQL 8.0 type-list test: every type returns supported or a stable unsupported reason.
- Property tests that alternatives only widen value domains and mapping/contract/DDL/fingerprints are deterministic.
- Identifier quoting, 63-byte handling, mapping-rule precedence, safe topics, collisions, routing evaluation, query projection/aliases, and SQL injection boundaries.
- Combined preflight-plan generation and exact expressions, including 20 MiB value/row, Boolean, unsigned, time, enum, zero-date, key width, and sequence bounds.
- Contract completeness, version snapshots, DDL rendering, default/identity/sequence/nullability rules, supplemental-SQL boundary, and structured differences.
- validation SQL timeout, endpoint concurrency, `Statement.cancel()`, backend/thread evidence, and confirmed termination before slot release;
- state-transition, optimistic revision, idempotency, queue coalescing, transaction rollback, projections, leases, cleanup ownership, and Flyway upgrade/backup fixtures.

### 15.2 PostgreSQL contract tests

Using PostgreSQL 15 Testcontainers, execute every representative generated DDL and require zero-difference catalog proof. Mutate case, type parameters, nullability, defaults, primary-key order, identity, sequence default/ownership, extra/missing columns, and routing inputs individually and require a blocking structured difference before Sink starts. Production probe inserts are not part of this test protocol.

### 15.3 Full real-stack certification

Pin and run MySQL 8.0, PostgreSQL 15, Kafka, Schema Registry, Connect, JDBC Source/Sink, Connector/J, and pgjdbc. Assert target structures and values, not connector green status. Cover:

- every type family and unsupported boundary;
- `BIT(1)`, `YEAR`, `BIGINT UNSIGNED`, `DECIMAL(38,10)`, JSON with Chinese/emoji, UTC, `DATETIME` versus `TIMESTAMP`, zero dates, and `TIME` range;
- normal routing plus safe alias, column pruning, and column rename query mode;
- empty tables, keyed/unkeyed tables, composite keys, repeated delivery, constraint failure, and target mismatch;
- exactly 20 MiB payload, above 20 MiB, and near-25 MiB transport capability;
- Sink-first/Source-first lifecycle, empty/delayed Source offsets, bulk emulation, stable completion, stuck/failure, restart reconciliation, orphans, and lost continuity;
- bounded reads: a bulk table at the 64 MiB cap, and a keyset table with a non-monotonic key and a zero minimum (ADR-0037);
- the Connect restart marker protocol on the pinned Connect image (ADR-0032);
- box failure isolation, disk gates, cancellation, discard, target generations, selected-table full rerun, topic/subject/secret cleanup;
- validation pass/fail/inconclusive/not-applicable/not-run and accepted-risk separation;
- all 21 diagnosis families with overlap and redaction;
- contract codec and upgrade regression.

Any change to a connector, converter, driver, Kafka/Registry, or database version reruns the affected pure, PostgreSQL, and full-stack gates.

Before a release tag, L4 `packageTest` (ADR-0035) proves the package installs offline, upgrades from the previous release, and rolls back.

### 15.4 Journey acceptance

Automated UI/API journey checks prove that:

- no selected table means no progress;
- unsupported or inconclusive preflight cannot be approved;
- mapping changes invalidate the contract and rerun affected evidence;
- DDL is read-only;
- no write freeze means no run start;
- no structural proof means no Sink;
- monitoring is table-centered and boxes remain internal;
- technical validation and accepted risk are visually and semantically distinct;
- remigration creates a new run and displays its selected scope.

A v1 release is accepted only when all documentation authority links remain valid, all above gates pass on the fixed version set, known limits appear in operator-facing material, and no unresolved v2 behavior is accidentally exposed as a supported configuration.
