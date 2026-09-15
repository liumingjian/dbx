# Detecting a Connect worker restart, including one shorter than a poll

> Research ticket: GitHub issue #79. Feeds ADR-0032 (*a Connect restart fails its running boxes*).
> Constraints: no docker.sock, no host agent (ADR-0027). One built-in distributed-mode worker, `confluentinc/cp-kafka-connect:7.9.0` (= Apache Kafka 3.9.x), Compose `restart: unless-stopped` + `-XX:+ExitOnOutOfMemoryError`. A restart is a new JVM with the same config: same `group.id`, same `rest.advertised.host.name/port`, and so the same `worker_id`.
> Allowed interfaces: Connect REST, Kafka AdminClient, the Connect internal topics.
> Method: reading source at the `3.9.0` tag (plus `4.0.0` for forward compatibility) and the KIPs. Nothing was run. Line numbers are approximate (±5).

## Executive summary

1. **Recommended signal: a marker that DBX plants in the worker's memory through the dynamic log-level API (KIP-976, Kafka ≥ 3.7).**
   - Plant: `PUT /admin/loggers/dbx.incarnation` with body `{"level":"ERROR"}`, no `scope` param (the default is worker scope). Nothing ever logs to that logger, so the marker is inert.
   - Check on every poll: `GET /admin/loggers/dbx.incarnation`.
   - **Rule: the worker restarted ⇔ the GET returns `404`, or it returns `200` with `last_modified: null`.** A connection error or 5xx means "unreachable", not "restarted" (ADR-0021 grace applies).
   - After a detected restart, re-plant before admitting anything else.
2. **Why it holds.** `last_modified` comes from an in-memory `Map<String, Long>` in `Loggers`, filled only by `setLevel()`. Worker-scoped changes are never persisted. A new JVM starts with an empty map and a fresh Log4j hierarchy, so the logger is either gone (404) or unmodified (`null`). Verified in 3.9.0 (reload4j) and 4.0.0 (log4j2) source.
3. **Between two polls.** The marker is destroyed by any process exit, however short the gap, and it stays destroyed until DBX re-plants it. DBX does not need to have seen the unreachable window. A restart in the 10 s between polls shows up as a 404 on the next successful poll.
4. **False positives: essentially none.** Rebalances, session expiry, network blips, and GC pauses all leave the marker in place, because the process is the same. Someone changing log levels on `root` or on an enclosing namespace rewrites `level` and `last_modified` to non-null values. The rule deliberately ignores that case: a non-null value is not a restart, and DBX just re-plants the marker.
5. **DBX's own restart loses nothing.** The baseline lives in Connect, not in DBX. After restarting, DBX does one GET. If the marker is intact, Connect did not restart while DBX was down. If it is gone, Connect did restart, and the boxes the reconciliation (ADR-0006) finds still running on that worker get the ADR-0032 failure. DBX does not even need to persist a value. Persisting the planted `last_modified` in the run record is still useful for the diagnostic package, because it bounds when the restart happened.
6. **Fallback / corroboration: the Connect group member id** from `Admin.describeClassicGroups(<group.id>)`. That call needs Java clients ≥ 4.0, because in 3.x `describeConsumerGroups` rejects `connect` groups. The member id is `connect-<host:port>-<UUID>`. It gets a fresh UUID on every process start, and Connect has no static membership. It *also* changes when the same process is fenced by session expiry (`UNKNOWN_MEMBER_ID`). So it over-reports: use it as corroboration, or as the signal when the logger API is unavailable, never as the sole trigger.
7. **Ruled out:**
   - Status-topic `generation` / `worker_id`: `worker_id` is stable, a crash writes nothing, and a new generation also comes from ordinary rebalances.
   - REST `GET /`: only static `version`/`commit`/`kafka_cluster_id`.
   - Task/connector status: no timestamps or incarnation fields.
   - JMX: no remote port unless `KAFKA_JMX_PORT` is configured, and DBX has no JMX client.

## Contents

