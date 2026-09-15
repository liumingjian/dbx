# Ant Design 5 with a Carbon-derived skin, DBX-owned conclusion indicators, and a Chinese typography layer

> Per [#89](https://github.com/liumingjian/dbx/issues/89): v1 ships zh-CN only and hides the language switch; the two-locale rules below take effect in v2.

Supersedes ADR-0014. Decided in #47, following #45's adoption of `dbx-prototype` as the frontend baseline. ADR-0029 extends the conclusion set and palette below with preflight finding impact and an accepted-risk indicator.

DBX v1 builds its operator interface on Ant Design 5, skinned with DBX's own Carbon-derived token layer. The substrate comes with the prototype. What ADR-0014 bought from Carbon — conclusions that never rest on colour alone, and a Chinese typography layer — DBX now owns itself. The rest of this ADR records how.

## Tokens have one source: the code

`src/theme/antd.ts` (the `ConfigProvider` theme) and `src/theme/tokens.css` (CSS variables) are the single source of token values. The prototype's `DESIGN.md` (Google Stitch format) is adopted as the design-intent document: its prose — rationale, component rules, do's and don'ts — stays, and every value table and the YAML token front matter are replaced by pointers into those two files.

Reason: `DESIGN.md` restated the values by hand with nothing keeping them in step, and had already drifted from the code in at least seven places (warning colour, card padding, menu row height, hover surface, base line height, status variable names, and spacing/type variables that do not exist). A value lives in exactly one place.

## Conclusion indicators

Almost every colour on screen carries a conclusion (`SUPPORTED`, `UNSUPPORTED`, `INCONCLUSIVE`, `PASS`, `FAIL`, …). Ant Design has no status-indicator vocabulary, so DBX supplies one.

- **One mapping site.** `conclusions/conclusion.ts` is the only module that knows which indicator carries which conclusion. It is ported from the current frontend with its compile-time coverage types and its notice rule, extended by ADR-0029 from two kinds (`error` = 无法迁移, `info` = 无法确认是否可迁移) to the three finding impacts and the accepted-risk indicator. Every view that colours something by conclusion goes through it: progress-bar fills, the verdict banner, and notification or dashboard severity included. A second conditional anywhere else is how `INCONCLUSIVE` comes to render as a caution in one screen and a failure in another.
- **Three channels, always.** `ConclusionIndicator` renders every conclusion as a distinct icon shape, a colour, and a text label. The label is derived from the conclusion inside the component, so callers cannot drop or override it. Icons come from `@carbon/icons-react`, whose semantic icons map one-to-one onto the kinds `conclusion.ts` already declares (`unknown`, `undefined`, `caution-major`, …).
- **`INCONCLUSIVE` (无法判定) is neutral.** It takes an information or neutral colour with a question-mark shape, distinct from `NOT_APPLICABLE`'s hollow or dash shape. The yellow and orange palette belongs to caution conclusions only, because 「无法判定」 must never read as 「有点风险但可以过」.
- **Enforcement.** A lint rule restricts views from expressing a conclusion through antd `Tag color` or `Badge status`. `Tag` stays for categorising dimensions such as database kind. A unit test asserts icon, colour, and label for every member of `DbxConclusion`.

The prototype's `StatusTag` maps the validation state `error` to the warning tone, and it has at least eight other sites choosing a colour from status on their own. Both are the anti-pattern this section exists to remove.

## Chinese typography layer

Neither Ant Design's defaults nor the prototype carry Chinese typography rules. DBX owns this layer:

- under `:lang(zh)`, letter-spacing is zeroed; the prototype's 0.16px and 0.32px Latin tracking visibly loosens Chinese words;
- under `:lang(zh)`, 12px text (labels, captions, status text, form labels, statistic titles) moves to 13px, and compact body line height moves to 1.45;
- the density floor is 32px **in both locales**: `controlHeightSM` is 32, so `size="small"` never produces a 24px control. One layout serves zh-CN and en-US, which keeps `scripts/overlap.mjs` testing the layout that ships.

## Fonts

Latin text and numerals use IBM Plex Sans and Plex Mono, bundled through `@fontsource`. Chinese text falls back to system fonts: `'IBM Plex Sans', -apple-system, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans SC', sans-serif`. The browser runs on the operator's desktop, which ships a CJK system font. IBM Plex Sans SC is not bundled: it would cost several MB for brand letterforms, and that argument belonged to the Carbon choice.

Customers run on isolated intranets (内网), so the build output references no external font or asset URL.

## Theme

The light and dark themes are whole-application, chosen by the operator. ADR-0014's partition (a light configuration surface with inline-dark monitoring blocks) is dropped. It was Carbon's documented use of inline theming rather than a domain need, and a forced dark block inside a dark theme means nothing.

## Lapsed with ADR-0014

ADR-0014's full-page-over-tearsheet deviation was a departure from Carbon guidance and lapses with it. Page structure now belongs to the information architecture rewrite (#48).

## Rejected alternatives

- **Cite `DESIGN.md` as the token source** — ratifies drift already present.
- **Generate `DESIGN.md` front matter from code** — worth it only if a Stitch-style generator keeps consuming the file.
- **Locale-specific density (24px allowed in en-US)** — two layouts, and the overlap gate would test only one per locale.
- **Keep the light/dark partition via nested `ConfigProvider`** — a Carbon-era rationale with no remaining domain reason.
- **Self-host IBM Plex Sans SC** — several MB of fonts for letterforms the new substrate no longer argues for.
