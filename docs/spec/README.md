# DBX v1 spec — index

The v1 spec compiles ADR-0001–0039 and `CONTEXT.md` into one sub-spec per module, so an implementing agent faces one module per session. Compiled in [#82](https://github.com/liumingjian/dbx/issues/82).

## How to use it

1. Read this index, then only the sub-spec of the module you are changing, then the ADRs its **Read first** line names. Root `CONTEXT.md` defines every term.
2. One session implements one slice from one sub-spec's **Slices** section. Its blocking slices must be merged first.
3. A slice is done when the obligations it covers are proved by the tests its **Verification** section names, at the ladder rung it names (ADR-0022), run on the mac through `rexec`.

**Precedence.** ADRs hold every rationale and win over the spec: a sub-spec that disagrees with an ADR is a spec bug, fixed in the sub-spec. `docs/technical-plan.md` is background reading, not a source of obligations. A sub-spec's **Conflicts resolved** section records which text lost where the corpus disagreed.

**Implementation tickets** are generated one per slice, with blocking edges copied from the Slices sections. Nothing here is a ticket yet.

## v1 in one paragraph

Offline, one-time, full migration of one MySQL 8.0 database into one PostgreSQL 15 schema, for a DBA who is not a developer. Kafka, Connect and Schema Registry are bundled, single-node, and invisible to the user (ADR-0009). DBX creates every target table from a table write contract the DBA approves (ADR-0011), schedules tables into boxes under resource gates (ADR-0002, ADR-0031), judges completion itself and validates every table before calling it complete (ADR-0001, ADR-0004). It ships as one offline package for macOS on Apple Silicon (ADR-0035), single-user, with a zh-CN UI only (#89 item 6). Out of scope: the map's [Out of scope](https://github.com/liumingjian/dbx/issues/1) section.

## Sub-specs

Module ownership is ADR-0036; boundaries, the session rule and ArchUnit enforcement are ADR-0018.

| Sub-spec | Owns | Kind |
|---|---|---|
| [dialect](dialect.md) | Source and target dialects, the directed database pair, type mapping, typed SQL plans | pure |
| [preflight](preflight.md) | Large-record and byte-estimate probes → evidence | plans + evaluation |
| [contract](contract.md) | Table write contract, read-only DDL rendering, structural proof, abandonment's projected list | pure |
| [scheduling](scheduling.md) | Box plan, admission gates, duration estimate and remaining time | pure |
| [connector](connector.md) | Connect REST, Kafka AdminClient, Schema Registry, lifecycle, restart marker, `judge` | effectful shell + pure `judge` |
| [validation](validation.md) | Validation plans and evaluation, source baseline and drift, sampling | pure |
| [diagnosis](diagnosis.md) | Error translation, diagnostic package content | pure |
| [gateway](gateway.md) | Frozen connections; executes typed SQL plans | effectful shell |
| [connection](connection.md) | Credential encryption, DEKs, master key, TLS material | effectful (key file) |
| [environment](environment.md) | Environment check E0–E8, memory tier | effectful (host probes) |
| [condition](condition.md) | Runtime condition fold | pure |
| [workflow](workflow.md) | State machine, single-writer queue, H2 aggregates, backups | effectful shell |
| [orchestration](orchestration.md) | Use cases: runs, recovery, cleanup, abandonment, admission pause, package assembly | integration |
| [web](web.md) | HTTP adapters, polling endpoints, status channel | adapter |
| [frontend](frontend.md) | The `dbx-prototype`-based UI, contract port, Mock scenarios | UI |
| [release](release.md) | Offline package, `dbx install/upgrade/rollback`, Compose, `release.json`, L4 | non-Java |

Dependencies point downward: `web → orchestration → deep modules → dialect`; only `orchestration` writes workflow state, and no deep module calls another's side effects (ADR-0036 §Dependencies and purity).

## Where to start

The cross-module slice graph is acyclic. Every blocking edge points to an earlier module in this order, which is also a safe build order:

`dialect` → `connection` → `condition` → `scheduling` → `gateway` → `preflight` → `contract` → `connector` → `validation` → `diagnosis` → `environment` → `workflow` → `orchestration` → `frontend` → `web` → `release`

- **Takeable at once**: slice 1 of every module whose slice 1 has no cross-module blocker (`dialect`, `connection`, `condition`, `scheduling`, `workflow`, `web`, `release`), and every `frontend` slice in order. `frontend` is mock-backed throughout and blocks `web` slices 2–6 through its slice 3 (the contract port).
- **Critical path**: `dialect` slices unblock most of the backend. `orchestration` slices point at the providers' real slices, for example its run driver at `connector` slice 4 (the Connect REST client).
- Each sub-spec's Slices section holds the exact edges; a slice also waits on any `D-n` item it names (below).

## Decisions still open

Compiling settled every obligation but 29 items, `D-1`–`D-29`, of which `D-1`–`D-13` are now resolved (16 open). Each is listed under **Open items** in the sub-specs it affects and blocks only the slices it names there. They are grouped into eight decision tickets on map #1:

| Items | Ticket |
|---|---|
| ~~D-1–D-4~~ **resolved** | [决策：预检发现的判定归属与 Source 投影](https://github.com/liumingjian/dbx/issues/92) |
| ~~D-5–D-9~~ **resolved** | [决策：调度器的输入与预估稳定性](https://github.com/liumingjian/dbx/issues/93) |
| ~~D-10–D-13~~ **resolved** | [决策：校验检查的定义与漂移记录](https://github.com/liumingjian/dbx/issues/94) |
| D-14–D-18 | [决策：运行状况的取值、磁盘停机之后的走向与状态通道的数据来源](https://github.com/liumingjian/dbx/issues/95) |
| D-19–D-21 | [决策：诊断目录的补充](https://github.com/liumingjian/dbx/issues/96) |
| D-22–D-24 | [决策：备份密钥、恢复与回退路径](https://github.com/liumingjian/dbx/issues/97) |
| D-25–D-26 | [决策：安装时的内存档位](https://github.com/liumingjian/dbx/issues/98) |
| D-27–D-29 | [决策：控制台的放置与措辞](https://github.com/liumingjian/dbx/issues/99) |

A resolved ticket moves its rulings into the ADRs and the affected sub-specs' Obligations, and deletes its `D-n` items. Items marked **Implementer decides** in a sub-spec are within-module choices bounded by the constraint stated there; they need no ticket.

## Provenance

- [`subspec-brief.md`](subspec-brief.md): the rules every sub-spec follows.
- [`corpus-audit.md`](corpus-audit.md): the pre-compile audit of the corpus, superseded by ADR-0036–0039 and the doc sync of #91; kept for its ownership matrix.
