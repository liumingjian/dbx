import { describe, expect, it } from 'vitest';
import type {
  DatabaseConnection,
  DraftTableConfiguration,
  ExecutionConfirmationSummary,
  MigrationDraft,
  StructuralProofGapStatement,
} from '@/contract';
import { messages } from '@/messages';
import {
  evaluateStageGate,
  furthestReachableStage,
  isStageComplete,
  isStageReachable,
  mayStartMigration,
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
    writeFreeze: null,
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

/** The 执行确认 summary as the server would state it for a scope that is ready to run. */
function summary(gaps: readonly StructuralProofGapStatement[] = []): ExecutionConfirmationSummary {
  return {
    draftId: 'draft-1',
    sourceConnectionId: source.id,
    sourceConnectionName: source.name,
    sourceDatabase: 'orders',
    targetConnectionId: target.id,
    targetConnectionName: target.name,
    targetSchema: 'orders',
    scopeKind: 'SELECTED_TABLES',
    tables: [
      {
        sourceTable: 'order_item',
        targetTable: 'order_item',
        preflightConclusion: 'SUPPORTED',
        contractVersion: 1,
        contractColumnCount: 9,
        largeRecordTable: false,
        prunedColumnCount: 0,
      },
    ],
    excludedTables: [],
    unresolvedFindings: [],
    structuralProof: { provableTableCount: 1 - gaps.length, gaps },
    assembledAt: '2026-09-01T09:00:00.000Z',
  };
}

const freeze = { accountableOperator: 'zhang.wei', durationHours: 8, changeReference: null };

const context = (
  entry: MigrationDraft,
  connections = [source, target],
  tableConfigurations: readonly DraftTableConfiguration[] | null = [configuration()],
  executionSummary: ExecutionConfirmationSummary | null = summary(),
): WizardGateContext => ({
  draft: entry,
  connections,
  tableConfigurations,
  executionSummary,
});

describe('wizard stage gating', () => {
  it('stops a half-configured draft at 连接与数据库', () => {
    const gate = evaluateStageGate('connections', context(draft()));
    expect(gate).toEqual({
      blocked: true,
      reason: {
        code: 'CONNECTIONS_INCOMPLETE' as const,
        text: messages.wizard.gates.connectionsIncomplete,
      },
    });
    expect(furthestReachableStage(context(draft()))).toBe('connections');
  });

  it('stops a draft whose chosen 数据库连接 is no longer usable, and names it', () => {
    const gate = evaluateStageGate('connections', context(configured, [source, failingTarget]));
    expect(gate).toEqual({
      blocked: true,
      reason: {
        code: 'CONNECTION_UNUSABLE' as const,
        text: messages.wizard.gates.connectionUnusable(failingTarget.name, 'FAILED'),
      },
    });
  });

  it('opens 迁移范围 once the pair and the databases are chosen', () => {
    expect(evaluateStageGate('connections', context(configured)).blocked).toBe(false);
    expect(furthestReachableStage(context(configured))).toBe('scope');
  });

  it('is Gate 1: an empty 迁移范围 cannot advance', () => {
    expect(evaluateStageGate('scope', context(configured))).toEqual({
      blocked: true,
      reason: { code: 'NO_TABLE_SELECTED' as const, text: messages.wizard.gates.noTableSelected },
    });
  });

  it('advances past 迁移范围 once at least one table is in it', () => {
    const withTables = { ...configured, selectedTables: ['order_item'] };
    expect(evaluateStageGate('scope', context(withTables)).blocked).toBe(false);
    expect(isStageReachable('tables', context(withTables))).toBe(true);
    // 执行确认 is where every draft stops: what leaves that stage is 「开始迁移」, which
    // ends the draft, and never 「下一步」.
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

  it('does not move a draft off the stage it asked for while a fact is still being read', () => {
    // Every stage is deep-linkable, and the reads behind the later gates settle after the
    // 迁移草稿 itself does. A redirect during that window would send an operator who typed
    // 执行确认 back to 逐表配置与预检 on nothing but request ordering, and lose the address
    // they asked for. Unread is 「not known yet」, not 「not allowed」.
    const withTables = { ...configured, selectedTables: ['order_item'] };
    const reading = context(withTables, [source, target], null, null);
    expect(furthestReachableStage(reading)).toBe('tables');
    expect(resolveStageEntry('confirm', reading)).toBe('confirm');

    // Nothing is unlocked by waiting: the stage the operator is standing on still refuses.
    expect(evaluateStageGate('confirm', reading)).toEqual({
      blocked: true,
      reason: {
        code: 'EXECUTION_SUMMARY_UNREAD' as const,
        text: messages.wizard.gates.executionSummaryUnread,
      },
    });
    expect(mayStartMigration(reading)).toBe(false);

    // And the moment the fact arrives and does block, the redirect follows.
    const answered = context(
      withTables,
      [source, target],
      [configuration({ preflightConclusion: 'UNSUPPORTED' })],
      null,
    );
    expect(resolveStageEntry('confirm', answered)).toBe('tables');
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
      reason: {
        code: 'CONTRACT_NOT_GENERATED' as const,
        text: messages.wizard.gates.contractNotGenerated(1, 'order_header'),
      },
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
      reason: {
        code: 'TABLE_CONFIGURATIONS_UNREAD' as const,
        text: messages.wizard.gates.tableConfigurationsUnread,
      },
    });
  });

  it('is Gate 2: an UNSUPPORTED 预检 cannot be approved', () => {
    // `CONTEXT.md` on 预检: 「only `SUPPORTED` may proceed」. The reason names the table and
    // the conclusion — in the conclusion's own `_中文_` wording, 不可迁移, never the
    // persisted literal — and the three exits, because a constraint that cannot say what
    // would resolve it is a dead end.
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
      reason: {
        code: 'PREFLIGHT_NOT_SUPPORTED' as const,
        text: messages.wizard.gates.preflightNotSupported(
          1,
          'order_event',
          messages.conclusion.labels.UNSUPPORTED,
        ),
      },
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
      reason: {
        code: 'PREFLIGHT_NOT_SUPPORTED' as const,
        text: messages.wizard.gates.preflightNotSupported(
          1,
          'order_item',
          messages.conclusion.labels.INCONCLUSIVE,
        ),
      },
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
      reason: {
        code: 'PREFLIGHT_IN_FLIGHT' as const,
        text: messages.wizard.gates.preflightInFlight(1, 'order_item'),
      },
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
      reason: {
        code: 'PREFLIGHT_BLOCKING_FINDINGS' as const,
        text: messages.wizard.gates.preflightBlockingFindings(1, 'order_item'),
      },
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
      reason: {
        code: 'PREFLIGHT_NOT_SUPPORTED' as const,
        text: messages.wizard.gates.preflightNotSupported(
          1,
          'order_item',
          messages.conclusion.labels.UNSUPPORTED,
        ),
      },
    });
  });

  it('opens 执行确认 once every table in the 迁移范围 carries a 表写入契约', () => {
    const ready = context({ ...configured, selectedTables: ['order_item'] });
    expect(evaluateStageGate('tables', ready).blocked).toBe(false);
    expect(furthestReachableStage(ready)).toBe('confirm');
  });

  it('is Gate 5: no 写冻结 confirmation, no start', () => {
    // `CONTEXT.md` on 写冻结: 「externally enforced, time-bounded … has an accountable
    // operator and expiry」, with 「permanent checkbox」 under `_Avoid_`. So the sentence
    // names the 责任人 and the 时限, and an unconfirmed freeze blocks exactly as it would
    // if the operator had never reached the stage.
    const ready = context({ ...configured, selectedTables: ['order_item'] });
    expect(evaluateStageGate('confirm', ready)).toEqual({
      blocked: true,
      reason: {
        code: 'WRITE_FREEZE_NOT_CONFIRMED' as const,
        text: messages.wizard.gates.writeFreezeNotConfirmed,
      },
    });
    expect(mayStartMigration(ready)).toBe(false);
  });

  it('is Gate 5: a 写冻结 with no named 责任人 is not a confirmation', () => {
    const blank = context({
      ...configured,
      selectedTables: ['order_item'],
      writeFreeze: { ...freeze, accountableOperator: '  ' },
    });
    expect(evaluateStageGate('confirm', blank)).toEqual({
      blocked: true,
      reason: {
        code: 'WRITE_FREEZE_NOT_CONFIRMED' as const,
        text: messages.wizard.gates.writeFreezeNotConfirmed,
      },
    });
    expect(mayStartMigration(blank)).toBe(false);
  });

  it('is Gate 5: a 写冻结 with no 时限 is the permanent checkbox the glossary rules out', () => {
    const unbounded = context({
      ...configured,
      selectedTables: ['order_item'],
      writeFreeze: { ...freeze, durationHours: 0 },
    });
    expect(evaluateStageGate('confirm', unbounded)).toEqual({
      blocked: true,
      reason: {
        code: 'WRITE_FREEZE_NOT_CONFIRMED' as const,
        text: messages.wizard.gates.writeFreezeNotConfirmed,
      },
    });
  });

  it('is Gate 6: a missing 结构证明 stops the start, and names the table', () => {
    // The frontend cannot perform a 结构证明 — it is a server-side catalog comparison
    // inside the run (lead decision D11) — so what it does is refuse while the summary
    // reports one it cannot be established for.
    const occupied = context(
      { ...configured, selectedTables: ['order_item'], writeFreeze: freeze },
      [source, target],
      [configuration()],
      summary([{ sourceTable: 'order_item', gap: 'TARGET_TABLE_EXISTS' }]),
    );
    expect(evaluateStageGate('confirm', occupied)).toEqual({
      blocked: true,
      reason: {
        code: 'STRUCTURAL_PROOF_MISSING' as const,
        text: messages.wizard.gates.structuralProofMissing(1, 'order_item'),
      },
    });
    expect(mayStartMigration(occupied)).toBe(false);
  });

  it('does not let 执行确认 answer on a summary it has not read yet', () => {
    const unread = context(
      { ...configured, selectedTables: ['order_item'], writeFreeze: freeze },
      [source, target],
      [configuration()],
      null,
    );
    expect(evaluateStageGate('confirm', unread)).toEqual({
      blocked: true,
      reason: {
        code: 'EXECUTION_SUMMARY_UNREAD' as const,
        text: messages.wizard.gates.executionSummaryUnread,
      },
    });
    expect(mayStartMigration(unread)).toBe(false);
  });

  it('lets the migration start once both constraints are satisfied, and still not 下一步', () => {
    // The gate never passes, and that is the shape rather than an omission: what leaves
    // 执行确认 is 「开始迁移」, which ends the draft. 运行监控 belongs to the 迁移运行.
    const ready = context({
      ...configured,
      selectedTables: ['order_item'],
      writeFreeze: freeze,
    });
    expect(evaluateStageGate('confirm', ready)).toEqual({
      blocked: true,
      reason: { code: 'RUN_NOT_STARTED' as const, text: messages.wizard.gates.runNotStarted },
    });
    expect(mayStartMigration(ready)).toBe(true);
    expect(isStageReachable('monitor', ready)).toBe(false);
  });

  it('keeps 运行监控 and 校验报告 out of a draft, because a draft produces no 迁移运行', () => {
    const ready = context({ ...configured, selectedTables: ['order_item'] });
    expect(evaluateStageGate('monitor', ready)).toEqual({
      blocked: true,
      reason: {
        code: 'STAGE_BELONGS_TO_RUN' as const,
        text: messages.wizard.gates.stageBelongsToRun,
      },
    });
    expect(isStageReachable('monitor', ready)).toBe(false);
  });
});
