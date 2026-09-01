import type { ReactNode } from 'react';
import { Section, Heading } from '@carbon/react';

/**
 * How wide a page is allowed to be.
 *
 * `reading` keeps configuration and form pages at a comfortable measure. `full` gives the
 * page the whole viewport, and exists because two DBX surfaces are wide by nature rather
 * than by accident: the 1200-row 迁移范围 selector and the three-pane single-table
 * workspace. Deciding it here, once, is deliberate (lead decision D7) — the alternative was
 * every wide page overriding `max-width` for itself, which is how a layout stops having a
 * rule at all.
 */
export type PageWidth = 'reading' | 'full';

interface PageProps {
  title: string;
  lead?: string;
  width?: PageWidth;
  /** Content that belongs beside the title, such as a primary action. */
  actions?: ReactNode;
  children?: ReactNode;
}

/** Shared page frame so headings keep one semantic level order across routes. */
export function Page({ title, lead, width = 'reading', actions, children }: PageProps) {
  return (
    <Section className={width === 'full' ? 'dbx-page dbx-page--full' : 'dbx-page'}>
      <div className="dbx-page__header">
        <div>
          <Heading className="dbx-page__title">{title}</Heading>
          {lead ? <p className="dbx-page__lead">{lead}</p> : null}
        </div>
        {actions ? <div className="dbx-page__actions">{actions}</div> : null}
      </div>
      {children}
    </Section>
  );
}
