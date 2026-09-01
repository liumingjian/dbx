/**
 * 阶段三 逐表配置与预检 — the single-table workspace (#35).
 *
 * `StageTables` is the whole of the stage's public surface; #36 adds the 预检 panel inside
 * `FindingsPane`'s slot and its clause to `wizardStageGates`, and needs nothing else from
 * here.
 */
export { StageTables } from './StageTables';
export { DdlPane } from './DdlPane';
export { FindingsPane } from './FindingsPane';
export { MappingExceptions } from './MappingExceptions';
export { ObjectTreePane } from './ObjectTreePane';
export { PreflightPane } from './PreflightPane';
export { preflightBlocks, preflightIndicatorConclusion, prunableColumnsOf } from './preflightExits';
export { tokeniseSql, type SqlToken, type SqlTokenKind } from './sqlHighlight';
