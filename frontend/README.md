# DBX frontend

The DBX operator console: React 18 + TypeScript + Vite, IBM Carbon v11, data mocked at the
HTTP boundary. Specification: issue #30. This directory is product code — the one-off
prototype in `../prototype/migration-wizard/` is retained evidence and is never modified.

## Running things

**All installs, builds and test runs go to the mac through `rexec`** (see the repository
root `CLAUDE.md`). Do not run them on the development server.

```bash
npm ci
npm run typecheck
npm run lint
npm run test        # Vitest — seam 2
npm run test:e2e    # Playwright — seam 1
npm run verify      # all of the above
```

## Layout

| Path                                  | What lives there                                                                            |
| ------------------------------------- | ------------------------------------------------------------------------------------------- |
| `src/messages/`                       | Every user-visible string. Components never hard-code copy.                                 |
| `src/styles/_chinese-typography.scss` | The ADR-0014 token override layer — the single place DBX departs from `@carbon/type`.       |
| `src/styles/fonts.ts`                 | Self-hosted IBM Plex, Light/Regular/SemiBold only, official split subsets. No Google Fonts. |
| `src/routes/paths.ts`                 | Route patterns and URL builders. Drawers get URLs too.                                      |
| `src/contract/`                       | The hand-written frontend/backend contract (ADR-0016). No OpenAPI document, deliberately.   |
| `src/api/`                            | The HTTP edge and the TanStack Query hooks over it.                                         |
| `src/mocks/`                          | MSW handlers, the stateful store, the controllable clock, and the URL scenario registry.    |
| `src/features/`                       | Per-area product code, one directory per surface.                                           |
| `src/shell/`                          | The persistent product shell: 迁移任务 / 数据源 / 系统设置.                                 |
| `src/pages/`                          | One module per route.                                                                       |
| `e2e/`                                | Playwright, seam 1.                                                                         |

## Mocks, scenarios and the clock

All data comes from MSW over a stateful in-memory store (ADR-0016); the worker starts in
every build, including a preview of the production bundle, because in this phase every DBX
request is mocked and an unhandled one is a bug rather than something to pass through.

Any route takes a scenario and a clock rate in its query string:

```
/connections?scenario=empty      # nothing registered yet
/connections?scenario=loading    # every read hangs
/connections?scenario=error      # every read fails
/runs/run-1?scenario=stuck-table&clockRate=600
```

`src/mocks/scenarios.ts` is the registry and the list of ids. Mock time is accelerated by
default so a three-hour migration replays in seconds; `clockRate=0` freezes it for a
screenshot. Only the default scenario persists a 迁移草稿 to `localStorage` — every
URL-selected scenario is memory backed, so one review or test run cannot poison the next.

`public/mockServiceWorker.js` is generated, not written by hand, and is checked in because
MSW needs it at a stable URL:

```bash
npx msw init public/ --save
```

`msw` is pinned to an exact version for two reasons: the generated worker carries the
package version and warns at runtime if the two drift, and from 2.13 onwards MSW pulls in a
`type-fest` major that wants a newer TypeScript than this project pins.

## Decisions a reader is likely to want to "correct"

Three of them are deliberate and recorded, with reasons:

- the wizard renders **full page**, not a wide tearsheet (ADR-0014);
- business code goes through **`DbxTable`** rather than the table substrate (ADR-0015);
- there is **no OpenAPI document** in this phase (ADR-0016). The types in `src/contract/`
  are hand-written and nothing generates them; if a backend later adopts OpenAPI, they are
  its input rather than a competing definition.

A second, smaller one: 数据源 renders its 数据库连接 as cards rather than as a table. The
`DbxTable` boundary exists for the surfaces ADR-0015 is about — the 1200-row selector and
the monitoring views — and a handful of endpoints with two actions each is not one of them.

One further, smaller deviation: `/design/density` renders a hand-written `<table>` rather
than `DbxTable` (ADR-0015). It is a design reference surface whose only question is
typography, and `DbxTable` does not exist until the next batch. No product view may follow
its example.

## Where the Chinese typography layer lives

`src/styles/_chinese-typography.scss` holds the values; `src/styles/index.scss` feeds them
into `@use '@carbon/react/scss/type' with (...)`. Overriding the tokens rather than
patching selected Carbon classes is deliberate — it is what makes every Carbon component
inherit the change instead of only the handful DBX renders today. The family stack is the
one exception and is applied as CSS, for the reason recorded in `src/styles/_typography.scss`.

## Chinese copy is domain language

User-visible Chinese comes from the `_中文_` lines in the repository's `CONTEXT.md`. If a
word is missing, add it there first. The navigation reads 「迁移任务」 and never 「作业」 —
`Migration task` lists Job under `_Avoid_`.
