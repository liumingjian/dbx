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

  it('shows preflight conclusions as the enum literal, not an invented translation', () => {
    // CONTEXT.md carries no `_中文_` for these, and #30 writes them in English too.
    expect(messages.conclusion.labels.SUPPORTED).toBe('SUPPORTED');
    expect(messages.conclusion.labels.UNSUPPORTED).toBe('UNSUPPORTED');
    expect(messages.conclusion.labels.INCONCLUSIVE).toBe('INCONCLUSIVE');
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

  it('shows connection check outcomes as the enum literal', () => {
    // As with preflight conclusions, CONTEXT.md carries no `_中文_` for these.
    expect(messages.connections.checkOutcomes.succeeded).toBe('SUCCEEDED');
    expect(messages.connections.checkOutcomes.failed).toBe('FAILED');
    expect(messages.connections.checkOutcomes.notRun).toBe('NOT_RUN');
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

  it('shows a 映射规则 origin as the enum literal', () => {
    // As with preflight conclusions: CONTEXT.md carries no `_中文_` for these, so the
    // interface renders the literal rather than inventing a translation.
    expect(messages.wizard.tables.ruleOrigins.PLATFORM).toBe('PLATFORM');
    expect(messages.wizard.tables.ruleOrigins.USER).toBe('USER');
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
      preflight.inconclusiveTitle('QUERY_TIMEOUT'),
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
    expect(overEnvelope).toContain('请选择排除此表、裁剪超限字段后重新预检，或中止并在源端缩减数据');

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
    const inconclusive = messages.wizard.tables.preflight.inconclusiveTitle('QUERY_TIMEOUT');
    expect(inconclusive).toContain('无法确认是否可迁移');
    expect(inconclusive).not.toContain('警告');
    expect(inconclusive).not.toContain('风险');
    expect(messages.conclusion.labels.INCONCLUSIVE).toBe('INCONCLUSIVE');
  });

  it('says a running 预检 is running rather than leaving the pane silent', () => {
    // Story 48: a scan that takes time must not be readable as a frozen interface.
    expect(messages.wizard.tables.preflight.inFlight).toContain('预检进行中');
    expect(messages.wizard.tables.preflight.inFlight).toContain('界面没有卡死');
  });

  it('explains Gate 2 with the conclusion as its enum literal', () => {
    const reason = messages.wizard.gates.preflightNotSupported(2, 'order_item', 'INCONCLUSIVE');
    expect(reason).toContain('INCONCLUSIVE');
    expect(reason).toContain('只有 SUPPORTED 的预检可以继续');
    expect(reason).toContain('显式排除该表');
  });

  it('keeps the two selection scopes verbally distinct', () => {
    // ADR-0015 leaves cross-page selection semantics to DBX. "Select all" that silently
    // means "this page" is the mistake the wording exists to prevent.
    expect(messages.table.selection.selectPageAction).toBe('当前页全选');
    expect(messages.table.selection.selectAllMatchingAction).toBe('选中符合当前筛选的全部');
    expect(messages.table.selection.selectedCount(3, '张')).toBe('已选 3 张');
  });
});
