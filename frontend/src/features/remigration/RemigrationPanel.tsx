import { useEffect, useMemo, useState } from 'react';
import { Button } from '@carbon/react';
import { useNavigate } from 'react-router-dom';
import { useRemigrationOffer, useStartRemigration } from '@/api/remigration';
import { DbxTable, type DbxTableColumn, useDbxSelection } from '@/components/DbxTable';
import { ConclusionIndicator } from '@/conclusions';
import type { RemigrationCandidate, RemigrationOffer } from '@/contract';
import { outcomeLabel } from '@/features/runs';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { paths } from '@/routes/paths';
import { StartRemigrationModal } from './StartRemigrationModal';

/**
 * 重新迁移, offered from the 校验报告 (#41).
 *
 * The panel is built around what it must *not* let an operator believe:
 *
 *  1. **that anything is being retried.** Confirming creates a new 迁移运行 with its own
 *     identifier, scope, 写冻结 and 源基线, and the copy says so before the dialog opens.
 *     `CONTEXT.md` lists 「retry in place」 under 迁移运行's `_Avoid_`.
 *  2. **that a table which never migrated failed.** 预检排除项 are not candidates and are
 *     not listed here; the panel states that they are absent and why, because a reader who
 *     cannot find a table needs to be told which kind of absence it is.
 *  3. **that the new run will cover everything.** The scope of the new run is what the
 *     operator ticks, and both the earlier run's count and the 迁移任务's are printed
 *     beside it — ADR-0006: 「its report names that scope and does not present a partial
 *     rerun as a new whole-task success」.
 *
 * The candidate list is the platform's, not this component's: which tables are still
 * undetermined and what their 预检 says *now* are server-side readings, assembled in one
 * request for the reason 执行确认's summary is.
 */
interface RemigrationPanelProps {
  readonly runId: string;
}

function candidateColumns(): readonly DbxTableColumn<RemigrationCandidate>[] {
  const copy = messages.remigration;
  return [
    {
      id: 'sourceTable',
      header: copy.columns.sourceTable,
      identifying: true,
      width: 220,
      textValue: (candidate) => candidate.sourceTable,
      renderCell: (candidate) => candidate.sourceTable,
    },
    {
      id: 'targetTable',
      header: copy.columns.targetTable,
      width: 200,
      textValue: (candidate) => candidate.targetTable,
      renderCell: (candidate) => candidate.targetTable,
    },
    {
      // Quoted from the earlier 校验执行 and rendered with the same indicator the report
      // uses. A candidate carries the conclusion it had; nothing here rewrites it.
      id: 'conclusion',
      header: copy.columns.conclusion,
      width: 200,
      textValue: (candidate) => messages.conclusion.labels[candidate.conclusion],
      renderCell: (candidate) => <ConclusionIndicator conclusion={candidate.conclusion} />,
    },
    {
      id: 'unitOutcome',
      header: copy.columns.unitOutcome,
      width: 220,
      textValue: (candidate) =>
        candidate.unitOutcome === null ? copy.noOutcome : outcomeLabel(candidate.unitOutcome),
      renderCell: (candidate) =>
        candidate.unitOutcome === null ? copy.noOutcome : outcomeLabel(candidate.unitOutcome),
    },
    {
      // Read again, now: an earlier run's 预检 says nothing about whether this table may
      // go today (ADR-0006).
      id: 'preflight',
      header: copy.columns.preflight,
      width: 180,
      textValue: (candidate) => messages.conclusion.labels[candidate.preflightConclusion],
      renderCell: (candidate) => (
        <ConclusionIndicator conclusion={candidate.preflightConclusion} />
      ),
    },
    {
      id: 'contractVersion',
      header: copy.columns.contractVersion,
      width: 160,
      textValue: (candidate) =>
        candidate.contractVersion === null
          ? copy.ineligible.noContract
          : `v${candidate.contractVersion}`,
      renderCell: (candidate) =>
        candidate.contractVersion === null
          ? copy.ineligible.noContract
          : `v${candidate.contractVersion}`,
    },
  ];
}

