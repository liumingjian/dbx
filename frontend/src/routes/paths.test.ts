import { describe, expect, it } from 'vitest';
import { CLOCK_RATE_PARAM, SCENARIO_PARAM } from '@/mocks/scenarios';
import { carriedSearchParams, carrySearch, isWizardStage, paths, routePatterns } from './paths';

describe('route paths', () => {
  it('builds each parameterised URL from its own pattern', () => {
    expect(paths.wizardStage('draft-1', 'scope')).toBe('/tasks/new/draft-1/scope');
    expect(paths.migrationRun('run-7')).toBe('/runs/run-7');
    expect(paths.tableMigrationUnit('run-7', 'unit-3')).toBe('/runs/run-7/tables/unit-3');
  });

  it('leaves no parameter placeholder behind', () => {
    for (const url of [
      paths.wizardStage('d', 'confirm'),
      paths.migrationRun('r'),
      paths.tableMigrationUnit('r', 'u'),
    ]) {
      expect(url).not.toContain(':');
    }
  });

  it('escapes identifiers so they cannot alter the route shape', () => {
    expect(paths.migrationRun('run/7')).toBe('/runs/run%2F7');
  });

  it('recognises only the six wizard stages', () => {
    expect(isWizardStage('scope')).toBe(true);
    expect(isWizardStage('nonsense')).toBe(false);
    expect(isWizardStage(undefined)).toBe(false);
  });

  it('keeps a pattern for every route the router mounts', () => {
    expect(Object.values(routePatterns).every((pattern) => pattern.startsWith('/'))).toBe(true);
  });
});

/**
 * `?scenario=` has to survive client-side navigation (lead decision D21).
 *
 * A scenario that only holds until the first link is pressed is a first paint, not a deep
 * link: a reviewer following a 「部分表失败」 link would land back in the default world one
 * click later, and #42's coverage matrix walks many views in one session. The rule lives
 * in the builders, so it cannot be forgotten at a call site.
 */
describe('the active scenario travels with every URL', () => {
  it('carries the scenario and the clock rate through a built path', () => {
    expect(carrySearch('/tasks', '?scenario=blocked-preflight&clockRate=4')).toBe(
      '/tasks?scenario=blocked-preflight&clockRate=4',
    );
    expect(carrySearch('/tasks/new/draft-1/tables', '?scenario=error')).toBe(
      '/tasks/new/draft-1/tables?scenario=error',
    );
  });

  it('leaves every other parameter behind', () => {
    // Only the two that say which mocked world this is. A filter or a table selection is
    // the state of the view being left, not of the one being opened.
    expect(carrySearch('/connections', '?scenario=empty&table=order_item&status=FAILED')).toBe(
      '/connections?scenario=empty',
    );
  });

  it('never overrules a parameter the path already states', () => {
    expect(carrySearch('/tasks?scenario=empty', '?scenario=error')).toBe('/tasks?scenario=empty');
    expect(carrySearch('/tables?table=order_item', '?scenario=error')).toBe(
      '/tables?table=order_item&scenario=error',
    );
  });

  it('adds nothing when no scenario is in force', () => {
    expect(carrySearch('/tasks', '')).toBe('/tasks');
    // The default world is the URL with no parameter on it; adding one would make every
    // link claim a scenario the operator never chose.
    expect(carrySearch('/runs/run-7', '?clockRate=')).toBe('/runs/run-7?clockRate=');
  });

  it('carries the same parameter names the mock layer reads', () => {
    // Routing is product code and must not import mock infrastructure, so the two lists
    // are written down twice. This is what stops them drifting apart.
    expect([...carriedSearchParams]).toEqual([SCENARIO_PARAM, CLOCK_RATE_PARAM]);
  });
});
