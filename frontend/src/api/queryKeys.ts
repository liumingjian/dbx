import { carriedSearchParams } from '@/routes/paths';

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
 *
 * **The parameters are read straight off the URL**, exactly as `src/routes/paths.ts` reads
 * them, and the scenario registry is deliberately not consulted. This is product code: a
 * cache key needs an *identity* for the world being looked at, not a resolved definition,
 * and importing mock infrastructure to obtain one would make the product depend on the
 * mocks it is supposed to be replaceable behind. `eslint.config.js` now enforces that.
 */
export function currentScenarioKey(): string {
  const search = typeof globalThis.location === 'undefined' ? '' : globalThis.location.search;
  const params = new URLSearchParams(search);
  // Every carried parameter, named, so two different worlds can never collapse into one
  // key and an absent parameter is distinguishable from an empty one.
  return carriedSearchParams.map((name) => `${name}=${params.get(name) ?? ''}`).join('&');
}

/**
 * Prefixes a resource key with the scenario, e.g.
 * `['scenario=stuck-table&clockRate=', 'migration-tasks']`.
 */
export function dbxQueryKey(...parts: readonly (string | number | null)[]): readonly unknown[] {
  return [currentScenarioKey(), ...parts];
}
