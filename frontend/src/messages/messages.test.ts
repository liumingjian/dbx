import { describe, expect, it } from 'vitest';
import { messages } from './index';

describe('messages module', () => {
  it('uses the glossary wording for the navigation destinations', () => {
    // The `_中文_` lines in CONTEXT.md are the source of truth for these strings.
    expect(messages.nav.migrationTasks).toBe('迁移任务');
    expect(messages.domain.migrationDraft).toBe('迁移草稿');
    expect(messages.domain.tableMigrationUnit).toBe('表迁移单元');
  });
});
