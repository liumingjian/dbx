import type { RunProgressSnapshot } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';
import { Identifier } from '@/pages/Identifier';
import { runEventLabel } from './runVocabulary';

/**
 * 事件流 — this run's timeline, most recent first (#38).
 *
 * A stream rather than a data table: it is read downwards from now, it is never sorted or
 * paged, and nothing in it is selected. ADR-0004 requires one table's timeline to be
 * readable 「without treating box history as that table's business state」, which is why
 * every entry names either one 表迁移单元 or the whole 迁移运行 — and never a scheduling
 * group, a connector or a topic.
 *
 * The list is bounded by the server and says so, with the true total beside the bound
 * (lead decision D24). A silently truncated timeline is worse than a short one.
 */
export function RunEventStream({ snapshot }: { readonly snapshot: RunProgressSnapshot }) {
  const copy = messages.run.events;

  return (
    <section className="dbx-run__panel" aria-label={copy.heading}>
      <h3 className="dbx-run__panel-title">{copy.heading}</h3>
      <p className="dbx-run__panel-lead">{copy.lead}</p>
      <ol className="dbx-run__events">
        {snapshot.events.map((event) => (
          <li key={event.id} className="dbx-run__event">
            <Identifier>{formatTimestamp(event.occurredAt)}</Identifier>
            <span className="dbx-run__event-subject">
              {event.sourceTable === null ? copy.wholeRun : event.sourceTable}
            </span>
            <span>{runEventLabel(event)}</span>
          </li>
        ))}
      </ol>
      {snapshot.eventTotalCount > snapshot.events.length ? (
        <p className="dbx-run__bound">
          {copy.bounded(snapshot.events.length, snapshot.eventTotalCount)}
        </p>
      ) : null}
    </section>
  );
}
