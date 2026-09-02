import { createControllableClock, type ControllableClock } from './clock';
import { createDraftPersistence } from './persistence';
import { resolveScenario, type ResolvedScenario } from './scenarios';
import { createMockStore, type MockStore } from './store';

/**
 * Resolves the scenario in force and holds the clock and store it implies.
 *
 * The scenario is read from the current URL rather than injected at start-up, because a
 * reviewer's entry point is a link: `/connections?scenario=error` has to put that page in
 * its error state on first paint. The context is rebuilt whenever the scenario or clock
 * rate changes, so two scenarios can never share one store's mutations.
 */
export interface MockContext {
  readonly scenario: ResolvedScenario;
  readonly clock: ControllableClock;
  readonly store: MockStore;
}

let current: MockContext | null = null;

function readSearch(): string {
  return typeof globalThis.location === 'undefined' ? '' : globalThis.location.search;
}

export function getMockContext(): MockContext {
  const scenario = resolveScenario(readSearch());
  if (current !== null && current.scenario.key === scenario.key) {
    return current;
  }

  if (scenario.unknownScenarioId !== null) {
    // Loud rather than silent: a mistyped scenario in a review link should be visible,
    // not quietly served as the default one.
    console.warn(
      `[dbx-mocks] unknown scenario "${scenario.unknownScenarioId}"; serving "${scenario.definition.id}".`,
    );
  }

  const clock = createControllableClock({ rate: scenario.clockRate });
  const store = createMockStore({
    scenario: scenario.definition,
    clock,
    draftPersistence: createDraftPersistence(scenario.definition.draftPersistence),
  });

  current = { scenario, clock, store };
  return current;
}

/** Drops the cached context so the next request rebuilds it from the current URL. */
export function resetMockContext(): void {
  current = null;
}
