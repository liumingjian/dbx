import { useMemo, useState } from 'react';
import { Select, SelectItem } from '@carbon/react';
import { useNavigate } from 'react-router-dom';
import { useMigrationTasks } from '@/api/migrationTasks';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import { ConclusionIndicator, migrationRunConclusion } from '@/conclusions';
import type { MigrationRunStatus, MigrationTask } from '@/contract';
import {
  databasePairLabel,
  databasePairsOf,
  filterMigrationTasks,
  isMigrationTaskFilterActive,
  noMigrationTaskFilter,
  type ApprovedWithin,
  type MigrationTaskFilter,
} from '@/features/tasks/filters';
import { formatTimestamp } from '@/format/display';
import { MigrationDraftsSection } from '@/features/drafts/MigrationDraftsSection';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';
import { Identifier } from './Identifier';
import { Page } from './Page';

/**
 * The migration task list (user stories 14, 15, 20).
 *
 * Every task here is approved, because approval is part of what a migration task *is*
 * (`CONTEXT.md`). Unapproved work is a 迁移草稿 and appears separately, which #34 adds.
 *
 * The page is full-bleed (lead decision D7): both tables here carry eight columns at 32px
 * row height, and reading width would put them behind a horizontal scrollbar for no gain.
 *
 * The latest run's status is rendered in `CONTEXT.md`'s wording beside an indicator taken
 * from the conclusion mapping module, never as a colour alone. The three statuses that end
 * in completion are worded apart — 全部完成, 完成，有失败, 完成，已接受风险 — because this
 * column is where a reader decides whether a run needs their attention.
 */
const runStatuses: readonly MigrationRunStatus[] = [
  'PREPARING',
  'RUNNING',
  'ATTENTION_REQUIRED',
  'CANCELLING',
  'COMPLETED',
  'COMPLETED_WITH_FAILURES',
  'COMPLETED_WITH_ACCEPTED_RISK',
  'CANCELLED',
];

const approvedWithinOptions: readonly { value: ApprovedWithin; label: string }[] = [
  { value: 'ANY', label: messages.tasks.filters.any },
  { value: 'LAST_7_DAYS', label: messages.tasks.filters.lastSevenDays },
  { value: 'LAST_30_DAYS', label: messages.tasks.filters.lastThirtyDays },
  { value: 'LAST_90_DAYS', label: messages.tasks.filters.lastNinetyDays },
];

