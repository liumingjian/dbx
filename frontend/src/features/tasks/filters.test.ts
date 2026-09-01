import { describe, expect, it } from 'vitest';
import type { MigrationTask } from '@/contract';
import {
  databasePairLabel,
  databasePairsOf,
  filterMigrationTasks,
  isMigrationTaskFilterActive,
  noMigrationTaskFilter,
} from './filters';

const now = Date.parse('2026-09-01T12:00:00.000Z');

function task(overrides: Partial<MigrationTask>): MigrationTask {
  return {
    id: 'task-1',
    name: '订单库迁移至分析库',
    databasePair: { sourceDialect: 'MYSQL_8_0', targetDialect: 'POSTGRESQL_15' },
    sourceConnectionId: 'conn-mysql-orders',
    sourceDatabase: 'orders',
    targetConnectionId: 'conn-pg-analytics',
    targetSchema: 'orders',
    approvedAt: '2026-08-30T12:00:00.000Z',
    approvedBy: 'zhang.wei',
    selectedTableCount: 12,
    runCount: 1,
    latestRunId: 'task-1-run-1',
    latestRunStatus: 'COMPLETED',
    ...overrides,
  };
}

describe('migration task filters', () => {
  it('reads a database pair as one directed relationship', () => {
    expect(databasePairLabel(task({}).databasePair)).toBe('MySQL 8.0 → PostgreSQL 15');
  });

  it('keeps everything when nothing is filtered', () => {
    const tasks = [task({}), task({ id: 'task-2', latestRunStatus: 'CANCELLED' })];
    expect(filterMigrationTasks(tasks, noMigrationTaskFilter, now)).toHaveLength(2);
    expect(isMigrationTaskFilterActive(noMigrationTaskFilter)).toBe(false);
  });

  it('filters by the latest migration run status', () => {
    const tasks = [task({}), task({ id: 'task-2', latestRunStatus: 'ATTENTION_REQUIRED' })];
    const filtered = filterMigrationTasks(
      tasks,
      { ...noMigrationTaskFilter, status: 'ATTENTION_REQUIRED' },
      now,
    );
    expect(filtered.map((entry) => entry.id)).toEqual(['task-2']);
  });

  it('filters by approval time', () => {
    const tasks = [task({}), task({ id: 'old', approvedAt: '2026-01-01T00:00:00.000Z' })];
    const filtered = filterMigrationTasks(
      tasks,
      { ...noMigrationTaskFilter, approvedWithin: 'LAST_7_DAYS' },
      now,
    );
    expect(filtered.map((entry) => entry.id)).toEqual(['task-1']);
  });

  it('offers only the database pairs the list actually contains', () => {
    expect(databasePairsOf([task({})])).toEqual(['MySQL 8.0 → PostgreSQL 15']);
  });
});
