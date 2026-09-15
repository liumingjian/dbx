---
status: accepted (amends the execution-platform clause of ADR-0021 and the stuck clause of ADR-0001; makes ADR-0027's Compose self-healing concrete)
---

# Out of memory crashes the worker; a Connect restart fails its running boxes

In the lab, one `OutOfMemoryError` killed the Connect worker's offsets-topic reader thread (`KafkaBasedLog Work Thread - dbx-connect-offsets`). The REST API and connector states stayed healthy. Every Source created afterwards blocked forever in `JdbcSourceTask.start` → `OffsetStorageReaderImpl.offsets`, and only a worker restart recovered it ([#63](https://github.com/liumingjian/dbx/issues/63), [#77](https://github.com/liumingjian/dbx/issues/77)).

DBX holds no host control (ADR-0027), and Compose restarts a container only when its process **exits**. An `unhealthy` healthcheck restarts nothing. So we decided that a JVM which runs out of memory crashes honestly, and Docker restarts it. A deeper probe could only raise an alarm.

- **Every shipped JVM exits on OOM.** Connect, Kafka, Schema Registry, and DBX each run with `-XX:+ExitOnOutOfMemoryError`, under Compose `restart: unless-stopped`. A kernel OOM kill takes the same path. The Connect healthcheck stays a REST probe.
- **A Connect restart fails its running boxes at once.** After a restart, Sources re-read everything since their last offset commit, and Sinks (`insert`, `pk.mode=none`) redeliver. So continuity is already disproved (ADR-0006).
  - Every box with a connector on the restarted worker fails immediately with the structured diagnosis *execution platform restarted mid-transfer* (迁移平台在传输中重启) in the 迁移平台 domain.
  - Its unfinished members become 因关联失败而阻塞 (ADR-0004). There's no automatic retry; they stay candidates for 重新迁移.
  - This narrows ADR-0021: its ten-minute grace covers an **unreachable** platform only. It ends as soon as DBX sees that Connect restarted.
  - Detecting the restart can't depend on DBX catching the unreachable gap, because a restart may be shorter than one poll. ADR-0001's beyond-baseline checks remain the backstop.
  - Kafka, Schema Registry, and DBX restarts keep their existing rules: ADR-0021's unreachable-platform clause and ADR-0006's reconciliation, respectively.
- **The second restart stops the run's admission.** The first Connect restart in a run turns the runtime condition (运行状况) to 需留意, with the reason *迁移平台发生过重启*, and admission continues. A second restart in the same run turns it to 受阻 and pauses admission. Admission resumes only when the DBA chooses to continue; the alternative is stopping the run. The condition-change record and the diagnostic package carry each restart's time and its OOM evidence.
- **Wedges not caused by OOM.** ADR-0001's 卡死 remains the backstop. It gains one rule: when two consecutive boxes of a run reach 卡死 without producing a single record, DBX pauses admission and the condition turns 受阻 with the reason *迁移平台无法启动新的读取*. This stops a wedged worker from costing ten minutes per admitted box.

## Considered options

- **Detect only.** DBX probes the offsets reader, turns the condition 受阻, and asks a server administrator to restart Connect. Rejected: it hands the DBA a host-level chore that the platform can absorb.
- **A healthcheck that exercises the offsets reader (canary Source).** Rejected: `unhealthy` triggers nothing under Compose, so it would only duplicate the stuck backstop at higher cost.
- **Let ADR-0006's duplicate checks fail the boxes later.** Rejected: failure arrives only after the table is re-read, which burns the write freeze. The diagnosis would also point at duplicate data instead of the crash.
- **Pause admission on the first restart.** Rejected: one OOM may be incidental. The second one proves it's systematic.

## Consequences

- One OOM costs every box running on the worker, not just the task that hit it. Bounding the heap so this stays rare is ADR-0031's job ([#75](https://github.com/liumingjian/dbx/issues/75)).
- The diagnosis catalog (ADR-0005) gains two codes: *execution platform restarted mid-transfer*, and *execution platform cannot start new reads*.
- ADR-0027 can't observe JVM flags (E4 excludes worker/JVM settings). If an operator removes `-XX:+ExitOnOutOfMemoryError`, the wedge returns silently, and only the stuck backstop catches it.
- The lab Compose (`local-env/docker-compose.yml`) has neither `restart:` nor the flag, and needs both before its evidence reflects the shipped behaviour.
