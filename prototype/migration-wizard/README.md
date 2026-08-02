# DBX migration wizard prototype

> Throwaway UI prototype for GitHub issue #21. This is not production code: all mutations are in-memory only and no database or credential is contacted.

## Run

From the repository root:

```bash
python3 -m http.server 4173 --directory prototype/migration-wizard
```

Open <http://localhost:4173/?variant=A>. The user lands on A; the bottom switcher preserves `?variant=A|B|C` for provenance and comparison.

## What changed after user feedback

Variant A remains the primary route, but it now uses a DBX shell rather than a generic card flow: a persistent dark navy sidebar (作业中心 / 数据源 / 系统设置), compact white top bar, pale workspace, bordered creation canvas, and a six-stage horizontal-in-canvas mental model expressed through clickable numbered navigation. The density and split-pane information architecture intentionally follow the X2Doris reference article and screenshots at <https://cloud.tencent.com/developer/article/2550911> without copying its branding or pixels.

- Stage 0 selects already-configured source and target connections; 数据源 is a separate management surface and there is no inline connection form.
- Stage 1 resembles a source database/table tree plus a dense workspace table. It supports search, select all, explicit exclusions, individual selection, and numeric sorting; it deliberately has no regex control.
- Stage 2 is exception-first: blockers and risks appear before safe tables, table-level field mapping is dense and structured, and DDL is a separate line-numbered, read-only tab with an explicit lock message. A blocker must be excluded or cropped and re-preflighted.
- Stage 3 is a compact execution confirmation with a truthful scope summary and write-freeze acknowledgement.
- Stages 4 and 5 stay table-centric: monitoring shows stage/progress/result/timestamp per table, while validation keeps PASS, FAIL, and INCONCLUSIVE separate from accepted-risk disposition. Rerun only selects failed/inconclusive eligible tables; accepted risk never changes a technical result.

## Variants and tradeoffs

- **A · 线性向导** is the agreed journey: discoverability and safety win over maximum information density.
- **B · 异常工作台** remains an earlier contrast concept that puts exceptions first for experienced operators.
- **C · 三个决定** remains an earlier contrast concept that compresses the journey for orientation.

The X2Doris lineage is used as a visual/configuration reference: persistent shell, compact enterprise controls, database tree + workspace, numbered progress, mapping table, read-only DDL review, centered confirmation, dense task center, and compact start confirmation. DBX intentionally does not expose X2Doris-only concepts such as connectors, boxes, topics, Spark settings, or arbitrary resource knobs. DBX instead emphasizes explicit connection reuse, table scope, preflight evidence, write-freeze accountability, and truthful table-level results.

## Prototype constraints

- Illustrative 200-table metadata; the initial state contains 196 eligible tables and 4 explicitly excluded blockers, so excluded blockers never appear as migrated validation results.
- One MySQL source and PostgreSQL target schema; no network, authentication, SQL execution, persistence, or real-time transport.
- Keyboard arrows move between stages in A and are ignored while focus is in inputs/selects. Responsive CSS collapses the shell on narrow screens.
- This is intentionally plain HTML/CSS/JS with no dependencies.
