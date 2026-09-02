import type { RunProgressSnapshot, StuckDiagnosis, TableMigrationUnit } from '@/contract';
import { ConclusionIndicator } from '@/conclusions';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { formatMinutes, presentRootCauseDomain } from './runVocabulary';

/**
 * 卡死 — a terminal diagnosis, given its own form (#38).
 *
 * `CONTEXT.md` defines it as 「no observable progress for the configured hard threshold
 * while … everything still reports healthy」, and lists 「slow」, 「failed」 and 「timed
 * out」 under its `_Avoid_`. So this panel is not a stronger shade of a slow row: it is a
 * separate statement, above the live blocks, saying what was measured (the threshold, the
 * last observed movement), what DBX did (stopped advancing, preserved the target data and
 * the diagnostic evidence) and what is left to a person.
 *
 * It also keeps ADR-0004's distinction intact. The stalled table has **no outcome** —
 * 「DBX never invents per-table blame merely to populate an outcome」 — while the tables
 * stopped alongside it carry 因关联失败而阻塞, whose own technical result is undetermined
 * rather than failed. Both are named here, separately, because a DBA deciding what to
 * re-migrate needs to know which is which.
 *
 * It sits in the page's g10, not in the inline g100 of the live blocks: it is a diagnosis
 * to be read once, not a surface that keeps moving.
 */
interface StuckPanelProps {
  readonly stuck: StuckDiagnosis;
  readonly snapshot: RunProgressSnapshot;
}

function namesOf(
  units: readonly TableMigrationUnit[],
  ids: readonly string[],
): readonly TableMigrationUnit[] {
  return units.filter((unit) => ids.includes(unit.id));
}

export function StuckPanel({ stuck, snapshot }: StuckPanelProps) {
  const copy = messages.run.stuck;
  const stalled = namesOf(snapshot.units, stuck.stalledUnitIds);
  const blocked = namesOf(snapshot.units, stuck.blockedUnitIds);

  return (
    // A region rather than an alert: it is a diagnosis to be read and acted on, present
    // from first paint, not something that arrives and needs announcing.
    <section className="dbx-run__stuck" aria-label={copy.heading}>
      <h3 className="dbx-run__panel-title">
        <ConclusionIndicator conclusion="STUCK" label={messages.phase.stuck} size={20} />
      </h3>
      <p>{copy.statement}</p>
      <p>{copy.notSlow}</p>
      <ul className="dbx-run__facts">
        <li>{copy.diagnosedAt(formatTimestamp(stuck.diagnosedAt))}</li>
        <li>{copy.lastProgressAt(formatTimestamp(stuck.lastProgressAt))}</li>
        <li>{copy.threshold(formatMinutes(stuck.thresholdMs))}</li>
        <li>{copy.noProgressFor(formatMinutes(stuck.noProgressForMs))}</li>
        {/*
          根因域: `Kafka Connect` and `Kafka` are presented as the single 迁移平台 domain.
          The specific domain stays in the diagnostic evidence for support use.
        */}
        <li>{copy.rootCauseDomain(presentRootCauseDomain(stuck.rootCauseDomain))}</li>
      </ul>
      <h4 className="dbx-run__subheading">{copy.stalledHeading}</h4>
      <ul className="dbx-run__list">
        {stalled.map((unit) => (
          <li key={unit.id}>
            <Identifier>{unit.sourceTable}</Identifier>
          </li>
        ))}
      </ul>
      {blocked.length > 0 ? (
        <>
          <h4 className="dbx-run__subheading">{copy.blockedHeading}</h4>
          <p>{copy.blockedExplanation}</p>
          <ul className="dbx-run__list">
            {blocked.map((unit) => (
              <li key={unit.id}>
                <Identifier>{unit.sourceTable}</Identifier>
              </li>
            ))}
          </ul>
        </>
      ) : null}
      <p>{copy.nextStep}</p>
    </section>
  );
}