export function MigrationTasksPage() {
  const navigate = useNavigate();
  const query = useMigrationTasks();
  const [filter, setFilter] = useState<MigrationTaskFilter>(noMigrationTaskFilter);

  const tasks = useMemo(() => query.data?.items ?? [], [query.data]);
  // 「now」 comes from the same read as the tasks, never from the browser: the 批准时间 are
  // written on the platform's clock, and a window measured against a different clock is a
  // filter that quietly empties the list.
  const observedAtMs = query.data === undefined ? null : Date.parse(query.data.observedAt);
  const filtered = useMemo(
    () => (observedAtMs === null ? tasks : filterMigrationTasks(tasks, filter, observedAtMs)),
    [tasks, filter, observedAtMs],
  );

  const columns = useMemo<readonly DbxTableColumn<MigrationTask>[]>(
    () => [
      {
        id: 'name',
        header: messages.tasks.columns.name,
        identifying: true,
        width: 260,
        textValue: (task) => task.name,
        renderCell: (task) => task.name,
      },
      {
        id: 'databasePair',
        header: messages.tasks.columns.databasePair,
        width: 220,
        textValue: (task) => databasePairLabel(task.databasePair),
        renderCell: (task) => databasePairLabel(task.databasePair),
      },
      {
        id: 'source',
        header: messages.tasks.columns.source,
        width: 160,
        textValue: (task) => task.sourceDatabase,
        renderCell: (task) => <Identifier>{task.sourceDatabase}</Identifier>,
      },
      {
        id: 'target',
        header: messages.tasks.columns.target,
        width: 160,
        textValue: (task) => task.targetSchema,
        renderCell: (task) => <Identifier>{task.targetSchema}</Identifier>,
      },
      {
        id: 'latestRunStatus',
        header: messages.tasks.columns.latestRunStatus,
        width: 240,
        textValue: (task) =>
          task.latestRunStatus === null
            ? messages.tasks.neverRun
            : messages.tasks.runStatuses[task.latestRunStatus],
        renderCell: (task) =>
          task.latestRunStatus === null ? (
            messages.tasks.neverRun
          ) : (
            <ConclusionIndicator
              conclusion={migrationRunConclusion(task.latestRunStatus)}
              label={messages.tasks.runStatuses[task.latestRunStatus]}
            />
          ),
      },
      {
        id: 'selectedTableCount',
        header: messages.tasks.columns.selectedTableCount,
        width: 120,
        textValue: (task) => String(task.selectedTableCount),
        renderCell: (task) => <Identifier>{task.selectedTableCount}</Identifier>,
      },
      {
        id: 'runCount',
        header: messages.tasks.columns.runCount,
        width: 140,
        textValue: (task) => String(task.runCount),
        renderCell: (task) => <Identifier>{task.runCount}</Identifier>,
      },
      {
        id: 'approvedAt',
        header: messages.tasks.columns.approvedAt,
        width: 200,
        textValue: (task) => formatTimestamp(task.approvedAt),
        renderCell: (task) => <Identifier>{formatTimestamp(task.approvedAt)}</Identifier>,
      },
    ],
    [],
  );

  const toolbar = (
    <div className="dbx-table__filters" role="group" aria-label={messages.tasks.filters.heading}>
      <Select
        id="task-filter-status"
        size="sm"
        labelText={messages.tasks.filters.status}
        value={filter.status}
        onChange={(event) =>
          setFilter((current) => ({
            ...current,
            status: event.target.value as MigrationRunStatus | 'ANY',
          }))
        }
      >
        <SelectItem value="ANY" text={messages.tasks.filters.any} />
        {runStatuses.map((status) => (
          <SelectItem key={status} value={status} text={messages.tasks.runStatuses[status]} />
        ))}
      </Select>
      <Select
        id="task-filter-pair"
        size="sm"
        labelText={messages.tasks.filters.databasePair}
        value={filter.databasePair}
        onChange={(event) =>
          setFilter((current) => ({ ...current, databasePair: event.target.value }))
        }
      >
        <SelectItem value="ANY" text={messages.tasks.filters.any} />
        {databasePairsOf(tasks).map((pair) => (
          <SelectItem key={pair} value={pair} text={pair} />
        ))}
      </Select>
      <Select
        id="task-filter-approved"
        size="sm"
        labelText={messages.tasks.filters.approvedWithin}
        value={filter.approvedWithin}
        onChange={(event) =>
          setFilter((current) => ({
            ...current,
            approvedWithin: event.target.value as ApprovedWithin,
          }))
        }
      >
        {approvedWithinOptions.map((option) => (
          <SelectItem key={option.value} value={option.value} text={option.label} />
        ))}
      </Select>
    </div>
  );

  return (
    <Page title={messages.tasks.title} lead={messages.tasks.lead} width="full">
      <DbxTable
        label={messages.tasks.listLabel}
        columns={columns}
        rows={filtered}
        rowId={(task) => task.id}
        onRowActivate={(task) => navigate(paths.migrationTaskRuns(task.id))}
        loading={query.isPending}
        loadingDescription={messages.tasks.loading}
        error={
          query.isError
            ? {
                title: messages.tasks.error.title,
                body: messages.tasks.error.body,
                onRetry: () => void query.refetch(),
              }
            : null
        }
        empty={{ title: messages.tasks.empty.title, body: messages.tasks.empty.body }}
        filterActive={isMigrationTaskFilterActive(filter)}
        densityPreferenceKey="migration-tasks"
        toolbar={toolbar}
      />
      {/* 迁移草稿 keep their own list. An unapproved working set is not a migration task in
          some earlier state — approval is part of what a migration task is — so it never
          appears as a row above. */}
      <MigrationDraftsSection />
    </Page>
  );
}
