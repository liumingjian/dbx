import { resolveScenario } from '@/mocks/scenarios';

/**
 * Query keys, scoped to the scenario in force (lead decision D12).
 *
 * Every mocked state DBX can be in is reachable from a URL scenario parameter, and #42's
 * state-coverage matrix walks many of them inside one session. Without the scenario in the
 * key, switching scenario without a full page load serves the previous scenario's cached
 * data, and the view quietly disagrees with the URL that produced it.
 *
 * It is folded in here, once, rather than remembered at each call site. When the mocks are
 * replaced by a real backend this collapses to a constant and every key stays valid.
 */
export function currentScenarioKey(): string {
  const search = typeof globalThis.location === 'undefined' ? '' : globalThis.location.search;
  return resolveScenario(search).key;
}

/** Prefixes a resource key with the scenario, e.g. `['default@60', 'migration-tasks']`. */
export function dbxQueryKey(...parts: readonly (string | number | null)[]): readonly unknown[] {
  return [currentScenarioKey(), ...parts];
}
