# IBM Carbon as the frontend design system, with a Chinese typography layer

DBX v1 builds its operator interface on IBM Carbon (`@carbon/react` 1.x, v11). This supersedes the frontend premise recorded in the initiative map, which pinned Ant Design 5. The remaining stack from that premise — React 18, TypeScript, Vite, TanStack Query — is unchanged.

## Why Carbon

DBX is a judgement-driven console: almost every colour on screen carries a conclusion such as `SUPPORTED`, `UNSUPPORTED`, `INCONCLUSIVE`, `PASS`, or `FAIL`. Carbon is the only candidate whose primitives already speak that language.

- `IconIndicator` ships the kinds `failed`, `succeeded`, `in-progress`, `not-started`, `pending`, `unknown`, `caution-major`, `caution-minor`, and `undefined`. `unknown` and `undefined` give `INCONCLUSIVE` a first-class visual form instead of forcing it into a warning.
- The status indicator pattern requires at least three of symbol, shape, colour, and text to be present. A conclusion therefore cannot be carried by colour alone, which is exactly the constraint the domain demands.
- The four themes and inline theming are official, and pairing a light configuration surface with a dark monitoring surface is the documented use of that mechanism rather than a workaround.
- IBM Plex has a Simplified Chinese family under OFL-1.1, so the brand typography survives translation. Most alternatives fall back to system fonts for Chinese text.

Ant Design 5 would have been cheaper on Chinese typography and on tables. It was rejected because it makes DBX visually indistinguishable from every other Chinese admin console, which is a commercial cost the project is not willing to pay.

## Status indicators are not tags

Carbon defines `Tag` for categorisation and explicitly not for status. Per-row conclusions use `IconIndicator` or `ShapeIndicator`; `Tag` is reserved for dimensions such as environment or database kind. This keeps a conclusion visually distinct from a label.

## Deliberate deviation: full page, not wide tearsheet

Carbon recommends a wide tearsheet for multi-step creation and attaches a notice discouraging the full-page form. DBX uses full page anyway. Stage two is a selector over a production schema, and stage three is a three-pane per-table workspace holding an object tree, source and target DDL side by side, and a findings list. A tearsheet cannot hold either. Carbon permits full page when the creation must be completed before the service can be used at all, and the migration wizard is not an ancillary create action inside DBX — it is the product. This deviation is recorded so a later reader does not "correct" it.

## Chinese typography layer

`@carbon/type` carries no Simplified Chinese family and Carbon publishes no CJK typography guidance, so DBX owns this layer:

- letter-spacing is zeroed; Carbon's 0.16px and 0.32px are tuned for small Latin text and visibly loosen Chinese words;
- `label-01`, `helper-text-01`, and `caption-01` move from 12px to 13px, not 14px, which would collide with `body-compact-01`;
- `body-compact-01` line height moves from 1.28572 to 1.45;
- consequently the smallest usable table row height in Chinese is `sm` (32px). `xs` (24px) is treated as unavailable, and table information density is planned against 32px;
- the family stack places Latin first — `'IBM Plex Sans', 'IBM Plex Sans SC', -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif` — so identifiers and numbers keep Plex letterforms;
- only Light, Regular, and SemiBold are loaded, self-hosted from the official split subsets. Google Fonts does not serve IBM Plex Sans SC.

## Theme partition

The product uses g10. Only the live blocks inside run monitoring — the progress matrix, the event stream, and logs — are wrapped in an inline g100 theme. A fully dark monitoring page was rejected because it would also darken that page's form controls and break continuity with the preceding stages.

## Consequences

Chinese typography, table density, and any syntax highlighting palette become DBX-owned concerns that must be re-tested whenever Carbon is upgraded. The token override layer is the single place where that risk is concentrated.

## Rejected alternatives

- **Keep Ant Design 5** — lower effort for Chinese text and dense tables, rejected for visual homogeneity and for lacking Carbon's status and theme semantics.
- **Mix Carbon tokens with Ant Design tables** — two sets of tokens, themes, radii, and spacing scales in permanent conflict; the most expensive option.
- **Follow Carbon and use a wide tearsheet** — rejected because the per-table workspace and the production-scale selector do not fit.
