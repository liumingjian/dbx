# Versioned connections, evidence-based recovery, and clean reruns

DBX v1 treats database access, execution recovery, and data reruns as separate concerns. A saved connection is reusable configuration; a migration run is an immutable execution snapshot; recovery may continue that run only while its original external execution can still be proven; a rerun always starts again from a clean target generation.

## Connection and credential model

A **database connection** represents a reusable server endpoint and identity, not a migration task. It stores structured host, port, database-product, TLS, username, semantic JDBC settings, and bounded operational settings. A task selects one source MySQL database, one target PostgreSQL database, and one target schema. Each run snapshots those selections together with the effective connection semantics, driver version, credential versions, and database-instance identity.

DBX does not accept arbitrary JDBC URLs or parameters in v1. Parameters that affect returned types or values—character encoding, session timezone, `tinyInt1isBit`, zero-date handling, and TLS verification—are controlled and fingerprinted by DBX. TLS material and timeouts use explicit fields. Connection and preflight checks verify the effective server character set, timezone, TLS state, product version, and stable instance identity rather than trusting configuration text alone. V1 supports username/password authentication, server-authenticated TLS, and mutual TLS. Dynamic database tokens, Kerberos, SSH tunnels, Vault/KMS integration, multi-host JDBC URLs, and custom authentication plugins are outside the v1 contract.

Secret material is stored in H2 as immutable **credential versions**, encrypted with AES-256-GCM. The master key is supplied independently as a deployment secret; it is never stored in H2, an image, or a diagnostic package. A shared connection update affects new runs only. When authentication failure interrupts a nonterminal run, an operator may explicitly adopt a new secret-only credential version for recovery. This appends an audited run credential binding and retains the initial version reference; it never mutates the immutable run snapshot. Endpoint identity, username, trust boundary, database selection, and semantic settings cannot change, and external execution continuity must still be proved before work resumes.

Kafka Connect receives no literal database password in connector configuration. DBX projects the referenced credential into a run-local secret and connector configuration uses a ConfigProvider reference. The v1 file-provider adapter atomically writes a versioned file, verifies permissions and a non-secret probe through Connect, and removes the file only after every connector that references it is confirmed stopped. The built-in Compose deployment mounts this directory only for DBX and Connect. A customer-owned Connect deployment is supported only when the same secured filesystem path is mounted at the same worker-visible location on DBX and every eligible worker; a DBX-local file or a capability check alone is insufficient. Provisioning verifies all eligible workers through a non-secret provider probe, configuration REST responses must preserve references, and cleanup is idempotent. Provider identity, mount identity, and secret-reference semantics enter the execution fingerprint. Deployments that cannot provide this shared path must wait for a future remote-provider adapter; v1 never falls back to plaintext. Active runs retain every encrypted version needed by a connector that may still resume or stop.

Once no active execution or cleanup needs an obsolete credential, DBX records an append-only destruction tombstone and destroys its ciphertext while retaining version, actor, usage, and destruction metadata. Backup sets are encrypted with per-backup data-encryption keys; destroying a credential also erases the wrapped backup keys for every retained backup that could expose that version. The separately protected master-key backup cannot recover an erased per-backup key. A restore reapplies the destruction-tombstone ledger before DBX may decrypt credentials or start reconciliation and rejects a backup whose tombstone continuity cannot be proved. Retention then removes the remaining unusable backup artifacts on schedule.

A connection is archived rather than deleted while any task or run refers to it. Historical runs retain a redacted endpoint snapshot and version references rather than depending on mutable connection records.

## Capability checks and minimum privileges

Saving a connection runs a lightweight connectivity and identity check. Every run performs a fresh, scope-specific capability check before approval and execution.

The MySQL account is read-only. It must connect to the selected database, read required metadata, select every chosen table, and execute the bounded counts, extrema, aggregates, and large-record preflight queries required by the approved plan. DBX neither requests write or lock privileges nor enforces the source write freeze itself.

PostgreSQL uses one target account by default. An optional hardened mode separates a management account from a Sink writer. The management account creates or uses the selected schema, creates and introspects DBX-owned tables and sequences, validates data, deliberately truncates for rerun or discard, and grants exact object privileges. The Sink account receives only schema `USAGE`, table `INSERT`, and the sequence rights demonstrated necessary by the supported connector version. DBX may grant those privileges on the specific objects it created; it does not create roles, alter role membership, change default privileges, grant across a whole schema, request superuser, or revoke customer privileges automatically.

