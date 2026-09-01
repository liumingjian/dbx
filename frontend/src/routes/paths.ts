/**
 * Every route in DBX has its own URL, drawers included (ADR-0016 / #30): a per-table
 * evidence view is quoted into a ticket, so it must be deep-linkable and restorable on
 * refresh. The prototype's `?variant=A` switch does not carry into the product.
 */

/** The six wizard stages, in journey order (ADR-0007). */
export const wizardStages = [
  'connections',
  'scope',
  'tables',
  'confirm',
  'monitor',
  'validation',
] as const;

export type WizardStage = (typeof wizardStages)[number];

export function isWizardStage(value: string | undefined): value is WizardStage {
  return wizardStages.includes(value as WizardStage);
}

/**
 * Route patterns are the single source of truth. The builders below are derived from
 * them by substituting parameters, so a pattern and its URL cannot drift apart.
 */
export const routePatterns = {
  migrationTasks: '/tasks',
  wizardStage: '/tasks/new/:draftId/:stage',
  /** A task's migration run history. Deep-linkable, like every other DBX surface. */
  migrationTaskRuns: '/tasks/:taskId/runs',
  migrationRun: '/runs/:runId',
  tableMigrationUnit: '/runs/:runId/tables/:unitId',
  /** 校验报告 — the wizard's sixth stage, seen from the 迁移运行 it concludes (#40). */
  validationReport: '/runs/:runId/validation',
  databaseConnections: '/connections',
  settings: '/settings',
  /** Design reference surface: the 32px Chinese density sample from ADR-0014. */
  densitySample: '/design/density',
} as const;

/**
 * The query parameters every DBX URL carries through client-side navigation.
 *
 * `?scenario=` (and its `?clockRate=` companion) select which mocked world the operator is
 * looking at — ADR-0016 makes them the entry point for every failure state, and #30's
 * state-coverage matrix walks many views in one session. A parameter that only survives
 * until the first link is pressed is not a deep link, it is a first paint: #35 found the
 * wizard's own stage navigation silently dropping it, so a reviewer following a
 * 「部分表失败」 link landed back in the default world one click later.
 *
 * The fix belongs **here**, in the one module that builds URLs, and not at the call sites.
 * A per-caller fix is a rule nobody can check; a builder that carries the parameters is a
 * rule that cannot be forgotten. This is why every entry of `paths` below is a function:
 * a constant would be evaluated once at module load and freeze whatever the URL happened
 * to say then.
 *
 * The names are duplicated from `src/mocks/scenarios.ts` on purpose — routing is product
 * code and must not import mock infrastructure — and `paths.test.ts` asserts the two lists
 * agree, so the duplication cannot drift.
 */
export const carriedSearchParams = ['scenario', 'clockRate'] as const;

function activeSearch(): string {
  return typeof globalThis.location === 'undefined' ? '' : globalThis.location.search;
}

/**
 * Adds the active scenario parameters to a path, without disturbing its own query.
 *
 * A parameter the path already states wins: `?table=` and a deliberate `?scenario=` are
 * both decisions the caller has made, and carrying is only meant to stop a decision being
 * lost, never to overrule one.
 */
export function carrySearch(path: string, search: string = activeSearch()): string {
  const active = new URLSearchParams(search);
  const [pathname = path, own = ''] = path.split('?');
  const merged = new URLSearchParams(own);
  for (const name of carriedSearchParams) {
    const value = active.get(name);
    if (value !== null && !merged.has(name)) {
      merged.set(name, value);
    }
  }
  const query = merged.toString();
  return query === '' ? pathname : `${pathname}?${query}`;
}

function buildPath(pattern: string, params: Record<string, string>): string {
  return pattern.replace(/:(\w+)/g, (_match, name: string) => {
    const value = params[name];
    if (value === undefined) {
      throw new Error(`Missing route parameter "${name}" for pattern "${pattern}"`);
    }
    return encodeURIComponent(value);
  });
}

export const paths = {
  migrationTasks: () => carrySearch(routePatterns.migrationTasks),
  databaseConnections: () => carrySearch(routePatterns.databaseConnections),
  settings: () => carrySearch(routePatterns.settings),
  densitySample: () => carrySearch(routePatterns.densitySample),
  wizardStage: (draftId: string, stage: WizardStage) =>
    carrySearch(buildPath(routePatterns.wizardStage, { draftId, stage })),
  migrationTaskRuns: (taskId: string) =>
    carrySearch(buildPath(routePatterns.migrationTaskRuns, { taskId })),
  migrationRun: (runId: string) => carrySearch(buildPath(routePatterns.migrationRun, { runId })),
  tableMigrationUnit: (runId: string, unitId: string) =>
    carrySearch(buildPath(routePatterns.tableMigrationUnit, { runId, unitId })),
  validationReport: (runId: string) =>
    carrySearch(buildPath(routePatterns.validationReport, { runId })),
} as const;
