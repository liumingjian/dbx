import { describe, expect, it } from 'vitest';
import type { DatabaseConnection, MigrationDraft } from '@/contract';
import { messages } from '@/messages';
import {
  evaluateStageGate,
  furthestReachableStage,
  isStageComplete,
  isStageReachable,
  resolveStageEntry,
  type WizardGateContext,
} from './stageGates';

/**
 * The gating mechanism, tested as the rule it is rather than through a rendered wizard.
 *
 * Gate 1 itself — 「一张表都没选时不能前进」 — is proved at seam 1 in
 * `e2e/wizard-scope.spec.ts`, where it has to block a real browser and a typed URL. What
 * is checked here is the mechanism the gate hangs on: that reachability is derived from the
 * gates, so no stage can be reached by any route without satisfying every one before it.
 */

function connection(overrides: Partial<DatabaseConnection>): DatabaseConnection {
  return {
    id: 'conn-source',
    name: '订单库（生产）',
    role: 'SOURCE',
    dialect: 'MYSQL_8_0',
    host: 'mysql.internal',
    port: 3306,
    database: 'orders',
    databases: ['orders'],
    tls: 'SERVER_AUTHENTICATED',
    currentCredentialVersion: {
      id: 'cred-1',
      connectionId: 'conn-source',
      version: 1,
      username: 'dbx_reader',
      createdAt: '2026-09-01T00:00:00.000Z',
      destroyedAt: null,
    },
    credentialVersionCount: 1,
    latestCheck: {
      outcome: 'SUCCEEDED',
      checkedAt: '2026-09-01T00:00:00.000Z',
      credentialVersionId: 'cred-1',
      serverVersion: 'MySQL 8.0.36',
      failureReason: null,
    },
    archived: false,
    createdAt: '2026-09-01T00:00:00.000Z',
    updatedAt: '2026-09-01T00:00:00.000Z',
    ...overrides,
  };
}

const source = connection({});
const target = connection({
  id: 'conn-target',
  name: '分析库（生产）',
  role: 'TARGET',
  dialect: 'POSTGRESQL_15',
  port: 5432,
  database: 'analytics',
  databases: ['analytics'],
});
const failingTarget = connection({
  ...target,
  latestCheck: {
    outcome: 'FAILED',
    checkedAt: '2026-09-01T00:00:00.000Z',
    credentialVersionId: 'cred-1',
    serverVersion: null,
    failureReason: 'AUTHENTICATION_FAILED',
  },
});

function draft(overrides: Partial<MigrationDraft> = {}): MigrationDraft {
  return {
    id: 'draft-1',
    name: '',
    createdAt: '2026-09-01T00:00:00.000Z',
    updatedAt: '2026-09-01T00:00:00.000Z',
    sourceConnectionId: null,
    sourceDatabase: null,
    targetConnectionId: null,
    targetSchema: null,
    scopeKind: 'SELECTED_TABLES',
    selectedTables: [],
    excludedTables: [],
    completedStages: [],
    ...overrides,
  };
}

const configured = draft({
  sourceConnectionId: source.id,
  sourceDatabase: 'orders',
  targetConnectionId: target.id,
  targetSchema: 'orders',
});

const context = (entry: MigrationDraft, connections = [source, target]): WizardGateContext => ({
  draft: entry,
  connections,
});

describe('wizard stage gating', () => {
  it('stops a half-configured draft at 连接与数据库', () => {
    const gate = evaluateStageGate('connections', context(draft()));
    expect(gate).toEqual({ blocked: true, reason: messages.wizard.gates.connectionsIncomplete });
    expect(furthestReachableStage(context(draft()))).toBe('connections');
  });

  it('stops a draft whose chosen 数据库连接 is no longer usable, and names it', () => {
    const gate = evaluateStageGate('connections', context(configured, [source, failingTarget]));
    expect(gate).toEqual({
      blocked: true,
      reason: messages.wizard.gates.connectionUnusable(failingTarget.name, 'FAILED'),
    });
  });

  it('opens 迁移范围 once the pair and the databases are chosen', () => {
    expect(evaluateStageGate('connections', context(configured)).blocked).toBe(false);
    expect(furthestReachableStage(context(configured))).toBe('scope');
  });

  it('is Gate 1: an empty 迁移范围 cannot advance', () => {
    expect(evaluateStageGate('scope', context(configured))).toEqual({
      blocked: true,
      reason: messages.wizard.gates.noTableSelected,
    });
  });

  it('advances past 迁移范围 once at least one table is in it', () => {
    const withTables = { ...configured, selectedTables: ['order_item'] };
    expect(evaluateStageGate('scope', context(withTables)).blocked).toBe(false);
    expect(furthestReachableStage(context(withTables))).toBe('tables');
  });

  it('lets the operator back into a completed stage', () => {
    const withTables = context({ ...configured, selectedTables: ['order_item'] });
    expect(isStageComplete('connections', withTables)).toBe(true);
    expect(isStageReachable('connections', withTables)).toBe(true);
    expect(resolveStageEntry('connections', withTables)).toBe('connections');
  });

  it('sends a request for a stage the draft has not earned back to the stage stopping it', () => {
    // Every stage has its own URL, so this is the normal way in rather than an edge case:
    // the gate has to hold against an address as well as against a button.
    const empty = context(draft());
    expect(resolveStageEntry('scope', empty)).toBe('connections');
    expect(resolveStageEntry('tables', empty)).toBe('connections');
    expect(resolveStageEntry('validation', empty)).toBe('connections');
    expect(resolveStageEntry('tables', context(configured))).toBe('scope');
  });

  it('keeps 运行监控 and 校验报告 out of a draft, because a draft produces no 迁移运行', () => {
    const ready = context({ ...configured, selectedTables: ['order_item'] });
    expect(evaluateStageGate('monitor', ready)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.stageBelongsToRun,
    });
    expect(isStageReachable('monitor', ready)).toBe(false);
  });
});