Target capability checks use an isolated, uniquely named probe object and exercise create, insert, read, truncate, and drop without touching a production target table. Every probe has a durable cleanup request. A failed check identifies the missing capability and produces conditional least-privilege SQL for a DBA; DBX never executes `GRANT` outside the bounded DBX-owned-object rule.

## Write-freeze contract and endpoint identity

The source **write freeze** is a time-bounded operator commitment with database scope, accountable actor, confirmation time, expiry, and optional change reference. It must remain valid from exact source-baseline capture until every selected table reaches a validation terminal state or the run is stopped. The operator may extend the commitment before expiry without changing the baseline. Observed row or primary-key drift proves a violation, but absence of observed drift never proves that updates or deletes did not occur.

At expiry, or when an operator declares the freeze broken, DBX stops admitting boxes, stops Source before Sink, preserves evidence, and fails unfinished units with a stable source-freeze reason. It never refreshes the baseline inside the same run. A new freeze and baseline require a new run.

A run binds both the configured logical endpoint and the database instance identity observed during preflight. A process restart, IP change, or ordinary certificate renewal may continue only when product, instance identity, TLS trust, and data facts remain equivalent. A source or target instance change, an unprovable failover, or a changed trust boundary fails the affected execution. V1 chooses correctness over seamless database failover.

## Recovery of the same execution

Recovery is reconciliation of the original execution, not a data retry. Temporary database, Kafka, Schema Registry, or Connect unavailability receives a default ten-minute bounded recovery budget with exponential backoff, jitter, explicit request timeouts, and visible remaining time. Read-only observations may be retried. Every external mutation persists intent first and is retried only after rereading facts:

- an already-satisfied state is accepted;
- an existing resource with the expected identity and normalized semantic fingerprint is reconciled;
- an absent resource may be created only when no prior ambiguous creation can have taken effect;
- a conflicting resource or unprovable state fails safely.

After platform restart or transient infrastructure restart, DBX may continue the same run only if the original connectors, topics, offsets, target generation, target facts, and configuration semantics remain continuous. A connector missing before its recorded stop boundary, a missing or truncated topic, offset regression, target count beyond the baseline, fingerprint conflict, expired write freeze, or changed database instance fails the box. DBX does not delete and recreate a missing connector to claim offset continuity.

A pure control-plane preparation failure may become `ATTENTION_REQUIRED`; an active transfer whose continuity remains unprovable after the budget fails. Cleanup continues retrying without changing the migration result. Cancellation remains `CANCELLING` until connector-stop facts converge; it is never reported complete because a retry budget expired. Connector-internal `RUNNING` state does not defeat the established two-minute warning and ten-minute `STUCK` diagnosis.

Only safe observations and fact-reconciled mutations are retried. Ambiguous DDL is resolved by target introspection: exact contract means success, no effect permits another attempt, and a partial or divergent structure stops for attention. DBX never blindly repeats DDL, `TRUNCATE`, baseline capture, or a user decision.

H2 remains the control-plane recovery authority. Consistent, checksummed backups are taken before Flyway upgrades, before a run's first destructive target action, before discard, and hourly by default during operation. Each backup is encrypted with a distinct data-encryption key whose wrapped form is tracked by the destruction ledger; the deployment master key and append-only tombstone ledger are protected and backed up separately from H2. If H2 is unavailable or corrupt, DBX fails closed and mutates no external resource. It may recover from a supported backup only after proving tombstone-ledger continuity, erasing any wrapped backup keys revoked after that backup, and reapplying credential destruction before secret access or reconciliation. It then reconciles newer external facts, but never reconstructs approved contracts, baselines, routing, or outcomes by guessing from connector names, topics, or target tables. Facts that do not uniquely prove continuity cause failure or orphan reporting, never forced adoption.

## Cancellation and discard

Cancellation applies to the whole run. It stops new admission and validation, confirms all Source connectors stopped first, then stops Sink connectors without draining the remaining Kafka backlog. Target data, topics, subjects, run-local secrets needed for stopping, and diagnostic evidence remain in place. Units already failed or succeeded keep those outcomes; unfinished units become `CANCELLED`. A restart during cancellation continues stopping execution and never resumes migration.

**Discard** is a separate, audited destructive command after execution has stopped. Run-local connectors, topics, subjects, probe objects, and secret projections are deleted only from authoritative ownership records and in dependency order. Schema Registry subjects are deleted only after their topic is confirmed absent and no retained topic shares them.

