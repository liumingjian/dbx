import { describe, expect, it } from 'vitest';
import type { DatabaseConnection, DraftTableConfiguration, MigrationDraft } from '@/contract';
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
    prunedColumns: [],
    mappingRules: [],
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

function configuration(overrides: Partial<DraftTableConfiguration> = {}): DraftTableConfiguration {
  return {
    sourceTable: 'order_item',
    targetTable: 'order_item',
    preflightConclusion: 'SUPPORTED',
    blockingFindingCount: 0,
    largeRecordTable: false,
    prunedColumnCount: 0,
    mappingExceptionCount: 0,
    undecidedMappingExceptionCount: 0,
    contractVersion: 1,
    ...overrides,
  };
}

const context = (
  entry: MigrationDraft,
  connections = [source, target],
  tableConfigurations: readonly DraftTableConfiguration[] | null = [configuration()],
): WizardGateContext => ({
  draft: entry,
  connections,
  tableConfigurations,
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
    expect(isStageReachable('tables', context(withTables))).toBe(true);
    // 执行确认 is where this draft now stops, because #37 has not delivered its gate yet.
    expect(furthestReachableStage(context(withTables))).toBe('confirm');
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

  it('stops 逐表配置与预检 while a table in the 迁移范围 has no 表写入契约', () => {
    // ADR-0011: a 表写入契约 is the complete single-table write intent, and DBX may not
    // assemble one while an exception it refuses to decide for the operator — the
    // *approved* per-column zero-date relaxation — is still open. A table with no contract
    // has nothing for 执行确认 to summarise and nothing for a 结构证明 to compare against.
    const withTables = { ...configured, selectedTables: ['order_item', 'order_header'] };
    const blocked = context(
      withTables,
      [source, target],
      [
        configuration(),
        configuration({
          sourceTable: 'order_header',
          mappingExceptionCount: 2,
          undecidedMappingExceptionCount: 1,
          contractVersion: null,
        }),
      ],
    );
    expect(evaluateStageGate('tables', blocked)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.contractNotGenerated(1, 'order_header'),
    });
    expect(furthestReachableStage(blocked)).toBe('tables');
    expect(isStageReachable('confirm', blocked)).toBe(false);
    // Blocked at stage three still means stage three is where the draft stands: a gate
    // stops the operator leaving a stage, never entering it.
    expect(resolveStageEntry('tables', blocked)).toBe('tables');
  });

  it('does not let 逐表配置与预检 pass on evidence it has not read yet', () => {
    // `null` is 「the summaries are still being read」. Answering 「not blocked」 on missing
    // evidence is how a safety sequence quietly stops being one.
    const unread = context(
      { ...configured, selectedTables: ['order_item'] },
      [source, target],
      null,
    );
    expect(evaluateStageGate('tables', unread)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.tableConfigurationsUnread,
    });
  });

  it('is Gate 2: an UNSUPPORTED 预检 cannot be approved', () => {
    // `CONTEXT.md` on 预检: 「only `SUPPORTED` may proceed」. The reason names the table and
    // the conclusion — as the enum literal, as everywhere else in DBX — and the three
    // exits, because a constraint that cannot say what would resolve it is a dead end.
    const blocked = context(
      { ...configured, selectedTables: ['order_item', 'order_event'] },
      [source, target],
      [
        configuration(),
        configuration({
          sourceTable: 'order_event',
          preflightConclusion: 'UNSUPPORTED',
          blockingFindingCount: 2,
        }),
      ],
    );
    expect(evaluateStageGate('tables', blocked)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.preflightNotSupported(1, 'order_event', 'UNSUPPORTED'),
    });
    expect(isStageReachable('confirm', blocked)).toBe(false);
  });

  it('is Gate 2: an INCONCLUSIVE 预检 cannot be approved either', () => {
    // The row of the mapping table #30 calls the most important one, stated as a gate:
    // 「无法判定」 is not a softer 「无法迁移」, and it does not pass.
    const blocked = context(
      { ...configured, selectedTables: ['order_item'] },
      [source, target],
      [configuration({ preflightConclusion: 'INCONCLUSIVE' })],
    );
    expect(evaluateStageGate('tables', blocked)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.preflightNotSupported(1, 'order_item', 'INCONCLUSIVE'),
    });
  });

  it('will not let a 预检 that has not concluded stand in for one that has', () => {
    // `null` is 「the scan is still running」. An unknown safety fact is not a satisfied one.
    const running = context(
      { ...configured, selectedTables: ['order_item'] },
      [source, target],
      [configuration({ preflightConclusion: null, contractVersion: 2 })],
    );
    expect(evaluateStageGate('tables', running)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.preflightInFlight(1, 'order_item'),
    });
  });

  it('refuses a SUPPORTED conclusion that still carries a blocking 发现', () => {
    const contradictory = context(
      { ...configured, selectedTables: ['order_item'] },
      [source, target],
      [configuration({ blockingFindingCount: 1 })],
    );
    expect(evaluateStageGate('tables', contradictory)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.preflightBlockingFindings(1, 'order_item'),
    });
  });

  it('names the 预检 before the 表写入契约 when a table fails both', () => {
    // Deciding a mapping does not make an UNSUPPORTED table migratable, so reporting the
    // contract first would send the operator to do work that changes nothing.
    const both = context(
      { ...configured, selectedTables: ['order_item'] },
      [source, target],
      [
        configuration({
          preflightConclusion: 'UNSUPPORTED',
          blockingFindingCount: 1,
          contractVersion: null,
          undecidedMappingExceptionCount: 1,
        }),
      ],
    );
    expect(evaluateStageGate('tables', both)).toEqual({
      blocked: true,
      reason: messages.wizard.gates.preflightNotSupported(1, 'order_item', 'UNSUPPORTED'),
    });
  });

  it('opens 执行确认 once every table in the 迁移范围 carries a 表写入契约', () => {
    const ready = context({ ...configured, selectedTables: ['order_item'] });
    expect(evaluateStageGate('tables', ready).blocked).toBe(false);
    expect(furthestReachableStage(ready)).toBe('confirm');
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
