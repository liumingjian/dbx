/**
 * 运行监控 (#38): the views that read one 迁移运行 through the `RunProgressSource` seam.
 *
 * The transport lives in `src/api/runProgressSource.ts`; nothing in here knows how a
 * snapshot arrived, which is the whole point of that seam.
 */
export { CancelRunModal } from './CancelRunModal';
export { RunEventStream } from './RunEventStream';
export { RunLogPanel } from './RunLogPanel';
export { RunProgressMatrix } from './RunProgressMatrix';
export { StuckPanel } from './StuckPanel';
export { TableEvidenceDrawer } from './TableEvidenceDrawer';
export {
  formatMinutes,
  outcomeLabel,
  phaseLabel,
  presentRootCauseDomain,
  runEventLabel,
  unitOutcomeLabel,
} from './runVocabulary';
