import type { MigrationRun, MigrationRunStatus, MigrationTask } from '@/contract';
import type { ControllableClock } from '../clock';
import type { SeedPlan } from '../scenarios';

/**
 * Seeded migration tasks and their migration runs.
 *
 * A migration task is by definition user-approved (`CONTEXT.md`), so every task here has
 * an approval and at least one migration run; unapproved work is a 迁移草稿 and lives in
 * the store's draft list instead. Reruns are modelled as what ADR-0013 says they are —
 * additional immutable runs whose selected scope is narrower than the task's — rather than
 * as a status that changes on an existing run.
 *
 * Timestamps are offsets from the scenario clock, never from wall-clock time, so the same
 * scenario renders the same screen twice.
 */

interface TaskPlan {
  readonly id: string;
  readonly name: string;
  readonly sourceConnectionId: string;
  readonly sourceDatabase: string;
  readonly targetConnectionId: string;
  readonly targetSchema: string;
  readonly approvedBy: string;
  /** How long before "now" the task was approved, in hours. */
  readonly approvedHoursAgo: number;
  readonly selectedTableCount: number;
  /** One entry per migration run, most recent last. */
  readonly runs: readonly {
    readonly status: MigrationRunStatus;
    readonly startedHoursAgo: number;
    readonly selectedTableCount: number;
    readonly excludedTableCount: number;
    /**
     * Whether this run is a 重新迁移 of the run before it.
     *
     * Seeded rather than only reachable by performing one, because 「迁移任务下并列展示
     * 历次迁移运行及其结论」 (#41) is a state the history has to be able to *show*: a
     * reviewer opening a task should see how many rounds it took without first having to
     * start a round themselves.
     */
    readonly remigrationOfPreviousRun?: boolean;
  }[];
}

const hour = 60 * 60 * 1000;
const minute = 60 * 1000;

/**
 * Every migration run status appears at least once, because a list in which everything
 * succeeded proves nothing about how a failed or cancelled run reads.
 */
const taskPlans: readonly TaskPlan[] = [
  {
    id: 'task-orders-analytics',
    name: '订单库迁移至分析库',
    sourceConnectionId: 'conn-mysql-orders',
    sourceDatabase: 'orders',
    targetConnectionId: 'conn-pg-analytics',
    targetSchema: 'orders',
    approvedBy: 'zhang.wei',
    approvedHoursAgo: 120,
    selectedTableCount: 1164,
    runs: [
      {
        status: 'COMPLETED_WITH_FAILURES',
        startedHoursAgo: 119,
        selectedTableCount: 1164,
        excludedTableCount: 36,
      },
      // 18 tables out of 1164: the 重新迁移 of what the first run left undetermined. Its
      // report names that scope and does not present itself as a whole-task success.
      {
        status: 'COMPLETED',
        startedHoursAgo: 96,
        selectedTableCount: 18,
        excludedTableCount: 0,
        remigrationOfPreviousRun: true,
      },
    ],
  },
  {
    id: 'task-billing-analytics',
    name: '计费库迁移至分析库',
    sourceConnectionId: 'conn-mysql-billing',
    sourceDatabase: 'billing',
    targetConnectionId: 'conn-pg-analytics',
    targetSchema: 'billing',
    approvedBy: 'li.na',
    approvedHoursAgo: 72,
    selectedTableCount: 412,
    runs: [
      {
        status: 'ATTENTION_REQUIRED',
        startedHoursAgo: 5,
        selectedTableCount: 412,
        excludedTableCount: 4,
      },
    ],
  },
  {
    id: 'task-orders-staging',
    name: '订单库迁移至预发分析库',
    sourceConnectionId: 'conn-mysql-orders',
    sourceDatabase: 'orders',
    targetConnectionId: 'conn-pg-staging',
    targetSchema: 'orders_staging',
    approvedBy: 'zhang.wei',
    approvedHoursAgo: 30,
    selectedTableCount: 96,
    runs: [
      {
        status: 'CANCELLED',
        startedHoursAgo: 29,
        selectedTableCount: 96,
        excludedTableCount: 0,
      },
      {
        status: 'RUNNING',
        startedHoursAgo: 1,
        selectedTableCount: 96,
        excludedTableCount: 0,
      },
    ],
  },
  {
    id: 'task-billing-staging',
    name: '计费库迁移至预发分析库',
    sourceConnectionId: 'conn-mysql-billing',
    sourceDatabase: 'billing',
    targetConnectionId: 'conn-pg-staging',
    targetSchema: 'billing_staging',
    approvedBy: 'wang.lei',
    approvedHoursAgo: 800,
    selectedTableCount: 55,
    runs: [
      {
        status: 'COMPLETED_WITH_ACCEPTED_RISK',
        startedHoursAgo: 799,
        selectedTableCount: 55,
        excludedTableCount: 2,
      },
    ],
  },
  {
    id: 'task-orders-refund',
    name: '退款域迁移至分析库',
    sourceConnectionId: 'conn-mysql-orders',
    sourceDatabase: 'orders',
    targetConnectionId: 'conn-pg-analytics',
    targetSchema: 'refund',
    approvedBy: 'li.na',
    approvedHoursAgo: 2000,
    selectedTableCount: 27,
    runs: [
      {
        status: 'COMPLETED',
        startedHoursAgo: 1999,
        selectedTableCount: 27,
        excludedTableCount: 0,
      },
    ],
  },
];

