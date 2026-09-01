import { describe, expect, it } from 'vitest';
import { messages } from './index';

describe('messages module', () => {
  it('uses the glossary wording for terms that appear in the interface', () => {
    // The `_中文_` lines in CONTEXT.md are the source of truth for these strings.
    expect(messages.nav.migrationTasks).toBe('迁移任务');
    expect(messages.densitySample.phases.readComplete).toBe('读取完成');
    expect(messages.densitySample.phases.writeComplete).toBe('写入完成');
    expect(messages.densitySample.phases.migrationComplete).toBe('迁移完成');
    expect(messages.densitySample.phases.stuck).toBe('卡死');
  });

  it('shows preflight conclusions as the enum literal, not an invented translation', () => {
    // CONTEXT.md carries no `_中文_` for these, and #30 writes them in English too.
    expect(messages.densitySample.conclusions.supported).toBe('SUPPORTED');
    expect(messages.densitySample.conclusions.inconclusive).toBe('INCONCLUSIVE');
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
});
