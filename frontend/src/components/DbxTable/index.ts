/**
 * The `DbxTable` boundary (ADR-0015).
 *
 * Business code imports from here and from nowhere else in this directory; the substrate
 * is not reachable through this entry point, and `eslint.config.js` keeps it that way.
 */
export { DbxTable } from './DbxTable';
export {
  useDbxSelection,
  emptySelection,
  allMatchingFilterSelection,
  isRowSelected,
  toggleRow,
  excludeRow,
  selectPage,
  clearPage,
  selectedIdsWithin,
  selectedCount,
  selectionSnapshot,
} from './selection';
export {
  dbxTableDensities,
  dbxTableDensityHeights,
  defaultDbxTableDensity,
  readDensityPreference,
  useDbxTableDensity,
} from './density';
export type {
  DbxBatchAction,
  DbxBatchActionSafety,
  DbxRowId,
  DbxSelectionModel,
  DbxSelectionScope,
  DbxSelectionSnapshot,
  DbxTableColumn,
  DbxTableDensity,
  DbxTableEmptyCopy,
  DbxTableErrorCopy,
  DbxTableProps,
  DbxTableRowWindow,
  DbxTableSelectionProps,
} from './types';