/**
 * The recorded statuses of a 迁移运行 that has come to rest.
 *
 * The seeded history states *that* a run finished; **when** it finished is derived from
 * its own plan by the store. `ATTENTION_REQUIRED` is deliberately absent: a 卡死 run has
 * stopped moving but has not ended — DBX preserves the target data and the diagnostic
 * evidence and waits for a decision — so it has no end time to state.
 */
const endedRunStatuses: ReadonlySet<MigrationRunStatus> = new Set([
  'COMPLETED',
  'COMPLETED_WITH_FAILURES',
  'COMPLETED_WITH_ACCEPTED_RISK',
  'CANCELLED',
]);

export function hasSeededRunEnded(status: MigrationRunStatus): boolean {
  return endedRunStatuses.has(status);
}

export interface SeededMigrationTasks {
  readonly tasks: readonly MigrationTask[];
  readonly runs: readonly MigrationRun[];
}

export function seedMigrationTasks(plan: SeedPlan, clock: ControllableClock): SeededMigrationTasks {
  if (plan.migrationTasks === 'none') {
    return { tasks: [], runs: [] };
  }

  const now = clock.now();
  const at = (millisecondsAgo: number) => new Date(now - millisecondsAgo).toISOString();

  const tasks: MigrationTask[] = [];
  const runs: MigrationRun[] = [];

  for (const taskPlan of taskPlans) {
    const taskRuns: MigrationRun[] = taskPlan.runs.map((runPlan, index) => {
      const startedAt = at(runPlan.startedHoursAgo * hour);
      const previousRunId = `${taskPlan.id}-run-${index}`;
      return {
        id: `${taskPlan.id}-run-${index + 1}`,
        taskId: taskPlan.id,
        status: runPlan.status,
        startedAt,
        // Left open here on purpose. A run that has come to rest is closed by the store,
        // at the quantum its **own plan** comes to rest at (`runPlanEndQuantum`), so the
        // recorded 结束时间 and the projection that replays the plan cannot state
        // different things. See `hasSeededRunEnded` below.
        endedAt: null,
        sourceConnectionId: taskPlan.sourceConnectionId,
        sourceDatabase: taskPlan.sourceDatabase,
        targetConnectionId: taskPlan.targetConnectionId,
        targetSchema: taskPlan.targetSchema,
        writeFreeze: {
          accountableOperator: taskPlan.approvedBy,
          confirmedAt: startedAt,
          // A write freeze is time-bounded and has an accountable operator; `CONTEXT.md`
          // lists "permanent checkbox" under its `_Avoid_`, so an expiry always exists.
          expiresAt: at(runPlan.startedHoursAgo * hour - 8 * hour),
          scope: taskPlan.sourceDatabase,
          changeReference: `CHG-${taskPlan.id.slice(-4).toUpperCase()}-${index + 1}`,
          declaredBrokenAt: null,
        },
        sourceBaseline: {
          capturedAt: startedAt,
          entries: [],
        },
        selectedTableCount: runPlan.selectedTableCount,
        excludedTableCount: runPlan.excludedTableCount,
        cancellationRequestedAt:
          runPlan.status === 'CANCELLED' || runPlan.status === 'CANCELLING'
            ? at(runPlan.startedHoursAgo * hour - 6 * minute)
            : null,
        origin:
          runPlan.remigrationOfPreviousRun === true && index > 0
            ? { kind: 'REMIGRATION', ofRunId: previousRunId }
            : { kind: 'INITIAL' },
        // Every run tests its connections for itself before it starts (ADR-0006). These
        // are this run's readings, taken at its own start.
        establishedEvidence: {
          connectionChecks: [
            {
              role: 'SOURCE',
              connectionId: taskPlan.sourceConnectionId,
              outcome: 'SUCCEEDED',
              checkedAt: startedAt,
            },
            {
              role: 'TARGET',
              connectionId: taskPlan.targetConnectionId,
              outcome: 'SUCCEEDED',
              checkedAt: startedAt,
            },
          ],
          // Per-table readings are not retained for the seeded history, exactly as its
          // 源基线 entries are not: the fixture states what it knows and nothing more.
          tables: [],
        },
      };
    });

    const latestRun = taskRuns[taskRuns.length - 1];
    runs.push(...taskRuns);
    tasks.push({
      id: taskPlan.id,
      name: taskPlan.name,
      databasePair: { sourceDialect: 'MYSQL_8_0', targetDialect: 'POSTGRESQL_15' },
      sourceConnectionId: taskPlan.sourceConnectionId,
      sourceDatabase: taskPlan.sourceDatabase,
      targetConnectionId: taskPlan.targetConnectionId,
      targetSchema: taskPlan.targetSchema,
      approvedAt: at(taskPlan.approvedHoursAgo * hour),
      approvedBy: taskPlan.approvedBy,
      selectedTableCount: taskPlan.selectedTableCount,
      runCount: taskRuns.length,
      latestRunId: latestRun?.id ?? null,
      latestRunStatus: latestRun?.status ?? null,
    });
  }

  return { tasks, runs };
}
