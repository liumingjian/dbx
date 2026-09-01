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
  migrationRun: '/runs/:runId',
  tableMigrationUnit: '/runs/:runId/tables/:unitId',
  databaseConnections: '/connections',
  settings: '/settings',
  /** Design reference surface: the 32px Chinese density sample from ADR-0014. */
  densitySample: '/design/density',
} as const;

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
  migrationTasks: routePatterns.migrationTasks,
  databaseConnections: routePatterns.databaseConnections,
  settings: routePatterns.settings,
  densitySample: routePatterns.densitySample,
  wizardStage: (draftId: string, stage: WizardStage) =>
    buildPath(routePatterns.wizardStage, { draftId, stage }),
  migrationRun: (runId: string) => buildPath(routePatterns.migrationRun, { runId }),
  tableMigrationUnit: (runId: string, unitId: string) =>
    buildPath(routePatterns.tableMigrationUnit, { runId, unitId }),
} as const;
