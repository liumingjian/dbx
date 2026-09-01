import type { RunProgressSnapshot } from '@/contract';
import { formatTimestamp } from '@/format/display';
import { messages } from '@/messages';

/**
 * 日志 — the platform's own technical evidence, shown as it is (#38).
 *
 * Not translated and not reformatted: this is what a DBA pastes into a ticket, in the same
 * category as a 预检发现's `detail`. It is still subject to Gate 7 — the lines are about
 * tables and rows, and the execution platform's vocabulary is not in them.
 *
 * Bounded by the server, with the true total stated (lead decision D24).
 */
export function RunLogPanel({ snapshot }: { readonly snapshot: RunProgressSnapshot }) {
  const copy = messages.run.log;

  return (
    <section className="dbx-run__panel" aria-label={copy.heading}>
      <h3 className="dbx-run__panel-title">{copy.heading}</h3>
      <p className="dbx-run__panel-lead">{copy.lead}</p>
      {snapshot.log.length === 0 ? (
        <p className="dbx-run__muted">{copy.empty}</p>
      ) : (
        <pre className="dbx-run__log">
          {snapshot.log.map((line) => `${formatTimestamp(line.at)}  ${line.text}`).join('\n')}
        </pre>
      )}
      {snapshot.logTotalCount > snapshot.log.length ? (
        <p className="dbx-run__bound">
          {copy.bounded(snapshot.log.length, snapshot.logTotalCount)}
        </p>
      ) : null}
    </section>
  );
}
