import { useMemo } from 'react';
import { Link as CarbonLink } from '@carbon/react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMigrationRunsOfTask, useMigrationTask } from '@/api/migrationTasks';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import { ConclusionIndicator, migrationRunConclusion } from '@/conclusions';
import type { MigrationRun } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';
import { Identifier } from './Identifier';
import { Page } from './Page';

/**
 * One migration task's run history (user story 15).
 *
 * A rerun is a new migration run with its own selected scope, never a retry in place
 * (`CONTEXT.md`, ADR-0013), so this page lists attempts rather than showing one record
 * whose status changed. The selected and excluded table counts are on every row for the
 * same reason: two runs of the same task can legitimately cover different tables, and
 * hiding that would make the history look like repetition.
 */
export function MigrationTaskRunsPage() {
  const { taskId = '' } = useParams();
  const navigate = useNavigate();
  const task = useMigrationTask(taskId);
  const runs = useMigrationRunsOfTask(taskId);

  const columns = useMemo<readonly DbxTableColumn<MigrationRun>[]>(
    () => [
      {
        id: 'id',
        header: messages.tasks.runs.columns.id,
        identifying: true,
        width: 260,
        textValue: (run) => run.id,
        renderCell: (run) => <Identifier>{run.id}</Identifier>,
      },
      {
        id: 'status',
        header: messages.tasks.runs.columns.status,
        width: 240,
        textValue: (run) => messages.tasks.runStatuses[run.status],
        renderCell: (run) => (
          <ConclusionIndicator
            conclusion={migrationRunConclusion(run.status)}
            label={messages.tasks.runStatuses[run.status]}
          />
        ),
      },
      {
        id: 'startedAt',
        header: messages.tasks.runs.columns.startedAt,
        width: 190,
        textValue: (run) => formatTimestamp(run.startedAt),
        renderCell: (run) => <Identifier>{formatTimestamp(run.startedAt)}</Identifier>,
      },
      {
        id: 'endedAt',
        header: messages.tasks.runs.columns.endedAt,
        width: 190,
        textValue: (run) =>
          run.endedAt === null ? messages.tasks.runs.inFlight : formatTimestamp(run.endedAt),
        renderCell: (run) =>
          run.endedAt === null ? (
            messages.tasks.runs.inFlight
          ) : (
            <Identifier>{formatTimestamp(run.endedAt)}</Identifier>
          ),
      },
      {
        id: 'selectedTableCount',
        header: messages.tasks.runs.columns.selectedTableCount,
        width: 120,
        textValue: (run) => String(run.selectedTableCount),
        renderCell: (run) => <Identifier>{run.selectedTableCount}</Identifier>,
      },
      {
        id: 'excludedTableCount',
        header: messages.tasks.runs.columns.excludedTableCount,
        width: 120,
        textValue: (run) => String(run.excludedTableCount),
        renderCell: (run) => <Identifier>{run.excludedTableCount}</Identifier>,
      },
    ],
    [],
  );

  return (
    <Page title={task.data?.name ?? messages.tasks.runs.title} lead={messages.tasks.runs.lead}>
      <p>
        <CarbonLink as={Link} to={paths.migrationTasks}>
          {messages.tasks.runs.backAction}
        </CarbonLink>
      </p>
      <DbxTable
        label={messages.tasks.runs.listLabel}
        columns={columns}
        rows={runs.data ?? []}
        rowId={(run) => run.id}
        onRowActivate={(run) => navigate(paths.migrationRun(run.id))}
        loading={runs.isPending}
        error={
          runs.isError
            ? {
                title: messages.tasks.runs.error.title,
                body: messages.tasks.runs.error.body,
                onRetry: () => void runs.refetch(),
              }
            : null
        }
        empty={{ title: messages.tasks.runs.empty.title, body: messages.tasks.runs.empty.body }}
        densityPreferenceKey="migration-runs"
      />
    </Page>
  );
}
