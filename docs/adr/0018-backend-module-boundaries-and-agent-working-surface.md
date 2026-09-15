# Backend module boundaries and the agent working surface

> **Status: module table and pure-core list superseded by [ADR-0036](0036-module-table-owns-every-v1-obligation.md)** (#83). Read ADR-0036 for the current modules; enforcement, module context, the session rule, and abstractions not drawn below still hold.

DBX is built entirely by agents working in 100K-token sessions. Each session should face one deep module through its narrow interface plus the interfaces it consumes, never the whole repository. The backend is therefore split into eleven modules with one-way dependencies, a pure core, and boundaries that tests enforce. A convention an agent can quietly break is no boundary at all.

## Enforcement

The backend stays one Gradle module. Each module is one top-level package; only its `<module>.api` package may be referenced from outside. ArchUnit tests fail the build on:

- a reference into another module's non-`api` package;
- any dependency cycle between modules;
- a pure module (see below) depending on `JdbcTemplate`, an HTTP client, or a clock;
- a caller other than `orchestration` referencing `workflow.api.command`.

When an ArchUnit rule goes red, fix the code. Changing a rule is an architectural decision recorded in an ADR, never a way to make a change pass.

## Modules

| Package | Owns | Interface |
|---|---|---|
| `dialect` | Source dialect, target dialect, directed database pair (ADR-0008), including the pair-owned `TypeMapper` and PostgreSQL-specific Sink settings, default whitelist, and identity rules | Returns facts, mapping decisions, and immutable typed SQL plans; never opens connections |
| `preflight` | Large-record, row-count, byte-estimate, and Kafka probes (ADR-0003) | Plans and evaluates probes → evidence |
| `contract` | Table write contract (ADR-0011) | `assemble`, `renderDdl`, `prove` |
| `scheduling` | Box plan and admission (ADR-0002) | `plan`, `admit` |
| `connector` | Connect REST, connector lifecycle, completion judgement (ADR-0001, ADR-0009, ADR-0010) | Box start/stop, `judge` |
| `validation` | Data validation | `plan(approved contract, pair capabilities)`, `evaluate(plan, facts)` |
| `diagnosis` | Error translation (ADR-0005) | `diagnose` |
| `gateway` | Core database gateway (ADR-0008): frozen connections, transactions, timeouts, result-schema checks | Executes typed SQL plans |
| `workflow` | State machine, single-writer command queue, repositories (ADR-0004, ADR-0012) | `workflow.api.command`, `workflow.api.query` |
| `orchestration` | Driving runs and recovery (ADR-0006) | Use cases called by `web` |
| `web` | HTTP adapters | — |

Type mapping lives in `dialect`, not in its own module, because ADR-0008 makes it pair-owned. `gateway` and `preflight` fill gaps in the original candidate list; the ADRs already assumed both.

The validation plan is frozen at contract approval, together with the contract, in the run version (ADR-0006).

## Dependency direction

Dependencies point downward: `web → orchestration → deep modules → dialect`. `orchestration` also calls `gateway`.

- Deep modules return results. They do not write workflow state or call each other's side effects.
- `orchestration` is the sole caller of `workflow.api.command`. Recovery is an `orchestration` use case: it rereads facts through `connector` and `contract`, and `workflow` never calls back into them. This removes the one cycle the ADRs implied.
- `web` reads progress, timelines, and table migration units directly through `workflow.api.query`, and writes only through `orchestration`.

## Pure core, effectful shell

`dialect`, `contract`, `scheduling`, `diagnosis`, `connector.judge`, and the evaluation half of `validation` are pure. Side effects are confined to `gateway`, the Connect REST client in `connector`, and the `workflow` repositories. This generalizes the locked rule that type mapping and DDL generation are pure functions, so most of the platform is verifiable in seconds without containers.

## Module context

Each module carries its own context so an agent can change it after reading only that module:

- A contract test (`*ContractTest`) is the primary documentation of the module's `api`.
- `README.md` is navigation only and at most 40 lines: one-line responsibility, `api` entry points, contract test location, modules depended on, and relevant ADRs. A test enforces the limit. `orchestration`'s README lists every `api` entry point it consumes.
- Terms are defined once, in root `CONTEXT.md`; READMEs link to them. Rationale lives once, in ADRs. Module names are implementation and stay out of `CONTEXT.md`, except that a module named after a domain term uses that term.
- Root `CONTEXT.md` is capped at about 20K characters.

## Session rule

One session changes one module. A cross-module change is split: first change the depended-on module's `api` and its contract test, then update each consumer in its own session. `orchestration` naturally spans modules but sees only their `api`.

## Abstractions deliberately not drawn in v1

Premature abstraction hurts agents as much as missing boundaries: it makes one change span five files. v1 has:

- no abstraction for replacing Kafka or Confluent Connect; the Connect REST client is concrete;
- no repository interface layer over Spring JDBC; each module owns concrete DAOs (consistent with ADR-0012);
- no runtime-loaded dialect plugins; the ADR-0008 seam is composed at compile time;
- no in-process event bus; modules call each other's `api` synchronously, so every caller stays traceable;
- no single-table sharding interface, only a reserved place for it.

## Considered options

- **Gradle subprojects** give compile-time enforcement but overturn the locked "single module first" premise; the cost pays off only when a module ships independently.
- **Spring Modulith `verify()`** enforces by implicit convention; an agent cannot read why a change was rejected. Explicit ArchUnit rules are code the agent can read.
- **Convention only** does not hold under agent development.
- **A query path through `orchestration`** would turn it into a forwarding layer for every screen.
