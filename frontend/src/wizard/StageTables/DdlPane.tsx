import { useMemo, type ReactNode } from 'react';
import { CopyButton } from '@carbon/react';
import { messages } from '@/messages';
import { tokeniseSql } from './sqlHighlight';

/**
 * One read-only DDL rendering — **Gate 4**.
 *
 * ADR-0011: 「DDL is a complete read-only rendering of that contract, never a second
 * editable configuration」, and 「editable DDL」 is listed among the rejected alternatives
 * because arbitrary SQL can diverge from the Source output and bypass structured preflight
 * and mapping rules. That is not a policy this component enforces with a `readOnly` flag —
 * it is a shape: there is no `textarea`, no `input`, no `contentEditable` anywhere in the
 * tree below, so there is nothing for a keystroke to land in.
 *
 * What the pane does offer is the two things a DBA actually needs from it: the whole
 * statement, highlighted, and a way to take all of it away (story 45 — 「贴进变更评审」).
 */
interface DdlPaneProps {
  readonly title: string;
  /** The statement, or null when there is nothing to render yet. */
  readonly sql: string | null;
  /** Shown instead of the statement — an absent contract is a state, not a blank. */
  readonly emptyTitle?: string;
  readonly emptyBody?: string;
  /** Facts about the rendering: the contract version, when it was regenerated. */
  readonly facts?: ReactNode;
}

export function DdlPane({ title, sql, emptyTitle, emptyBody, facts }: DdlPaneProps) {
  const tokens = useMemo(() => (sql === null ? [] : tokeniseSql(sql)), [sql]);

  return (
    <section className="dbx-ddl" aria-label={title}>
      <header className="dbx-ddl__header">
        <h3 className="dbx-wizard__pane-title">{title}</h3>
        {sql === null ? null : (
          <CopyButton
            align="left"
            iconDescription={messages.wizard.tables.copyAction(title)}
            feedback={messages.wizard.tables.copied}
            onClick={() => {
              // The whole statement, never a selection: what gets pasted into a change
              // review has to be the same thing the structural proof will be made against.
              void navigator.clipboard?.writeText(sql);
            }}
          />
        )}
      </header>
      {facts === undefined ? null : <p className="dbx-wizard__fact">{facts}</p>}
      {sql === null ? (
        <div className="dbx-ddl__empty">
          <h4 className="dbx-view-state__title">{emptyTitle}</h4>
          <p className="dbx-view-state__body">{emptyBody}</p>
        </div>
      ) : (
        <pre className="dbx-ddl__code">
          <code>
            {tokens.map((token, index) => (
              <span key={index} className={`dbx-sql dbx-sql--${token.kind}`}>
                {token.text}
              </span>
            ))}
          </code>
        </pre>
      )}
    </section>
  );
}
