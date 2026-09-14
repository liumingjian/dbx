# Preflight findings carry three impacts, approval is their acceptance, and every conclusion has one colour

`dbx-prototype` grades findings in two levels, `blocking` (the task may not be created) and `warning` (each one ticked 我已知晓), and its validation enum has no `INCONCLUSIVE`. #23 had fixed three levels: red, orange, yellow. #45 made the three-state conclusion a floor the prototype port may not give up. Decided in [#52](https://github.com/liumingjian/dbx/issues/52). This ADR restates #23 §6 in the vocabulary below and supersedes its colour wording.

## Impact, not severity

Every preflight finding (预检发现) carries one 预检发现影响 (preflight finding impact):

| Impact | Colour | Findings |
|---|---|---|
| 阻塞 (blocking) | red | large record value or row over the 大记录包络; value domain out of range; zero date value rejected; source `auto_increment` already above 2^63-1; a same-name target table exists; structural-proof difference |
| 数据有损 (data loss) | orange | `NOT NULL` relaxed per column; primary key not built because it is too wide; sub-millisecond precision truncated to milliseconds |
| 仅行为差异 (behaviour change only) | yellow | B-tier `DEFAULT` not built; sequence ceiling narrowed (`BIGINT UNSIGNED`); no primary key and no single candidate |

A new finding code is added to this table when it is introduced, never left ungraded.

Only 阻塞 gates, and it gates **per table**, not per task. A blocked table is corrected, pruned, or excluded (预检判定不可迁移), and the other tables continue. The prototype's task-level block is removed.

The prototype's `warning` is split in two because a change review asks first whether data changes or only later behaviour changes. Merging them hides the one distinction the DBA must defend.

A fact with no trade-off is not a finding impact. A 大记录表 inside the envelope shows a neutral 大记录表 label. Indexes, foreign keys, and comments not built are one task-level line pointing at the supplemental SQL (ADR-0026), not a yellow finding on every table.

## `INCONCLUSIVE` is outside the scale

无法判定 is not a fourth impact. It gates exactly like 阻塞: it cannot be acknowledged, and its exits are the same. It is drawn neutral with a question mark and names its reason (查询超时 / 权限不足 / 连接中断), per ADR-0017.

## Approval is the acceptance

There is no per-finding acknowledgement. Non-blocking findings are part of the table write contract, and the operator already approves every contract before execution (ADR-0004 `AWAITING_APPROVAL`). That approval is the audited acceptance, frozen into the 运行快照. 执行确认 lists the unresolved findings as counts per impact, each opening the affected tables' drawers. A per-item tick across 200 tables trains the operator to tick everything, and that erases the signal.

## Validation vocabulary

The prototype's `VerifyResult` is replaced by the validation item states in `CONTEXT.md` (`PASS / FAIL / INCONCLUSIVE / NOT_APPLICABLE / NOT_RUN`, plus 执行中). `count_mismatch` and `content_mismatch` become `FAIL` reason codes. The prototype's `error` becomes `INCONCLUSIVE` (#16 §7). 完成，已接受风险 and 校验处置 are unchanged from ADR-0004.

## Palette

`conclusions/conclusion.ts` remains the only mapping site (ADR-0017). Values live in `src/theme/tokens.css`:

| Conclusion | Colour token | Shape |
|---|---|---|
| 阻塞, 不可迁移, 未通过, 迁移失败 | `--color-error` | failed |
| 数据有损 | new `--color-caution-major` (`#ff832b`, both themes) | caution-major |
| 仅行为差异 | `--color-warning` | caution-minor |
| 无法判定, 因关联失败而阻塞 | neutral grey | question mark |
| 完成，已接受风险 | `--color-warning` (amber) | `CheckmarkOutlineWarning` |
| 可迁移, 通过, 迁移完成 | `--color-success` | succeeded |

Accepted risk shares the yellow hue and is told apart by shape and label. ADR-0017's three-channel rule exists for exactly this case. The earlier frontend mapped `COMPLETED_WITH_ACCEPTED_RISK` to the `INCONCLUSIVE` indicator; that row is wrong, because #16 fixes accepted risk as amber, and it gets its own `accepted-risk` kind. Enforcement is ADR-0017's: lint forbids conclusions through `Tag color` / `Badge status`, and a unit test asserts icon, colour, and label for every conclusion.

## Considered options

- **Two levels, as the prototype has them**: rejected, since data loss and behaviour change would read the same.
- **Task-level blocking**: rejected; one bad table would stop 199 good ones, against ADR-0020.
- **Per-finding 我已知晓, or one bulk tick for orange**: rejected as a duplicate of contract approval.
- **A separate hue for accepted risk**: rejected; one more colour to learn, when shape and label already separate it.
