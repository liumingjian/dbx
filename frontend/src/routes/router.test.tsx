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

  it('routes the wizard stage URL into the migration wizard', async () => {
    renderAt('/tasks/new/draft-1/scope');

    // The wizard owns `/tasks/new/:draftId/:stage`. `draft-1` was never created, and a
    // 迁移草稿 that is gone leaves no trace, so the page says so rather than offering a
    // retry that can never succeed — or a 404, which would be about the route.
    expect(await screen.findByText(messages.wizard.notFound.title)).toBeInTheDocument();
  });
});
