/**
 * 校验报告 (#40): the views and the pure rules behind the report a DBA submits to a change
 * review. The technical conclusion and the 校验处置 are separate everywhere in here.
 */
export { RecordDispositionModal } from './RecordDispositionModal';
export { ValidationReportTable } from './ValidationReportTable';
export { formatValidationReport } from './reportExport';
export {
  isDisposable,
  itemStateCountsOf,
  reportConclusionOrder,
  reportItemStateOrder,
  summariseValidationReport,
  type ValidationReportSummary,
} from './reportSummary';
