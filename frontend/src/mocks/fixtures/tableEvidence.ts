import type {
  Diagnosis,
  DiagnosisCode,
  ErrorOccurrence,
  RunProgressSnapshot,
  TableMigrationUnit,
  TableMigrationUnitEvidence,
} from '@/contract';
import { OBSERVATION_INTERVAL_MOCK_MS } from './runProgress';

/**
 * 单表证据, as the mock assembles it (#39).
 *
 * It is a **projection of the run snapshot**, not a second world: the same unit, the same
 * 卡死 diagnosis and the same instant the monitor is looking at. Two things follow, and
 * both are deliberate:
 *
 *  - the drawer can never disagree with the row it was opened from, because there is only
 *    one source for both;
 *  - the evidence is as deterministic as the run is, so the same deep link produces the
 *    same screen twice — which is the entire point of giving a table's evidence a URL.
 *
 * ADR-0005's separation is preserved rather than flattened. An 错误事件 is an observed
 * fact with its own times and its own immutable evidence reference; the 诊断 is a separate,
 * versioned interpretation of those facts, carrying one 根因域; and neither of them is the
 * unit's `outcome`, which the state machine owns.
 */

/** The catalog version every diagnosis in this mock was reached under (ADR-0005). */
export const DIAGNOSIS_CATALOG_VERSION = '2026.09.1';

/**
 * The external-translation codes, in the order failing tables take them.
 *
 * The first three are chosen so that a run with three failures always shows a **source**
 * problem, a **target** problem and an **unidentified** one side by side: that contrast is
 * what the drawer exists to make readable, and a fixture that produced only one kind would
 * let it rot unnoticed.
 */
const FAILURE_CODES: readonly DiagnosisCode[] = [
  'DBX-SOURCE-PERMISSION-DENIED',
  'DBX-TARGET-NOT-NULL-VIOLATION',
  'DBX-UNKNOWN',
  'DBX-SOURCE-VALUE-UNREPRESENTABLE',
  'DBX-TARGET-VALUE-TOO-LONG',
];

/** The 根因域 the catalog assigns to each code. Exactly one per diagnosis (ADR-0005). */
const CODE_DOMAINS: Record<DiagnosisCode, Diagnosis['rootCauseDomain']> = {
  'DBX-SOURCE-PERMISSION-DENIED': 'SOURCE_DATABASE',
  'DBX-SOURCE-VALUE-UNREPRESENTABLE': 'SOURCE_DATABASE',
  'DBX-TARGET-NOT-NULL-VIOLATION': 'TARGET_DATABASE',
  'DBX-TARGET-VALUE-TOO-LONG': 'TARGET_DATABASE',
  // An unidentified failure still needs one primary domain; it is the platform's own
  // admission that it could not attribute the failure, and the copy says exactly that.
  'DBX-UNKNOWN': 'PLATFORM',
  'DBX-NO-OBSERVABLE-PROGRESS': 'KAFKA_CONNECT',
  'DBX-STOPPED-BY-RELATED-FAILURE': 'KAFKA_CONNECT',
};

const SOURCE_KINDS: Record<DiagnosisCode, Diagnosis['sourceKind']> = {
  'DBX-SOURCE-PERMISSION-DENIED': 'EXTERNAL_TRANSLATION',
  'DBX-SOURCE-VALUE-UNREPRESENTABLE': 'EXTERNAL_TRANSLATION',
  'DBX-TARGET-NOT-NULL-VIOLATION': 'EXTERNAL_TRANSLATION',
  'DBX-TARGET-VALUE-TOO-LONG': 'EXTERNAL_TRANSLATION',
  'DBX-UNKNOWN': 'SYSTEM_FALLBACK',
  // Facts DBX produced itself: the 卡死 diagnosis and what it stopped.
  'DBX-NO-OBSERVABLE-PROGRESS': 'STRUCTURED',
  'DBX-STOPPED-BY-RELATED-FAILURE': 'STRUCTURED',
};

/**
 * The redacted technical detail retained with an 错误事件.
 *
 * Server-produced evidence, quoted verbatim into a ticket — and bound by Gate 7 like every
 * other line the operator can read: no scheduling group, no connector, no topic. It names
 * the table, the column and the stable code, which is what a DBA needs to act.
 */
