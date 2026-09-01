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
| `src/shell/`                          | The persistent product shell: 迁移任务 / 数据源 / 系统设置.                                 |
| `src/pages/`                          | One module per route.                                                                       |
| `e2e/`                                | Playwright, seam 1.                                                                         |

## Decisions a reader is likely to want to "correct"

Three of them are deliberate and recorded, with reasons:

- the wizard renders **full page**, not a wide tearsheet (ADR-0014);
- business code goes through **`DbxTable`** rather than the table substrate (ADR-0015);
- there is **no OpenAPI document** in this phase (ADR-0016).

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
