import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { messages } from '@/messages';
// The shared helper (lead decision D14) supplies the query client these routes now need:
// the migration task list reads through TanStack Query, so rendering a route without a
// provider stopped being a shell-only concern in #33.
import { renderRoute as renderAt } from '@/test/render';

describe('product shell', () => {
  it('offers the three navigation destinations', () => {
    renderAt('/tasks');

    const nav = screen.getByRole('navigation', { name: messages.nav.ariaLabel });
    expect(nav).toHaveTextContent(messages.nav.migrationTasks);
    expect(nav).toHaveTextContent(messages.nav.databaseConnections);
    expect(nav).toHaveTextContent(messages.nav.settings);
  });

  it('never calls a migration task a 作业', () => {
    renderAt('/tasks');

    // `Migration task` lists Job under `_Avoid_` in CONTEXT.md, and 「作业中心」 is a
    // drift that already happened once. This assertion is the guard against a repeat.
    expect(document.body.textContent).not.toContain('作业');
  });

  it('routes the wizard stage URL to the named stage', () => {
    renderAt('/tasks/new/draft-1/scope');

    expect(screen.getByText(new RegExp(messages.wizard.stages.scope))).toBeInTheDocument();
  });
});
