import type { ReactNode } from 'react';
import { Section, Heading } from '@carbon/react';

interface PageProps {
  title: string;
  lead?: string;
  children?: ReactNode;
}

/** Shared page frame so headings keep one semantic level order across routes. */
export function Page({ title, lead, children }: PageProps) {
  return (
    <Section className="dbx-page">
      <Heading className="dbx-page__title">{title}</Heading>
      {lead ? <p className="dbx-page__lead">{lead}</p> : null}
      {children}
    </Section>
  );
}
