import { useMemo, useState } from 'react';
import { Button, ContentSwitcher, Link as CarbonLink, Switch, Theme } from '@carbon/react';
import { Link, Outlet, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '@/api/http';
import { useRunProgress } from '@/api/runProgress';
import { EmptyState, ErrorState, LoadingState } from '@/components/ViewState';
import { ConclusionIndicator, migrationRunConclusion } from '@/conclusions';
import type { RunProgressSnapshot, TableMigrationUnit } from '@/contract';
import {
  CancelRunModal,
  RunEventStream,
  RunLogPanel,
  RunProgressMatrix,
  StuckPanel,
  outcomeLabel,
  phaseLabel,
} from '@/features/runs';
import { formatCount, formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';
import { Identifier } from './Identifier';
import { Page } from './Page';

/**
 * 运行监控 — the wizard's fifth stage, seen from the 迁移运行 it observes (#38).
 *
 * It is a route of its own rather than a wizard stage component because a 迁移草稿
 * 「produces no migration run」 (`CONTEXT.md`): what arrives here is a task's execution, and
 * the draft that proposed it has ceased to exist. `src/wizard/stageGates.ts` says the same
 * thing from the other side.
 *
 * Three decisions are load-bearing and are easy to undo by accident:
 *
 *  1. **The page is organised around 表迁移单元** — one row per table, each carrying that
 *     table's own phase, observation, technical result and observation time. **Gate 7**:
 *     boxes, connectors and topics are internal scheduling details and never reach this
 *     screen, so an operator can run a migration without understanding the platform under
 *     it. The two phase literals named after the box are rendered as 等待调度 and
 *     因关联失败而阻塞, and 根因域's two platform values as 迁移平台.
 *  2. **Progress is rendered as observation, not as advance.** ADR-0004 permits progress
 *     observations to be coalesced, so nothing here animates, interpolates or extrapolates:
 *     every number is printed with the instant it was observed at, a lagging row says so,
 *     and the page states the rule in words.
 *  3. **Only the live blocks are g100** (ADR-0014). The progress matrix, the event stream
 *     and the log sit inside one inline `Theme`; the identity, the 卡死 diagnosis, the
 *     filter and the cancel control stay in the page's g10. A fully dark monitoring page
 *     was explicitly rejected.
 */

/**
 * 单表证据, over this page and with its own URL (#39).
 *
 * It is a **sibling of the page rather than a child of it**, and rendered at the same
 * position in every branch below. That is not cosmetic: the run's own read moves from
 * pending to observed while a drawer may already be open, and an outlet that changed
 * position between those branches would unmount and remount the drawer — re-fetching its
 * evidence and throwing away focus — every time the run page changed state underneath it.
 */
function EvidenceOutlet() {
  return <Outlet />;
}

type UnitFilter = 'all' | 'failed' | 'stuck';

const unitFilters: readonly UnitFilter[] = ['all', 'failed', 'stuck'];

function filteredUnits(
  snapshot: RunProgressSnapshot,
  filter: UnitFilter,
): readonly TableMigrationUnit[] {
  if (filter === 'failed') {
    return snapshot.units.filter((unit) => unit.outcome === 'FAILED');
  }
  if (filter === 'stuck') {
    // 卡死 names the tables that stopped and the tables stopped alongside them. Both are
    // what an operator filtering for it needs to see, and each is shown as what it is.
    const ids = [
      ...(snapshot.stuck?.stalledUnitIds ?? []),
      ...(snapshot.stuck?.blockedUnitIds ?? []),
    ];
    return snapshot.units.filter((unit) => ids.includes(unit.id));
  }
  return snapshot.units;
}

interface Totals {
  readonly read: number;
  readonly written: number;
  readonly baseline: number;
}

function totalsOf(units: readonly TableMigrationUnit[]): Totals {
  return units.reduce<Totals>(
    (running, unit) => ({
      read: running.read + (unit.progress?.sourceRowsRead ?? 0),
      written: running.written + (unit.progress?.targetRowsWritten ?? 0),
      baseline: running.baseline + (unit.sourceBaselineRowCount ?? 0),
    }),
    { read: 0, written: 0, baseline: 0 },
  );
}

/** How many tables stand in each phase, and how many reached each technical result. */
function distributionOf(units: readonly TableMigrationUnit[]): readonly string[] {
  const counts = new Map<string, number>();
  for (const unit of units) {
    const label =
      unit.phase === 'TERMINAL' && unit.outcome !== null
        ? outcomeLabel(unit.outcome)
        : phaseLabel(unit.phase);
    counts.set(label, (counts.get(label) ?? 0) + 1);
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'en'))
    .map(([label, count]) => messages.run.matrix.countOf(label, count));
}

export function MigrationRunPage() {
  const { runId = '' } = useParams();
  const navigate = useNavigate();
  const progress = useRunProgress(runId);
  const [filter, setFilter] = useState<UnitFilter>('all');
  const [cancelOpen, setCancelOpen] = useState(false);

  const snapshot = progress.snapshot;
  const units = useMemo(
    () => (snapshot === null ? [] : filteredUnits(snapshot, filter)),
    [snapshot, filter],
  );
  const totals = useMemo(() => totalsOf(snapshot?.units ?? []), [snapshot]);

  if (progress.pending) {
    return (
      <>
        <Page title={messages.run.title}>
          <LoadingState description={messages.run.loading} />
        </Page>
        <EvidenceOutlet />
      </>
    );
  }

  if (snapshot === null) {
    // A run that is not there is not a failure of this page: the link names something the
    // current world does not contain, and saying so is more use than a retry that cannot
    // succeed. Anything else is a read that failed and can be tried again.
    const missing = progress.error instanceof ApiError && progress.error.status === 404;
    return (
      <>
        <Page title={messages.run.title}>
          {missing ? (
            <EmptyState title={messages.run.notFound.title} body={messages.run.notFound.body} />
          ) : (
            <ErrorState
              title={messages.run.error.title}
              body={messages.run.error.body}
              onRetry={progress.refresh}
            />
          )}
        </Page>
        <EvidenceOutlet />
      </>
    );
  }

  const run = snapshot.run;
  const cancellable = run.endedAt === null;

  return (
    <>
      <Page
        title={messages.run.title}
        lead={messages.run.lead}
        width="full"
        actions={
          cancellable ? (
            <Button kind="danger--tertiary" onClick={() => setCancelOpen(true)}>
              {messages.run.cancel.action}
            </Button>
          ) : (
            <span className="dbx-run__muted">{messages.run.cancel.unavailable}</span>
          )
        }
      >
        <section className="dbx-run__identity" aria-label={messages.run.statusLabel}>
          <p>
            {messages.run.runLabel} <Identifier>{run.id}</Identifier>
          </p>
          <p>
            <ConclusionIndicator
              conclusion={migrationRunConclusion(run.status)}
              label={messages.tasks.runStatuses[run.status]}
            />
          </p>
          <p>
            {messages.run.sourceLabel} <Identifier>{run.sourceDatabase}</Identifier>
            {' → '}
            {messages.run.targetLabel} <Identifier>{run.targetSchema}</Identifier>
          </p>
          <p>
            {messages.run.startedAt} <Identifier>{formatTimestamp(run.startedAt)}</Identifier>
            {' · '}
            {messages.run.endedAt}{' '}
            {run.endedAt === null ? (
              messages.run.stillRunning
            ) : (
              <Identifier>{formatTimestamp(run.endedAt)}</Identifier>
            )}
          </p>
          <p>
            {messages.run.freezeLabel}{' '}
            {messages.run.freezeSummary(
              run.writeFreeze.accountableOperator,
              formatTimestamp(run.writeFreeze.expiresAt),
            )}
          </p>
          <p>
            <CarbonLink as={Link} to={paths.migrationTaskRuns(run.taskId)}>
              {messages.run.backAction}
            </CarbonLink>
            {' · '}
            {/* 校验报告 is the stage after this one (#40), and it belongs to this run. */}
            <CarbonLink as={Link} to={paths.validationReport(run.id)}>
              {messages.validation.openAction}
            </CarbonLink>
          </p>
        </section>

        {/*
          The rule, in words, beside the numbers it governs. A DBA who has learned to read a
          frozen bar as 「卡住了」 is misled by an interface that animates one, and equally
          misled by one that freezes without saying that an observation has simply not
          arrived yet.
        */}
        <p className="dbx-run__notice">{messages.run.observationNotice}</p>
        <p className="dbx-run__notice">
          {messages.run.observedAt(formatTimestamp(snapshot.observedAt))}
          {' · '}
          {messages.run.matrix.totals(
            formatCount(totals.read),
            formatCount(totals.written),
            formatCount(totals.baseline),
          )}
        </p>
        <p className="dbx-run__notice">
          {messages.run.matrix.phaseHeading}：{distributionOf(snapshot.units).join(' · ')}
        </p>
        {snapshot.unitTotalCount > snapshot.units.length ? (
          <p className="dbx-run__notice">
            {messages.run.matrix.bounded(snapshot.units.length, snapshot.unitTotalCount)}
          </p>
        ) : null}

        {snapshot.stuck === null ? null : <StuckPanel stuck={snapshot.stuck} snapshot={snapshot} />}

        {/*
          The filter is a form control, so it stays in the page's g10: ADR-0014 rejected a
          fully dark monitoring page precisely because it would darken this kind of control.
        */}
        <div className="dbx-run__filters">
          <ContentSwitcher
            aria-label={messages.run.filters.heading}
            selectedIndex={unitFilters.indexOf(filter)}
            onChange={({ name }) => setFilter((name as UnitFilter | undefined) ?? 'all')}
          >
            <Switch name="all" text={messages.run.filters.all} />
            <Switch name="failed" text={messages.run.filters.failed} />
            <Switch name="stuck" text={messages.run.filters.stuck} />
          </ContentSwitcher>
        </div>

        {/*
          ADR-0014: 「Only the live blocks inside run monitoring — the progress matrix, the
          event stream, and logs — are wrapped in an inline g100 theme.」 Exactly these three,
          and nothing above or below them.
        */}
        <Theme theme="g100" className="dbx-run__live">
          <RunProgressMatrix
            snapshot={snapshot}
            units={units}
            filterActive={filter !== 'all'}
            // 单表证据 has its own URL (#39): activating a row is a navigation, built
            // through `paths` so the active scenario travels with it (D25).
            onRowActivate={(unit) => void navigate(paths.tableMigrationUnit(runId, unit.id))}
          />
          <RunEventStream snapshot={snapshot} />
          <RunLogPanel snapshot={snapshot} />
        </Theme>

        <CancelRunModal
          runId={run.id}
          open={cancelOpen}
          onClose={() => setCancelOpen(false)}
          onRequested={progress.refresh}
        />
      </Page>
      <EvidenceOutlet />
    </>
  );
}
