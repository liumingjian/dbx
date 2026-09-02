import { describe, expect, it } from 'vitest';
import type { TableMigrationOutcome, ValidationConclusion, ValidationReportRow } from '@/contract';
import { isRemigrationCandidate } from '@/features/remigration/candidates';
import { isServerRemigrationCandidate } from './remigrationCandidates';

/**
 * The two copies of the 重新迁移 candidate rule agree.
 *
 * The duplication is deliberate — the mock stands in for a server, and a server that
 * answered by calling the client's own function would make 「never offers a 预检排除项」 a
 * tautology. What keeps two copies honest is this: every combination the contract allows,
 * asserted to produce the same answer on both sides.
 */
const conclusions: readonly ValidationConclusion[] = [
  'PASS',
  'FAIL',
  'INCONCLUSIVE',
  'NOT_APPLICABLE',
  'NOT_RUN',
  'IN_FLIGHT',
];

const outcomes: readonly (TableMigrationOutcome | null)[] = [
  null,
  'SUCCEEDED',
  'FAILED',
  'BLOCKED_BY_BOX_FAILURE',
  'SKIPPED',
  'CANCELLED',
  'COMPLETED_WITH_ACCEPTED_RISK',
];

function rowWith(
  conclusion: ValidationConclusion,
  unitOutcome: TableMigrationOutcome | null,
): ValidationReportRow {
  return {
    unitId: 'run-1-unit-1',
    sourceTable: 'order_item',
    targetTable: 'order_item',
    conclusion,
    unitOutcome,
    execution: null,
    disposition: null,
  } as ValidationReportRow;
}

describe('the mock platform and the browser agree on what may be migrated again', () => {
  for (const conclusion of conclusions) {
    for (const unitOutcome of outcomes) {
      it(`agrees for ${conclusion} / ${unitOutcome ?? '尚无技术结果'}`, () => {
        const row = rowWith(conclusion, unitOutcome);
        expect(isServerRemigrationCandidate(row)).toBe(isRemigrationCandidate(row));
      });
    }
  }
});