function CandidateSelection({ offer }: { offer: RemigrationOffer }) {
  const copy = messages.remigration;
  const navigate = useNavigate();
  const start = useStartRemigration(offer.runId);
  const [confirming, setConfirming] = useState(false);

  const candidateIds = useMemo(
    () => offer.candidates.map((candidate) => candidate.unitId),
    [offer.candidates],
  );
  const selection = useDbxSelection(candidateIds);
  const columns = useMemo(() => candidateColumns(), []);
  const selectedIds = useMemo(
    () => candidateIds.filter((id) => selection.isSelected(id)),
    [candidateIds, selection],
  );

  // A dialog left open across a change of offer would be confirming a scope that has moved.
  useEffect(() => {
    if (selectedIds.length === 0) {
      setConfirming(false);
    }
  }, [selectedIds.length]);

  return (
    <>
      <p>{copy.scopeNotice(offer.runSelectedTableCount, offer.taskSelectedTableCount)}</p>
      <p>{copy.candidatesStatement}</p>
      <p className="dbx-validation__notice">{copy.exclusionsNotice}</p>
      <DbxTable
        label={copy.listLabel}
        columns={columns}
        rows={offer.candidates}
        rowId={(candidate) => candidate.unitId}
        selection={{ model: selection, unitLabel: copy.unitLabel }}
        densityPreferenceKey="remigration-candidates"
        empty={copy.empty}
      />
      <p>
        {copy.selectedCount(selectedIds.length)}
        {selectedIds.length === 0 ? ` · ${copy.selectFirst}` : ''}
      </p>
      <Button
        kind="tertiary"
        disabled={selectedIds.length === 0}
        onClick={() => {
          start.reset();
          setConfirming(true);
        }}
      >
        {copy.action}
      </Button>
      {confirming ? (
        <StartRemigrationModal
          sourceDatabase={offer.sourceDatabase}
          tableCount={selectedIds.length}
          pending={start.isPending}
          failed={start.isError}
          onCancel={() => setConfirming(false)}
          onConfirm={(writeFreeze) =>
            start.mutate(
              { unitIds: selectedIds, writeFreeze },
              {
                onSuccess: (started) => {
                  setConfirming(false);
                  // Straight to the record that did not exist a moment ago. The run this
                  // was started from is left exactly where it was.
                  void navigate(paths.migrationRun(started.run.id));
                },
              },
            )
          }
        />
      ) : null}
    </>
  );
}

export function RemigrationPanel({ runId }: RemigrationPanelProps) {
  const copy = messages.remigration;
  const offer = useRemigrationOffer(runId);

  return (
    <section className="dbx-validation__block" aria-label={copy.heading}>
      <h3 className="dbx-validation__heading">{copy.heading}</h3>
      <p>{copy.statement}</p>
      {offer.isPending ? <p>{copy.loading}</p> : null}
      {offer.isError ? (
        <p role="alert">
          {copy.error.title}
          {' · '}
          {copy.error.body}
        </p>
      ) : null}
      {offer.data === undefined ? null : offer.data.candidates.length === 0 &&
        offer.data.ineligible.length === 0 ? (
        <>
          <p>{copy.empty.title}</p>
          <p>{copy.empty.body}</p>
          <p className="dbx-validation__notice">{copy.exclusionsNotice}</p>
        </>
      ) : (
        <CandidateSelection offer={offer.data} />
      )}
      {offer.data === undefined || offer.data.ineligible.length === 0 ? null : (
        <>
          <h3 className="dbx-validation__heading">{copy.ineligible.heading}</h3>
          <p>{copy.ineligible.statement}</p>
          <ul className="dbx-validation__list">
            {offer.data.ineligible.map((candidate) => (
              <li key={candidate.unitId}>
                {copy.ineligible.of(
                  candidate.sourceTable,
                  messages.conclusion.labels[candidate.preflightConclusion],
                )}
                {candidate.contractVersion === null ? ` · ${copy.ineligible.noContract}` : ''}
                {' · '}
                {formatTimestamp(candidate.preflightConcludedAt)}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
