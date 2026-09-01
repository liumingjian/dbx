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

export const paths = {
  migrationTasks: '/tasks',
  wizardStage: (draftId: string, stage: WizardStage) => `/tasks/new/${draftId}/${stage}`,
  migrationRun: (runId: string) => `/runs/${runId}`,
  tableMigrationUnit: (runId: string, unitId: string) => `/runs/${runId}/tables/${unitId}`,
  databaseConnections: '/connections',
  settings: '/settings',
  /** Design reference surface: the 32px Chinese density sample from ADR-0014. */
  densitySample: '/design/density',
} as const;

/** Route patterns, kept next to the builders so the two cannot drift apart. */
export const routePatterns = {
  migrationTasks: '/tasks',
  wizardStage: '/tasks/new/:draftId/:stage',
  migrationRun: '/runs/:runId',
  tableMigrationUnit: '/runs/:runId/tables/:unitId',
  databaseConnections: '/connections',
  settings: '/settings',
  densitySample: '/design/density',
} as const;
