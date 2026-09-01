import { useMemo } from 'react';
import { DbxTable, type DbxTableColumn } from '@/components/DbxTable';
import { ConclusionIndicator, tableMigrationConclusion } from '@/conclusions';
import type { RunProgressSnapshot, TableMigrationUnit } from '@/contract';
import { formatCount, formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { outcomeLabel, phaseLabel } from './runVocabulary';

/**
 * 进度矩阵 — the run seen as its 表迁移单元, one row each (#38).
 *
 * The organising unit is the table, not the platform: a DBA reads down the column of
 * tables they chose, and every row is that table's own durable record — its phase, its
 * latest observation, its technical result, and when that observation was taken.
 *
 * **Progress is never drawn as smooth advance.** ADR-0004 permits progress observations to
 * be coalesced, so what a row holds is one observation, not a position on a timeline. The
 * bar has no transition and is never animated, the numbers are the observed counts and are
 * never interpolated or extrapolated, and the observation's own instant is printed beside
 * them. A row whose observation is older than the snapshot is marked 观测滞后 — normal, and
 * deliberately worded so it cannot be read as 卡死, which is a separate diagnosis with its
 * own panel.
 */
interface RunProgressMatrixProps {
  readonly snapshot: RunProgressSnapshot;
  readonly units: readonly TableMigrationUnit[];
  readonly filterActive: boolean;
}

function progressPercent(unit: TableMigrationUnit): number {
  const baseline = unit.sourceBaselineRowCount ?? 0;
  if (unit.progress === null || baseline <= 0) {
    return 0;
  }
  return Math.max(0, Math.min(100, Math.round((unit.progress.sourceRowsRead / baseline) * 100)));
}

/** Whether this row's latest observation is older than the snapshot it is shown in. */
function isLagging(unit: TableMigrationUnit, snapshot: RunProgressSnapshot): boolean {
  if (unit.progress === null || unit.phase === 'TERMINAL') {
    return false;
  }
  if (snapshot.stuck?.stalledUnitIds.includes(unit.id) === true) {
    // A stalled unit is not lagging: it has a diagnosis of its own, and saying both would
    // blur the one distinction this screen exists to keep sharp.
    return false;
  }
  return unit.progress.observedAt < snapshot.observedAt;
}

export function RunProgressMatrix({ snapshot, units, filterActive }: RunProgressMatrixProps) {
  const copy = messages.run.matrix;
  // The units the run's 卡死 diagnosis names, memoised so the column definitions below do
  // not rebuild on every render.
  const stalled = useMemo(() => snapshot.stuck?.stalledUnitIds ?? [], [snapshot]);

  const columns = useMemo<readonly DbxTableColumn<TableMigrationUnit>[]>(
    () => [
      {
        id: 'sourceTable',
        header: copy.columns.sourceTable,
        identifying: true,
        width: 240,
        textValue: (unit) => unit.sourceTable,
        renderCell: (unit) => <Identifier>{unit.sourceTable}</Identifier>,
      },
      {
        id: 'targetTable',
        header: copy.columns.targetTable,
        width: 220,
        textValue: (unit) => unit.targetTable,
        renderCell: (unit) => <Identifier>{unit.targetTable}</Identifier>,
      },
      {
        id: 'phase',
        header: copy.columns.phase,
        width: 220,
        textValue: (unit) =>
          stalled.includes(unit.id)
            ? `${phaseLabel(unit.phase)} ${messages.phase.stuck}`
            : phaseLabel(unit.phase),
        // 卡死 is marked beside the phase and **not** in the 技术结果 column, because
        // ADR-0004 makes `STUCK` a diagnosis rather than a table outcome: the table
        // stopped where it was, and DBX has reached no technical result for it at all.
        renderCell: (unit) =>
          stalled.includes(unit.id) ? (
            <span className="dbx-run__phase">
              {phaseLabel(unit.phase)}
              <ConclusionIndicator conclusion="STUCK" label={messages.phase.stuck} />
            </span>
          ) : (
            phaseLabel(unit.phase)
          ),
      },
      {
        id: 'progress',
        header: copy.columns.progress,
        width: 280,
        textValue: (unit) =>
          unit.progress === null
            ? messages.run.noObservation
            : copy.progressOf(
                formatCount(unit.progress.sourceRowsRead),
                formatCount(unit.sourceBaselineRowCount ?? 0),
              ),
        renderCell: (unit) =>
          unit.progress === null ? (
            <span className="dbx-run__muted">{messages.run.noObservation}</span>
          ) : (
            <div className="dbx-run__progress">
              {/*
                A width, and nothing else. No transition, no animation, no easing: an
                observation that jumped by a third jumps by a third on screen, and one that
                did not move does not move. See `_run-monitor.scss`.
              */}
              <div className="dbx-run__progress-track">
                <div
                  className="dbx-run__progress-fill"
                  style={{ width: `${progressPercent(unit)}%` }}
                />
              </div>
              <span className="dbx-run__progress-value">
                {copy.progressOf(
                  formatCount(unit.progress.sourceRowsRead),
                  formatCount(unit.sourceBaselineRowCount ?? 0),
                )}
              </span>
              <span className="dbx-run__muted">
                {copy.writtenLabel} {formatCount(unit.progress.targetRowsWritten)}
              </span>
            </div>
          ),
      },
      {
        id: 'outcome',
        header: copy.columns.outcome,
        width: 220,
        textValue: (unit) =>
          unit.outcome === null ? messages.run.matrix.noOutcome : outcomeLabel(unit.outcome),
        renderCell: (unit) => (
          <ConclusionIndicator
            conclusion={tableMigrationConclusion(unit.outcome)}
            label={
              unit.outcome === null ? messages.run.matrix.noOutcome : outcomeLabel(unit.outcome)
            }
          />
        ),
      },
      {
        id: 'observedAt',
        header: copy.columns.observedAt,
        width: 240,
        textValue: (unit) =>
          unit.progress === null ? '' : formatTimestamp(unit.progress.observedAt),
        renderCell: (unit) =>
          unit.progress === null ? (
            <span className="dbx-run__muted">{messages.run.noObservation}</span>
          ) : (
            <div className="dbx-run__observed">
              <Identifier>{formatTimestamp(unit.progress.observedAt)}</Identifier>
              {isLagging(unit, snapshot) ? (
                <span
                  className="dbx-run__lagging"
                  title={messages.run.laggingDetail(formatTimestamp(unit.progress.observedAt))}
                >
                  {messages.run.lagging}
                </span>
              ) : null}
            </div>
          ),
      },
    ],
    [copy, snapshot, stalled],
  );

  return (
    <DbxTable
      label={copy.heading}
      columns={columns}
      rows={units}
      rowId={(unit) => unit.id}
      filterActive={filterActive}
      empty={{ title: messages.run.empty.title, body: messages.run.empty.body }}
      densityPreferenceKey="run-progress-matrix"
    />
  );
}
