---
status: accepted (amends the run-status projection of ADR-0004, the paused-admission consequence of ADR-0036, and the execution-platform clause of ADR-0021; makes ADR-0032's pause concrete)
---

# Admission paused is a run fact, and the run requires attention

ADR-0032 pauses admission after a run's second Connect restart, or after two consecutive boxes reach 卡死 (stuck) without producing a record, until the DBA chooses to continue. It left open where the pause lives, what the DBA presses, and what happens next ([#88](https://github.com/liumingjian/dbx/issues/88)).

- **A durable run fact.** 准入已暂停 (admission paused) is recorded on the migration run with its reason, trigger, and time, and survives a DBX restart. It stops admission for that run only; other runs keep being admitted. While any run holds one, the runtime condition (运行状况) is 受阻 with that reason.
- **The run shows 需要人工处理.** While the pause is open, the run projects `ATTENTION_REQUIRED`, ahead of active work, even while other boxes still drain. This amends ADR-0004's precedence (cancellation in progress, then open admission pause, then active work, then required attention, then terminal severity) and replaces ADR-0036's "the run stays running".
- **One resume path.** Both triggers write the same record, told apart by `reason`: *迁移平台发生过重启* or *迁移平台无法启动新的读取*. The DBA sees 「继续迁移」 beside 「取消运行」, which is the existing whole-run cancellation. 准入 stays internal; `orchestration`'s continue-admission (继续准入) command serves the button.
- **Once warned, one more strikes.** After the DBA continues, a single further Connect restart, or a single further zero-output stuck box, pauses the run again. Counters do not reset to demand a second proof.
- **No timeout of its own.** The pause waits for the DBA. Write-freeze expiry keeps its own rule and is not decided here.
- **One ten-minute budget.** Schema Registry joins ADR-0021's unreachable-platform clause in full: no admission, no stuck accrual, 受阻, and boxes failing after ten continuous minutes. Source and target database unreachability is run-scoped and never changes the runtime condition. DBX's own calls use ADR-0006's ten-minute budget. A connector task that fails on the database follows ADR-0001's failure rule, and the time does not count toward 卡死.

## Considered options

- **Keep the run `RUNNING` and rely on the condition banner** (ADR-0036's line). Rejected: the run list would hide that the run is waiting for the DBA, which is what 需要人工处理 means.
- **A new run status such as `ADMISSION_HELD`.** Rejected: `ATTENTION_REQUIRED` already means a person must act before execution can advance.
- **Reset the counters on continue.** Rejected: it spends two more boxes proving a fault the DBA was already shown.