1. [Recommended: the log-level marker](#1-recommended-the-log-level-marker)
2. [Fallback: Connect group member id](#2-fallback-connect-group-member-id)
3. [Ruled out](#3-ruled-out)
4. [Open items](#4-open-items)
5. [Sources](#5-sources)

---

## 1. Recommended: the log-level marker

### 1.1 Mechanism (source)

- `Loggers` keeps `private final Map<String, Long> lastModifiedTimes` as an instance field (3.9.0 `connect/runtime/.../runtime/Loggers.java` ~L52; 4.0.0 ~L60). `loggerLevel()` returns `new LoggerLevel(level, lastModifiedTimes.get(name))` (3.9.0 ~L196–202). The map is written only in `setLevel()` (~L188). So a logger never modified in this process reports `last_modified: null`.
- `setLevel(namespace, level)` on a namespace with no existing logger creates one through `lookupLogger` → `LogManager.getLogger(namespace)` (3.9.0 ~L146–154; 4.0.0 ~L154–159). Planting under a fresh name like `dbx.incarnation` therefore works, and the next GET finds it.
- `LoggingResource`: `GET /admin/loggers/{logger}` throws `NotFoundException` → **404** for an unknown logger (~L82–91). `PUT` takes `@DefaultValue("worker") scope`. Worker scope calls `herder.setWorkerLoggerLevel` and returns 200 with the affected loggers. `scope=cluster` writes a record to the config topic and returns 204 (~L98–135).
- KIP-976 semantics: *"If no modifications to the namespace have been made since the worker finished startup, the timestamp will be null."* *"Restarting a worker will cause it to discard all cluster-wide dynamic log level adjustments, and revert to the levels specified in its Log4j configuration."* Workers that have not finished startup ignore the cluster-scope records in the config topic. So even a cluster-scope marker would not survive a restart. DBX should still use worker scope, because it leaves no trace in the config topic.
- Availability: the `/admin/loggers` endpoint dates from KIP-495 (AK 2.4). `last_modified` and `scope` come from KIP-976, first shipped in AK 3.7.0. **Holds for AK 3.7 – 4.x**, including CP 7.9.0 (AK 3.9). On AK < 3.7 the 404 half of the rule still works; the `null` half does not exist.

### 1.2 What DBX stores and compares

| When | Call | DBX action |
|---|---|---|
| Before a run admits its first box, and after every detected restart | `PUT /admin/loggers/dbx.incarnation` `{"level":"ERROR"}` | Then `GET` it and record `last_modified` (epoch ms) as `planted_at` in the run record (for diagnostics only) |
| Every poll (10 s) | `GET /admin/loggers/dbx.incarnation` | `404` → **restarted**. `200` with `last_modified == null` → **restarted**. `200` with non-null value → alive, same process. Connection refused / timeout / 5xx → unreachable (ADR-0021), no conclusion |
| On restarted | — | Apply ADR-0032: fail every box with a connector on that worker, count the restart toward 运行状况, then re-plant |

The restart window is bounded by `(last poll that saw the marker, first poll that saw it gone]`. Put both timestamps in the condition-change record.

### 1.3 Behaviour when the restart falls entirely between two polls

- **t0 poll:** marker present.
- **t0+2 s:** OOM, and the JVM exits.
- **t0+3 s:** Docker starts a new JVM.
- **t0+10 s poll:** there are two outcomes, and both are correct.
  - REST is not up yet (Connect startup with plugin scanning usually takes longer than 10 s). The poll is "unreachable", and a later poll decides.
  - REST is up. The GET returns 404, so a restart is detected on the first successful poll after the gap.

There is no dependency on catching the outage. The marker is a latch that only DBX can set and only a process exit can clear.

### 1.4 False positives and edge cases

- **Rebalance, connector create/delete, task restart via REST, session expiry, broker blip, long GC pause:** the process survives, the map survives, and there is no signal. (Contrast with §2.)
- **Operator or another tool sets levels on `root` or on `dbx`:** `setLevel` iterates over all matching loggers, so our logger gets a new level and a non-null `last_modified`. That is not a restart under the rule. DBX should re-plant, so the level stays `ERROR`, and update `planted_at`.
- **A Log4j config that happens to define `dbx.incarnation`:** after a restart it returns 200 with `null`, which is still detected.
- **DBX restarts while Connect keeps running:** the marker is intact, so there is no false alarm. The baseline needs no reconciliation.
- **Multiple workers (not v1):** the REST call reaches one worker only, so DBX would have to plant and check per worker URL.
- **Planned `docker restart` by an operator:** detected as a restart. ADR-0032 treats every restart the same way, which is intended.

## 2. Fallback: Connect group member id

### 2.1 Mechanism (source)

- Client id: `clientId = clientIdConfig.isEmpty() ? "connect-" + workerId : clientIdConfig` (3.9.0 `DistributedHerder.java` ~L265–270). `workerId` is the advertised `host:port`, so the client id is stable across restarts.
- Member id: the broker assigns `clientId + "-" + UUID.randomUUID()` to a dynamic member (3.9.0 `core/.../coordinator/group/GroupMetadata.scala` `generateMemberId`, ~L412–419). A new process joins with an empty member id, so it always gets a new UUID.
- No static membership: `GroupRebalanceConfig` sets `groupInstanceId = Optional.empty()` for every protocol type other than CONSUMER ("Static membership is only introduced in consumer API"). `DistributedConfig` defines no `group.instance.id`.
- The same process also gets a new id: on `UNKNOWN_MEMBER_ID`, `AbstractCoordinator.resetStateAndRejoin` resets the member id to empty. The next JoinGroup yields "a brand new member id" (3.9.0 ~L954–963, ~L1082). This happens after the broker expires the session: `session.timeout.ms` default **10 s**, heartbeat 3 s, `rebalance.timeout.ms` 60 s (`DistributedConfig` ~L100–110).
- Graceful shutdown sends LeaveGroup, so the old member disappears at once. On a crash the old member lingers until its 10 s session expires, so a `describe` in that window can show the dead member and the new one side by side.
- Reading it: `Admin.describeConsumerGroups` **fails** for Connect groups in 3.9. `DescribeConsumerGroupsHandler.handledClassicGroupResponse` puts `IllegalArgumentException("GroupId %s is not a consumer group (%s).")` for any `protocolType` other than `consumer` or empty (~L246–285). Use `Admin.describeClassicGroups` (KIP-1043, **clients ≥ 4.0**, works against a 3.9 broker). It accepts any protocol type and returns `memberId`, `clientId`, `clientHost` per member, with no assignment for non-consumer groups (4.0.0 `DescribeClassicGroupsHandler` ~L118–133). Neither API exposes the generation id.

### 2.2 Rule

Store the set of member ids whose `clientId == "connect-<advertised host:port>"`. Any id not in the stored set means the worker rejoined as a new member. That is **either** a process restart **or** a session expiry inside the same process. Uses:

- With the marker intact, a changed member id means a membership loss without a restart. Record it as an incident; it is not the ADR-0032 trigger.
- If the logger API is unavailable (AK < 3.7 with a pre-planted logger lost, or `/admin` blocked by a REST extension), fall back to the member id and accept the over-reporting. It is rare on a single host: it needs more than 10 s without heartbeats, and the heartbeat runs on its own thread.
- Between-polls behaviour: the same as the marker. The UUID differs even if DBX never saw the gap. The exception is when the poll lands while the new member has not joined yet; then the group shows only the dead member, or is Empty, and a later poll decides.

## 3. Ruled out

| Candidate | Finding | Verdict |
|---|---|---|
| Status topic `worker_id` | `workerId` = advertised host:port, identical after restart | Useless alone |
| Status topic records on shutdown/startup | Graceful stop writes `UNASSIGNED` via `putSafe`; start writes `RUNNING` via `put`, both with `generation()` (3.9.0 `AbstractHerder.java` ~L186–215). **A crash (OOM exit) writes nothing.** The new process writes `RUNNING` again, which is indistinguishable from a rebalance or a REST task restart | Ambiguous |
| Status topic `generation` | This is the Connect group generation from the last rebalance (`DistributedHerder.generation()` ~L418). It increases on every rebalance, and connector create/delete triggers one, so a bump does not imply a restart. It can also reset if the broker deletes the Empty group's metadata (not verified). Stale-write guards compare it (`KafkaStatusBackingStore` ~L330–340, ~L413–416) | Ruled out |
| REST `GET /` | `ServerInfo` has only `version`, `commit`, `kafka_cluster_id`, all static | Ruled out |
| REST `/connectors?expand=status`, task status | `state`, `worker_id`, `trace`; no start time or incarnation field | Ruled out |
| JMX (`java.lang:type=Runtime` `StartTime`) | Would be exact, but Confluent images need `KAFKA_JMX_PORT`/`KAFKA_JMX_HOSTNAME` for remote JMX. Neither is set, and DBX has no JMX client | Ruled out (would need a Compose change + a JMX client) |
| Session key in config topic | The leader rotates it only when it is absent, expired (default 1 h), or its algorithm changed (`DistributedHerder.checkForKeyRotation` ~L577–605). A restarted worker reads the existing key back | Ruled out |
| Producer ids on internal topics (`Admin.describeProducers`) | Not investigated to the source level; depends on whether the internal producers are idempotent. Not needed given §1 | Not pursued |

## 4. Open items

Each needs a lab check on the mac (via `rexec`), not on this server. None blocks the decision.

1. On `cp-kafka-connect:7.9.0`: plant the marker, `docker kill` the container, let Compose restart it, then `GET /admin/loggers/dbx.incarnation`. Expect **404**. Also confirm that the PUT returns 200 on this image, since no REST extension blocks `/admin`.
2. Confirm that `PUT /admin/loggers/root` rewrites our logger's `last_modified` to a non-null value, which the rule treats as not a restart.
3. Confirm that `describeClassicGroups` from DBX's Admin client returns the Connect member with `clientId = connect-<host:port>`, and that its UUID changes after `docker kill`.

## 5. Sources

- Apache Kafka `3.9.0` tag, `connect/runtime/src/main/java/org/apache/kafka/connect/runtime/`:
  - `Loggers.java`
  - `rest/resources/LoggingResource.java`
  - `rest/entities/ServerInfo.java`
  - `AbstractHerder.java`
  - `distributed/DistributedHerder.java`
  - `distributed/DistributedConfig.java`
  - `distributed/WorkerGroupMember.java`
  - `storage/KafkaStatusBackingStore.java`
  — https://github.com/apache/kafka/tree/3.9.0/connect/runtime/src/main/java/org/apache/kafka/connect
- Kafka `3.9.0`, clients:
  - `clients/src/main/java/org/apache/kafka/clients/GroupRebalanceConfig.java`
  - `clients/.../consumer/internals/AbstractCoordinator.java`
  - `clients/.../admin/internals/DescribeConsumerGroupsHandler.java`
- Kafka `3.9.0`, broker: `core/src/main/scala/kafka/coordinator/group/GroupMetadata.scala`
- Kafka `4.0.0`:
  - `connect/runtime/.../Loggers.java` (log4j2)
  - `clients/.../admin/internals/DescribeClassicGroupsHandler.java`
- [KIP-976: Cluster-wide dynamic log adjustment for Kafka Connect](https://cwiki.apache.org/confluence/display/KAFKA/KIP-976%3A+Cluster-wide+dynamic+log+adjustment+for+Kafka+Connect)
- [KIP-1043: Administration of groups](https://cwiki.apache.org/confluence/display/KAFKA/KIP-1043:+Administration+of+groups); [KAFKA-17896 Admin.describeClassicGroups](https://issues.apache.org/jira/browse/KAFKA-17896) (4.0.0)
- [Confluent Platform 7.9 Docker monitoring (JMX env vars)](https://docs.confluent.io/platform/7.9/installation/docker/operations/monitoring.html)