A target table has a **target generation**. DBX creates a new generation when it first creates or deliberately truncates that table for a run. Discard may truncate a target table only while the current generation still belongs to that run, no connector can write it, its structure still matches, and no active run holds it. It never uses `CASCADE`, and it does not drop the table or schema by default. Once a later run takes a new generation, an older run can discard only its run-local resources and can never touch current target data. Discard appends resource facts; it never rewrites the run's original technical outcomes.

## Rerun semantics and exclusion

A rerun is always a new migration run with new table migration units, connectors, topics, baselines, routing snapshot, scheduling plan, and target generations. It may include one table, a chosen subset, or the entire task, but its report names that scope and does not present a partial rerun as a new whole-task success.

The task retains current user-authored mapping rules and choices. A rerun freshly tests connections and capabilities, reads source metadata, executes preflight, obtains a new write-freeze commitment and source baseline, regenerates write contracts and automatic rules, and introspects target structures. A changed contract or semantic input requires approval. An unchanged contract is shown as a zero-difference review and still receives one confirmation.

For an existing target table, DBX proceeds only when introspection proves the approved contract exactly. It first acquires the persistent target lease, then reads and displays the destructive scope and current row counts for confirmation. Immediately before `TRUNCATE`, DBX acquires the PostgreSQL advisory lock and rereads the structure, generation, and row counts; any difference from the confirmed facts aborts the action and requires a fresh confirmation. It performs `TRUNCATE` without `CASCADE`, never defaults to drop-and-recreate, and never removes customer constraints to make a rerun work. Foreign-key dependencies, structural drift, or ambiguous effects require operator action. A missing table follows the ordinary approved creation path.

V1 therefore supports coordinator and infrastructure recovery of a provably intact execution, but it does not offer data-level checkpoint resumption after execution resources are lost. The cost of lost continuity is a fresh full migration of the selected tables.

## Target concurrency

Before any target DDL or truncation, a run atomically acquires persistent table-level leases for its whole selected scope. The key combines the actual target server identity, database, and case-sensitive schema and table names; different saved connection records cannot bypass it. If any table conflicts, the run performs no destructive target action and becomes `ATTENTION_REQUIRED`. One migration task may have only one nonterminal run.

The H2 lease is the durable business authority and does not expire merely because the process paused. PostgreSQL transaction-scoped advisory locks provide a second guard around creation, truncation, structural checks, generation changes, and discard. A lease remains until all connectors capable of writing that target are confirmed stopped. Topic and subject cleanup need not hold the table lease because those resources are run-local. V1 cannot prevent manual writes or a separate DBX control plane from ignoring its durable lease; the deployment contract therefore assigns a target-table set to one DBX control plane.

## Consequences

The model keeps reusable setup convenient for a non-developer DBA while preventing mutable connection settings, secret rotation, failover, or retries from silently changing execution semantics. Platform and infrastructure restarts usually continue without intervention when the same execution survives. Real loss of data-plane identity, an expired freeze, or missing control-plane truth deliberately costs a new full run.

Secrets have a clear persistence and destruction lifecycle, but the distribution must configure the Connect file-provider path and operators must protect the master key, per-backup key wrappers, and destruction ledger separately. The built-in Compose path is direct; customer-owned Connect additionally requires one secured, identically mounted filesystem visible to DBX and every eligible worker. The optional split target account supports least privilege without burdening the default path. Cancellation is fast and evidence-preserving; destructive cleanup remains explicit and cannot reach data owned by a later run.

## Rejected alternatives

- Plaintext credentials, per-run password entry, and literal connector passwords were rejected because they respectively expose secrets, prevent unattended recovery, or persist secrets in Connect's configuration plane.
- Arbitrary JDBC URLs and dynamic authentication were rejected because they create untested value semantics and refresh behavior that cannot be snapshotted or reconciled safely.
- Recreating missing connectors and resuming from presumed offsets was rejected because DBX cannot prove absence of gaps or duplicates.
- Refreshing a baseline after a broken write freeze was rejected because one run would contain tables from different source boundaries.
- Automatic drop/recreate, `TRUNCATE ... CASCADE`, and cancellation-as-cleanup were rejected because they can destroy customer or diagnostic data outside the confirmed scope.
- Reconstructing control state from external resource names was rejected because external facts cannot recover approval, routing, baseline, disposition, or ownership truth.
- Database-wide locks and schema-wide grants were rejected because table-level exclusion and object-level privileges provide the required safety with less interference.