function detailOf(code: DiagnosisCode, unit: TableMigrationUnit): string {
  switch (code) {
    case 'DBX-SOURCE-PERMISSION-DENIED':
      return (
        `SQLSTATE 42000: SELECT command denied to user 'dbx_reader'@'10.0.3.11' ` +
        `for table '${unit.sourceTable}'`
      );
    case 'DBX-SOURCE-VALUE-UNREPRESENTABLE':
      return (
        `${unit.sourceTable}.created_at: source value '0000-00-00 00:00:00' has no ` +
        `representation in target type timestamp(3)`
      );
    case 'DBX-TARGET-NOT-NULL-VIOLATION':
      return (
        `SQLSTATE 23502: null value in column "settled_at" of relation ` +
        `"${unit.targetTable}" violates not-null constraint`
      );
    case 'DBX-TARGET-VALUE-TOO-LONG':
      return (
        `SQLSTATE 22001: value too long for type character varying(64) ` +
        `in column "reference_code" of relation "${unit.targetTable}"`
      );
    case 'DBX-UNKNOWN':
      return (
        `${unit.sourceTable}: transfer task exited with an unrecoverable exception; ` +
        `no catalog rule matched the deepest trustworthy cause`
      );
    case 'DBX-NO-OBSERVABLE-PROGRESS':
      return `${unit.sourceTable}: no observable progress within the configured hard threshold`;
    case 'DBX-STOPPED-BY-RELATED-FAILURE':
      return (
        `${unit.sourceTable}: stopped without a technical result of its own after a ` +
        `related failure; target data and evidence preserved`
      );
  }
}

function occurrenceOf(
  code: DiagnosisCode,
  unit: TableMigrationUnit,
  observedPhase: TableMigrationUnit['phase'],
  firstObservedAt: string,
  lastObservedAt: string,
  observationCount: number,
): ErrorOccurrence {
  return {
    id: `${unit.id}-occurrence-1`,
    observedPhase,
    firstObservedAt,
    lastObservedAt,
    observationCount,
    // An immutable reference rather than the evidence itself: the 诊断包 is what carries
    // the raw material, and it is exported deliberately rather than rendered in a drawer.
    evidenceReference: `dbx-evidence://${unit.runId}/${unit.id}/1`,
    detail: detailOf(code, unit),
  };
}

/** The position of a failing unit among the run's failing units, or -1. */
function failurePosition(snapshot: RunProgressSnapshot, unit: TableMigrationUnit): number {
  return snapshot.units
    .filter((candidate) => candidate.outcome === 'FAILED')
    .findIndex((candidate) => candidate.id === unit.id);
}

function minus(at: string, milliseconds: number): string {
  return new Date(Date.parse(at) - milliseconds).toISOString();
}

/**
 * The evidence for one 表迁移单元 at the instant the snapshot describes.
 *
 * A healthy table has **no diagnosis and no 错误事件**, and says so: an interpretation
 * invented to fill a panel would be exactly the speculative cause ADR-0005 forbids.
 */
export function buildTableMigrationUnitEvidence(
  snapshot: RunProgressSnapshot,
  unitId: string,
): TableMigrationUnitEvidence | undefined {
  const unit = snapshot.units.find((candidate) => candidate.id === unitId);
  if (unit === undefined) {
    return undefined;
  }

  const base = {
    observedAt: snapshot.observedAt,
    runId: snapshot.run.id,
    unitId: unit.id,
    unit,
  };

  const stalled = snapshot.stuck?.stalledUnitIds.includes(unit.id) === true;
  const blocked = unit.outcome === 'BLOCKED_BY_BOX_FAILURE';
  const failed = unit.outcome === 'FAILED';

  if (!stalled && !blocked && !failed) {
    return { ...base, occurrences: [], diagnosis: null };
  }

  const code: DiagnosisCode = stalled
    ? 'DBX-NO-OBSERVABLE-PROGRESS'
    : blocked
      ? 'DBX-STOPPED-BY-RELATED-FAILURE'
      : (FAILURE_CODES[Math.max(0, failurePosition(snapshot, unit)) % FAILURE_CODES.length] ??
        'DBX-UNKNOWN');

  const lastObservedAt =
    stalled || blocked
      ? (snapshot.stuck?.diagnosedAt ?? snapshot.observedAt)
      : (unit.progress?.observedAt ?? snapshot.observedAt);
  const firstObservedAt =
    stalled && snapshot.stuck !== null
      ? snapshot.stuck.lastProgressAt
      : minus(lastObservedAt, OBSERVATION_INTERVAL_MOCK_MS);

  const occurrence = occurrenceOf(
    code,
    unit,
    // The phase DBX observed the table in, which for every failure it can attribute to a
    // table is the transfer. The unit's own phase has since become TERMINAL, and an
    // occurrence records what was true when it happened rather than what is true now.
    stalled ? unit.phase : 'TRANSFERRING',
    firstObservedAt,
    lastObservedAt,
    // Repeated observations of the same fingerprint are aggregated, never appended.
    stalled || blocked ? 1 : 3,
  );

  const diagnosis: Diagnosis = {
    code,
    catalogVersion: DIAGNOSIS_CATALOG_VERSION,
    sourceKind: SOURCE_KINDS[code],
    rootCauseDomain:
      stalled || blocked ? (snapshot.stuck?.rootCauseDomain ?? 'PLATFORM') : CODE_DOMAINS[code],
    occurrenceIds: [occurrence.id],
  };

  return { ...base, occurrences: [occurrence], diagnosis };
}
