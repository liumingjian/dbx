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
});
