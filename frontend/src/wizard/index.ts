/**
 * The migration wizard.
 *
 * `MigrationWizardStagePage` loads the draft and the connections, resolves which stage the
 * URL is actually allowed to show, and renders one stage inside `WizardShell`. Stages three
 * to six are added by supplying a component for the stage and a rule for it in
 * `stageGates.ts`; nothing else has to move.
 */
export { WizardShell } from './WizardShell';
export { StageConnections } from './StageConnections';
export { StageScope } from './StageScope';
export { StageTables } from './StageTables';
export { StageConfirm } from './StageConfirm';
export {
  evaluateStageGate,
  furthestReachableStage,
  isStageComplete,
  isStageReachable,
  mayStartMigration,
  nextStage,
  previousStage,
  resolveStageEntry,
  wizardStageGates,
  type StageGateResult,
  type WizardGateContext,
} from './stageGates';
export {
  compareSourceTableNames,
  draftPatchOfSelection,
  matchesSearch,
  selectionScopeOfDraft,
  sortedSourceTables,
} from './scopeSelection';
