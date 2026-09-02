import { describe, expect, it } from 'vitest';
import { messages } from './index';

describe('messages module', () => {
  it('uses the glossary wording for terms that appear in the interface', () => {
    // The `_中文_` lines in CONTEXT.md are the source of truth for these strings.
    expect(messages.nav.migrationTasks).toBe('迁移任务');
    // #33 promoted these out of `densitySample`: the migration boundaries are product
    // vocabulary, and the design reference page is one of their readers, not their owner.
    expect(messages.phase.readComplete).toBe('读取完成');
    expect(messages.phase.writeComplete).toBe('写入完成');
    expect(messages.phase.migrationComplete).toBe('迁移完成');
    expect(messages.phase.stuck).toBe('卡死');
    expect(messages.conclusion.labels.STUCK).toBe('卡死');
  });

  it('words every conclusion it renders, and never as an enum literal', () => {
    // #42: every user-visible string traces to a `_中文_` line in CONTEXT.md. The three
    // preflight conclusions are the ones the whole safety sequence turns on.
    expect(messages.conclusion.labels.SUPPORTED).toBe('可迁移');
    expect(messages.conclusion.labels.UNSUPPORTED).toBe('不可迁移');
    expect(messages.conclusion.labels.INCONCLUSIVE).toBe('无法判定');
    for (const label of Object.values(messages.conclusion.labels)) {
      expect(label).not.toMatch(/^[A-Z_]+$/);
    }
  });

  it('keeps 不适用 and 未执行 apart, and neither of them a failure', () => {
    // A DBA must not chase a check that never needed to run, and must not read either as
    // a failure. `CONTEXT.md` gives them separate entries for that reason.
    expect(messages.conclusion.labels.NOT_APPLICABLE).toBe('不适用');
    expect(messages.conclusion.labels.NOT_RUN).toBe('未执行');
    expect(messages.conclusion.labels.NOT_APPLICABLE).not.toBe(messages.conclusion.labels.NOT_RUN);
    for (const label of [
      messages.conclusion.labels.NOT_APPLICABLE,
      messages.conclusion.labels.NOT_RUN,
    ]) {
      expect(label).not.toContain('失败');
      expect(label).not.toContain('错误');
    }
  });

  it('never lets 无法判定 read as a softened warning', () => {
    // The whole spec turns on 「无法判定」 not being heard as 「有点风险但可以过」, so the
    // word shares nothing with 未通过 and carries no hedge.
    const inconclusive = messages.conclusion.labels.INCONCLUSIVE;
    expect(inconclusive).toBe('无法判定');
    expect(inconclusive).not.toContain('风险');
    expect(inconclusive).not.toContain('警告');
    expect(inconclusive).not.toBe(messages.conclusion.labels.FAIL);
  });

  it('keeps 数据源 the navigation area and 数据库连接 the endpoint', () => {
    // `Data source management`'s `_中文_` names the navigation area alone; an individual
    // endpoint is a 数据库连接 and never a 数据源, which is why `Database connection`
    // still lists `datasource` under `_Avoid_`. Getting this backwards is the easiest
    // vocabulary mistake on this page.
    expect(messages.nav.databaseConnections).toBe('数据源');
    expect(messages.connections.title).toBe('数据源');
    expect(messages.connections.listLabel).toBe('数据库连接');
    expect(messages.connections.credentialVersionLabel).toBe('凭据版本');
  });

  it('words a 连接校验 outcome, and never calls an unchecked connection broken', () => {
    expect(messages.connections.checkOutcomes.SUCCEEDED).toBe('校验通过');
    expect(messages.connections.checkOutcomes.FAILED).toBe('校验失败');
    // An absence of evidence, never evidence of a problem (`CONTEXT.md`).
    expect(messages.connections.checkOutcomes.NOT_RUN).toBe('尚未校验');
    expect(messages.connections.checkOutcomes.NOT_RUN).not.toContain('失败');
  });

  it('says 没有匹配项 rather than 没有数据 when a filter emptied a table', () => {
    // A filtered-out table and an empty one are different facts, and #30 requires the
    // interface to keep them apart so a DBA does not go looking for missing records.
    expect(messages.table.noMatches.title).toBe('没有匹配项');
    expect(messages.table.noMatches.title).not.toContain('没有数据');
  });

  it('keeps 迁移草稿 a concept of its own rather than an unapproved 迁移任务', () => {
    // `Migration draft` lists 「unsaved task」, 「pending task」 and 「unapproved migration
    // task」 under `_Avoid_`, and `Migration task`'s definition makes approval part of what
    // a task *is*. The interface has to keep those two apart in words as well as in types.
    expect(messages.drafts.title).toBe('迁移草稿');
    expect(messages.drafts.lead).toContain('不产生迁移运行');
    expect(messages.drafts.lead).toContain('经执行确认后才成为迁移任务');
    expect(messages.drafts.discard.body).toContain('不留痕迹');
  });

  it('names the six wizard stages the way ADR-0007 orders them', () => {
    expect(messages.wizard.stages.connections).toBe('连接与数据库');
    expect(messages.wizard.stages.scope).toBe('迁移范围');
    expect(messages.wizard.stages.tables).toBe('逐表配置与预检');
    expect(messages.wizard.stages.confirm).toBe('执行确认');
    expect(messages.wizard.stages.monitor).toBe('运行监控');
    expect(messages.wizard.stages.validation).toBe('校验报告');
  });

  it('explains Gate 1 rather than only refusing', () => {
    // A blocked state that cannot say what would unblock it is indistinguishable from a bug.
    expect(messages.wizard.gates.noTableSelected).toContain('迁移范围');
    expect(messages.wizard.gates.noTableSelected).toContain('至少一张表');
  });

  it('never calls a discovery estimate a 源基线', () => {
    // `Source baseline` lists 「estimated row count」 under `_Avoid_`: a baseline is exact
    // and is captured under a write freeze, and nothing in 迁移范围 is one.
    expect(messages.wizard.scope.estimateNotice).toContain('预估值');
    expect(messages.wizard.scope.estimateNotice).toContain('不是源基线');
    expect(messages.wizard.scope.largeRecordTable).toBe('大记录表');
  });

  it('calls the DDL a rendering of the 表写入契约, never an editor', () => {
    // ADR-0011 lists 「editable DDL」 among its rejected alternatives, and `Table write
    // contract`'s `_Avoid_` names it too. The wording is the product's main defence
    // against a DBA assuming the pane is a SQL editor (story 44).
    expect(messages.wizard.tables.readOnlyNotice).toContain('表写入契约');
    expect(messages.wizard.tables.readOnlyNotice).toContain('只读');
    expect(messages.wizard.tables.readOnlyNotice).toContain('不是可以手改的 SQL 编辑器');
    expect(messages.wizard.tables.contractVersion(2)).toContain('表写入契约');
  });

  it('uses 补建 SQL for the structures outside the v1 writable-table contract', () => {
    // `Supplemental SQL`'s `_中文_` is 补建 SQL, and its definition is that DBX v1
    // delivers it but does not execute it as part of migration. Calling it 「已迁移的
    // 约束」 would claim a structural migration v1 does not perform.
    expect(messages.wizard.tables.outOfContract).toBe('补建 SQL');
    expect(messages.wizard.tables.supplementalTitle).toBe('补建 SQL');
    expect(messages.wizard.tables.outOfContractNotice).toContain('不在迁移过程中执行');
  });

  it('says who decided a 映射规则, in words', () => {
    // 「whether DBX or the user produced it; user rules override automatic rules」.
    expect(messages.wizard.tables.ruleOrigins.PLATFORM).toBe('DBX 自动生成');
    expect(messages.wizard.tables.ruleOrigins.USER).toBe('用户指定');
  });

  it('never offers to acknowledge a 预检', () => {
    // `Preflight`'s `_Avoid_` line names 「warning acknowledgement」 outright, and #30's
    // story 41 turns it into a requirement: a blocking or inconclusive preflight cannot be
    // confirmed away. So every sentence the blocked operator is given is an exit, and the
    // copy says in as many words that there is no fourth one.
    const preflight = messages.wizard.tables.preflight;
    expect(preflight.exits.noFourth).toContain('不能被确认掉');
    expect(preflight.exits.noFourth).toContain('不能被关闭');
    for (const sentence of [
      preflight.overEnvelopeTitle('order_item', 'payload', '20,971,521'),
      preflight.inconclusiveTitle(preflight.inconclusiveReasons.QUERY_TIMEOUT),
      preflight.unsupportedTitle('order_item'),
    ]) {
      expect(sentence).toMatch(/不能忽略/);
      expect(sentence).not.toContain('确认继续');
    }
  });

  it('states ADR-0003 three exits, in ADR-0003 own words', () => {
    // The sentence is fixed by the ADR character for character, so it is copied rather
    // than paraphrased; the three exits are the ones it names.
    const overEnvelope = messages.wizard.tables.preflight.overEnvelopeTitle(
      'order_item',
      'payload',
      '20,971,521',
    );
    expect(overEnvelope).toContain('超过 DBX v1 的 20 MiB（20,971,520 字节）上限');
    expect(overEnvelope).toContain(
      '请选择排除此表、裁剪超限字段后重新预检，或中止并在源端缩减数据',
    );

    const exits = messages.wizard.tables.preflight.exits;
    expect(exits.fixSource.title).toBe('修正源');
    expect(exits.pruneColumn.title).toContain('裁剪超限字段');
    expect(exits.excludeTable.title).toBe('显式排除该表');
    // 「裁掉一个字段不豁免整行检查」 — ADR-0003 says so explicitly, and hiding it would
    // make the second exit look stronger than it is.
    expect(exits.pruneColumn.body).toContain('不豁免整行检查');
  });

  it('never calls 无法判定 a warning', () => {
    // #30: drawing INCONCLUSIVE as a caution teaches a DBA to read it as 「有点风险但可以
    // 过」. ADR-0003 gives it its own sentence, which is a statement about what DBX could
    // not establish rather than about how risky the table is.
    const preflightCopy = messages.wizard.tables.preflight;
    const inconclusive = preflightCopy.inconclusiveTitle(
      preflightCopy.inconclusiveReasons.QUERY_TIMEOUT,
    );
    expect(inconclusive).toContain('无法确认是否可迁移');
    expect(inconclusive).not.toContain('警告');
    expect(inconclusive).not.toContain('风险');
    expect(messages.conclusion.labels.INCONCLUSIVE).toBe('无法判定');
  });

  it('says a running 预检 is running rather than leaving the pane silent', () => {
    // Story 48: a scan that takes time must not be readable as a frozen interface.
    expect(messages.wizard.tables.preflight.inFlight).toContain('预检进行中');
    expect(messages.wizard.tables.preflight.inFlight).toContain('界面没有卡死');
  });

  it('explains Gate 2 in the conclusions own wording', () => {
    const reason = messages.wizard.gates.preflightNotSupported(
      2,
      'order_item',
      messages.conclusion.labels.INCONCLUSIVE,
    );
    expect(reason).toContain('无法判定');
    expect(reason).toContain('只有结论为可迁移的预检可以继续');
    expect(reason).toContain('显式排除该表');
  });

  it('never lets a 写冻结 be a permanent checkbox', () => {
    // `Write freeze`'s `_Avoid_` names 「permanent checkbox」 outright, and its definition
    // makes an accountable operator and an expiry part of what a freeze *is*. So the
    // interface asks for both by name, and the gate's refusal says so too.
    const confirm = messages.wizard.confirm;
    expect(confirm.freezeHeading).toBe('写冻结');
    expect(confirm.operatorLabel).toBe('责任人');
    expect(confirm.durationLabel).toBe('时限');
    expect(confirm.durationOption(8)).toBe('8 小时');
    expect(messages.wizard.gates.writeFreezeNotConfirmed).toContain('责任人');
    expect(messages.wizard.gates.writeFreezeNotConfirmed).toContain('时限');
    // 「externally enforced」: DBX records the commitment, it does not impose it.
    expect(confirm.freezeConstraint).toContain('DBX 只记录');
  });

  it('states Gate 6 as a constraint rather than as something the frontend performed', () => {
    // 结构证明 is 「the deterministic comparison of the actual PostgreSQL table … against
    // the approved table write contract. Only zero difference permits the Sink to start」,
    // and it happens server-side inside the run (lead decision D11). The copy says both:
    // what the rule is, and who establishes it.
    const confirm = messages.wizard.confirm;
    expect(confirm.proofHeading).toBe('结构证明');
    expect(confirm.proofConstraint).toContain('表写入契约');
    expect(confirm.proofConstraint).toContain('只有零差异');
    expect(confirm.proofConstraint).toContain('不会向目标表写入');
    expect(confirm.proofConstraint).toContain('由平台在迁移运行内');
    expect(messages.wizard.gates.structuralProofMissing(1, 'order_item')).toContain(
      '没有结构证明，DBX 不会开始写入目标表',
    );
    // ADR-0011: an existing target table 「fails review rather than reusing, truncating,
    // or replacing it」 — the copy must not soften that into a reuse.
    expect(confirm.proofGaps.TARGET_TABLE_EXISTS).toContain('不会复用、清空或替换');
  });

  it('says a 迁移运行 is an immutable snapshot rather than an editable plan', () => {
    // `Migration run`: 「one immutable execution attempt」, with 「retry in place」 and
    // 「resumed run」 under `_Avoid_`. The dialog is where the operator consents to that,
    // so it is where the sentence has to appear.
    expect(messages.wizard.confirm.start.body).toContain('不可变的执行快照');
    expect(messages.wizard.confirm.start.body).toContain('范围日后不可篡改');
    expect(messages.wizard.confirm.start.challengeHelper).toContain('不能顺手点过');
  });

  it('never calls a 未解决的发现 harmless', () => {
    // A blocking finding cannot reach 执行确认 at all, so what is listed there is what
    // nobody resolved. The wording says it travels with the migration rather than
    // implying it stopped mattering once the stage let the table through.
    const notice = messages.wizard.confirm.findingsNotice('3');
    expect(notice).toContain('未解决的发现');
    expect(notice).toContain('会随这次迁移一起被带走');
    expect(notice).not.toContain('可以忽略');
  });

  it('keeps the two selection scopes verbally distinct', () => {
    // ADR-0015 leaves cross-page selection semantics to DBX. "Select all" that silently
    // means "this page" is the mistake the wording exists to prevent.
    expect(messages.table.selection.selectPageAction).toBe('当前页全选');
    expect(messages.table.selection.selectAllMatchingAction).toBe('选中符合当前筛选的全部');
    expect(messages.table.selection.selectedCount(3, '张')).toBe('已选 3 张');
  });

  it('gives the two phases named after the box their operator-facing words', () => {
    // Gate 7: 「A box is an internal scheduling detail, and the operator is not required to
    // understand the execution platform in order to run the product」 (`CONTEXT.md`). The
    // usual fallback — render the enum literal — is unavailable for these two, because the
    // literals name the box; `CONTEXT.md` therefore defines the words, and they are used.
    expect(messages.run.phases.WAITING_FOR_BOX).toBe('等待调度');
    expect(messages.run.outcomes.BLOCKED_BY_BOX_FAILURE).toBe('因关联失败而阻塞');
    for (const label of [
      ...Object.values(messages.run.phases),
      ...Object.values(messages.run.outcomes),
      ...Object.values(messages.run.rootCauseDomains),
    ]) {
      expect(label.toLowerCase()).not.toContain('box');
      expect(label.toLowerCase()).not.toContain('kafka');
      expect(label).not.toContain('箱');
    }
    // #42: everything else is worded too, so no phase, outcome or domain is a literal.
    expect(messages.run.phases.TRANSFERRING).toBe('传输中');
    // `SUCCEEDED` is exactly the boundary `Migration complete` defines, so it carries that
    // term's wording rather than a second word for the same fact.
    expect(messages.run.outcomes.SUCCEEDED).toBe('迁移完成');
    expect(messages.run.outcomes.SUCCEEDED).toBe(messages.phase.migrationComplete);
    for (const label of [
      ...Object.values(messages.run.phases),
      ...Object.values(messages.run.outcomes),
      ...Object.values(messages.run.rootCauseDomains),
      ...Object.values(messages.tasks.runStatuses),
    ]) {
      expect(label).not.toMatch(/^[A-Z_]+$/);
    }
  });

  it('never spends 取消 on a unit that merely stopped', () => {
    // `CONTEXT.md` gives 取消 to a person's terminal stop of a 迁移运行. A unit that
    // stopped without a result of its own is a different fact, and shares no word with it.
    expect(messages.run.outcomes.CANCELLED).toBe('因运行取消而停止');
    expect(messages.tasks.runStatuses.CANCELLED).toBe('已取消');
    expect(messages.run.outcomes.CANCELLED).not.toBe(messages.tasks.runStatuses.CANCELLED);
    // 已排除未迁移 is the other undetermined outcome, and neither reads as a failure.
    expect(messages.run.outcomes.SKIPPED).toBe('已排除未迁移');
    for (const outcome of [messages.run.outcomes.CANCELLED, messages.run.outcomes.SKIPPED]) {
      expect(outcome).not.toContain('失败');
    }
  });

  it('keeps the DBX 根因域 apart from the 迁移平台 one', () => {
    // `PLATFORM` is DBX's own logic; `Kafka Connect` and `Kafka` present as 迁移平台. One
    // word for both would tell the operator the opposite of what the evidence says.
    expect(messages.run.rootCauseDomains.PLATFORM).toBe('DBX 自身');
    expect(messages.run.rootCauseDomains.PLATFORM).not.toBe(messages.run.rootCauseDomains.KAFKA);
  });

  it('does not word a run status that ends in completion as a bare 完成', () => {
    // Three of the eight end in completion, and a reader scanning a list must be able to
    // tell them apart at a glance.
    expect(messages.tasks.runStatuses.COMPLETED).toBe('全部完成');
    expect(messages.tasks.runStatuses.COMPLETED_WITH_FAILURES).toBe('完成，有失败');
    expect(messages.tasks.runStatuses.COMPLETED_WITH_ACCEPTED_RISK).toBe('完成，已接受风险');
    expect(messages.tasks.runStatuses.ATTENTION_REQUIRED).toBe('需要人工处理');
  });

  it('presents both execution-platform 根因域 values as the single 迁移平台 domain', () => {
    // `CONTEXT.md`: 「The distinction between them is DBX's own to act on, not the
    // operator's」. The specific domain is retained in the diagnostic evidence.
    expect(messages.run.rootCauseDomains.KAFKA_CONNECT).toBe('迁移平台');
    expect(messages.run.rootCauseDomains.KAFKA).toBe('迁移平台');
  });

  it('says progress may jump and lag rather than implying smooth advance', () => {
    // ADR-0004 makes progress observations coalescable. An interface that renders them as
    // smooth advance is telling a DBA something the platform never said.
    const notice = messages.run.observationNotice;
    expect(notice).toContain('跳变');
    expect(notice).toContain('滞后');
    expect(notice).toContain('不做平滑推进');
    expect(messages.run.lagging).toBe('观测滞后');
    expect(messages.run.laggingDetail('2026-09-01 09:00 UTC')).toContain('不是卡死');
  });

  it('never grades 卡死 as a degree of slowness', () => {
    // 卡死's `_Avoid_` names 「slow」, 「failed」 and 「timed out」. It is a terminal
    // diagnosis with a threshold, and the copy states the threshold rather than a degree.
    const stuck = messages.run.stuck;
    expect(stuck.heading).toBe('卡死');
    expect(stuck.statement).toContain('终局诊断');
    expect(stuck.statement).toContain('硬阈值');
    expect(stuck.notSlow).toContain('慢的表仍在推进');
    // 因关联失败而阻塞 is not a failure of the table it names.
    expect(stuck.blockedExplanation).toContain('技术结果未定');
    expect(stuck.blockedExplanation).toContain('重新迁移');
  });

  it('states what a 取消 does and does not do, before it happens', () => {
    // 取消's `_Avoid_` names 「discard」, 「delete」 and 「rollback」; its definition keeps
    // target data and diagnostic evidence. Both halves are consequences, so both are said.
    const cancel = messages.run.cancel;
    expect(cancel.preserved).toContain('保留');
    expect(cancel.preserved).toContain('不是丢弃');
    expect(cancel.preserved).toContain('不回滚');
    expect(cancel.terminalStop).toContain('新的迁移运行');
    expect(cancel.terminal(2)).toContain('技术结果保持不变');
  });

  it('never says a 校验处置 changed the technical conclusion', () => {
    // 校验处置's `_Avoid_` names 「manual pass」 and 「overridden result」 outright, and its
    // definition is that accepting risk 「may close the workflow but never changes the
    // technical validation result to passed」. This is the sentence the whole audit chain
    // hangs from, so it is asserted as copy and not only as behaviour.
    const disposition = messages.validation.disposition;
    expect(disposition.heading).toBe('校验处置');
    expect(disposition.statement).toContain('不会把技术结论改写为通过');
    expect(disposition.statement).toContain('责任人');
    expect(disposition.statement).toContain('关闭流程');
    const failLabel = messages.conclusion.labels.FAIL;
    expect(disposition.modal.body('order_item', failLabel)).toContain('不会改变这个技术结论');
    // The denial has to survive 通过 becoming the word for `PASS`: the sentence says the
    // disposition does not turn the result into one, and never says the table passed.
    expect(disposition.modal.body('order_item', failLabel)).toContain('不会把它变成通过');
    expect(disposition.technicalResultUnchanged(failLabel)).toBe('技术结论仍然是未通过');
    // The decision asks for the two things that make it audited.
    expect(disposition.modal.operatorLabel).toBe('责任人');
    expect(disposition.modal.reasonLabel).toBe('理由');
  });

  it('keeps 「没迁」 and 「迁了但没过」 apart in words', () => {
    const exclusions = messages.validation.exclusions;
    expect(exclusions.heading).toBe('预检排除项');
    expect(exclusions.statement).toContain('没有迁移');
    expect(exclusions.statement).toContain('没有技术结论');
    // 「显式排除是可复核的例外」, and 「只有 SUPPORTED 的预检可以继续」 — two different reasons.
    expect(exclusions.reasonDetails.OPERATOR_EXCLUDED).toContain('显式排除');
    expect(exclusions.reasonDetails.PREFLIGHT_UNSUPPORTED).toContain(
      '只有结论为可迁移的预检可以继续',
    );
    // A table that was never migrated has no technical conclusion, so no reason may read
    // as a failure.
    for (const reason of Object.values(exclusions.reasons)) {
      expect(reason).not.toContain('失败');
    }
    expect(exclusions.reasonDetails.PREFLIGHT_INCONCLUSIVE).toContain('不能被确认掉');
  });

  it('says 不适用 and 未执行 are not failures to chase', () => {
    const note = messages.validation.summary.itemsNote;
    expect(note).toContain(messages.conclusion.labels.NOT_APPLICABLE);
    expect(note).toContain(messages.conclusion.labels.NOT_RUN);
    expect(note).toContain('都不是失败');
    expect(note).not.toMatch(/NOT_APPLICABLE|NOT_RUN/);
  });

  it('refuses to present a half-finished 校验 as a conclusion', () => {
    const inFlight = messages.validation.inFlight;
    expect(inFlight.heading).toBe('校验尚未跑完');
    expect(inFlight.body(7, 12)).toContain('不给出总体结论');
  });

  it('names each 校验项 by what it compares', () => {
    expect(messages.validation.checks.ROW_COUNT).toBe('行数比对');
    // A sample never claims full value equality, and the name says sample.
    expect(messages.validation.checks.VALUE_CHECKSUM_SAMPLE).toBe('抽样值比对');
    for (const label of Object.values(messages.validation.checks)) {
      expect(label).not.toMatch(/^[A-Z_]+$/);
    }
  });

  it('never leaves a rendered value set as its persisted literal', () => {
    // #42's own claim, asserted over every value vocabulary the interface renders. Each of
    // these sets has its wording in `CONTEXT.md`; a literal here means one was missed.
    const sets = [
      messages.conclusion.labels,
      messages.tasks.runStatuses,
      messages.run.phases,
      messages.run.outcomes,
      messages.run.rootCauseDomains,
      messages.run.evidence.diagnosis.sourceKinds,
      messages.connections.checkOutcomes,
      messages.connections.register.tlsModes,
      messages.wizard.tables.ruleOrigins,
      messages.wizard.tables.preflight.codeLabels,
      messages.wizard.tables.preflight.inconclusiveReasons,
      messages.validation.checks,
      messages.validation.exclusions.reasons,
    ];
    for (const set of sets) {
      for (const label of Object.values(set)) {
        expect(label).not.toMatch(/^[A-Z][A-Z_]*$/);
      }
    }
  });

  it('says how much a 诊断 is worth trusting, in words', () => {
    const kinds = messages.run.evidence.diagnosis.sourceKinds;
    expect(kinds.STRUCTURED).toBe('DBX 直接判定');
    expect(kinds.EXTERNAL_TRANSLATION).toBe('外部信号翻译');
    // 「An unknown or conflicting diagnosis must not invent a cause」.
    expect(kinds.SYSTEM_FALLBACK).toBe('兜底判定');
  });

  it('names the bound and the true total when a list is bounded', () => {
    // Lead decision D24: bounded rendering states its bound and the true total.
    expect(messages.run.matrix.bounded(12, 1164)).toContain('12');
    expect(messages.run.matrix.bounded(12, 1164)).toContain('1164');
    expect(messages.run.events.bounded(60, 400)).toContain('共 400 条');
  });
});
