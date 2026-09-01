/**
 * 重新迁移 (#41): migrating again the tables an earlier 迁移运行 left undetermined.
 *
 * Everything in here obeys one sentence of `CONTEXT.md` — 「A rerun is a new migration
 * run」 — so nothing exported from this module can reach an existing run's record.
 */
export { RemigrationPanel } from './RemigrationPanel';
export { StartRemigrationModal } from './StartRemigrationModal';
export {
  isRemigrationCandidate,
  remigrationCandidateRows,
  reportCoversEveryTableOnce,
} from './candidates';
