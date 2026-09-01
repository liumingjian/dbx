import type {
  IsoTimestamp,
  MigrationRun,
  MigrationRunStatus,
  MigrationTask,
  RunEvent,
  RunLogLine,
  RunProgressSnapshot,
  SourceBaselineEntry,
  StuckDiagnosis,
  TableMigrationOutcome,
  TableMigrationPhase,
  TableMigrationUnit,
} from '@/contract';
import type { ControllableClock } from '../clock';
import type { SeedPlan } from '../scenarios';
import { generateSourceTables } from './sourceTables';

/**
 * The time dimension of a 迁移运行, as the mock supplies it (#38, ADR-0016).
 *
 * Everything below is a **pure projection of the controllable clock**: given a run plan and
 * an instant, the phase, outcome, progress observation, timeline and log of every
 * 表迁移单元 follow deterministically. Nothing is scheduled, nothing accumulates in a
 * mutable buffer, and no timer runs. That is what lets a three-hour migration be reviewed
 * in tens of seconds, lets the same review link produce the same screen twice, and lets a
 * test ask what the run looked like at an arbitrary instant without waiting for it.
 *
 * Two shapes here are deliberate and easy to "tidy" into a lie:
 *
 *  - **progress advances in observation quanta, not continuously.** ADR-0004 permits
 *    progress observations to be coalesced, so a table's latest observation is a step
 *    function whose steps are uneven, and some steps are flat. A view that interpolated
 *    between two of them would be inventing rows that were never reported.
 *  - **some units lag.** A unit's latest observation may be several quanta older than the
 *    snapshot's own instant. This is normal — it is what 「进度可能滞后」 means — and it is
 *    emphatically not 卡死, which is a separate, threshold-based, terminal diagnosis.
 */

/** The 迁移任务 and 迁移运行 that 运行监控 is reachable through, by fixed identifier. */
export const MONITORED_TASK_ID = 'task-monitored';
export const MONITORED_RUN_ID = 'run-monitored';

/** How many 表迁移单元 the monitored run covers. Enough to hold every state at once. */
export const MONITORED_UNIT_COUNT = 12;

/**
 * The observation quantum, in **mock** milliseconds.
 *
 * Progress is reported at this granularity and no finer, which is what makes consecutive
 * reads legitimately return the same observation and then jump. At the default clock rate
 * of 60 it is three real seconds, so the behaviour is visible while a person watches.
 */
export const OBSERVATION_INTERVAL_MOCK_MS = 180_000;

/**
 * The configured hard threshold behind 卡死, in **mock** milliseconds.
 *
 * `CONTEXT.md` defines 卡死 as no observable progress for this long while everything still
 * reports healthy. Ten quanta: comfortably longer than the flat steps an ordinary table
 * produces, so 「慢」 can never drift into 「卡死」 by accident.
 */
export const STUCK_THRESHOLD_MOCK_MS = 10 * OBSERVATION_INTERVAL_MOCK_MS;

/**
 * How far into the run the seeded 迁移运行 already is when the store boots.
 *
 * Lead decision D22: a late state must be reachable on first paint rather than by waiting
 * for it. Forty quanta puts every run plan past its interesting moment — the failures, the
 * 卡死 diagnosis, the cancellation — while leaving the run in flight, so progress still
 * moves and a 取消 is still possible.
 */
export const SEEDED_RUN_ELAPSED_QUANTA = 40;

/** How long a 取消 takes to reach the units it stops, in observation quanta. */
const CANCELLATION_DRAIN_QUANTA = 1;

/** Server-side bounds on the two unbounded lists. The true totals travel with them. */
export const RUN_EVENT_LIMIT = 60;
export const RUN_LOG_LIMIT = 120;

/** Same generator as the source-table fixture: the same seed produces the same bytes. */
function mulberry32(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** What one 表迁移单元 does over the life of the run, in observation quanta. */
interface UnitPlan {
  readonly id: string;
  readonly sourceTable: string;
  readonly targetTable: string;
  /** The exact count captured in the 源基线 — never the discovery estimate. */
  readonly baselineRowCount: number;
  /** The quantum at which the unit stops 等待调度 and DBX begins creating its target. */
  readonly admittedAt: number;
  readonly transferStartsAt: number;
  readonly transferEndsAt: number;
  readonly validationEndsAt: number;
  /** The outcome the unit reaches if nothing interrupts it. */
  readonly outcome: TableMigrationOutcome;
  /** The quantum at which the unit fails outright, if it does. */
  readonly failsAt: number | null;
  /** The quantum at which the unit simply stops moving, if it does. `卡死`'s subject. */
  readonly stallsAt: number | null;
  /** Whether this unit is stopped by another unit's stall rather than by a fault. */
  readonly blockedByStall: boolean;
  /** How many quanta this unit's latest observation trails the snapshot by. */
  readonly observationLagQuanta: number;
  /** Cumulative fraction of the table read after each transfer quantum. Uneven by design. */
  readonly cumulativeRead: readonly number[];
}

export interface RunPlan {
  readonly units: readonly UnitPlan[];
  /** The quantum at which the seeded scenario asks for a 取消, if it does. */
  readonly seededCancellationAt: number | null;
  readonly rootCauseDomain: StuckDiagnosis['rootCauseDomain'];
}

/**
 * Builds the uneven progress curve of one table.
 *
 * A fifth of the quanta report no new rows at all. That is the point: a flat step is
 * ordinary, and an interface that treats every flat step as trouble would cry 卡死 several
 * times in every healthy run.
 */
function cumulativeReadCurve(random: () => number, quanta: number): readonly number[] {
  const weights = Array.from({ length: quanta }, () => (random() < 0.2 ? 0 : 0.2 + random()));
  const total = weights.reduce((sum, weight) => sum + weight, 0) || 1;
  let running = 0;
  return weights.map((weight) => {
    running += weight / total;
    return Math.min(1, running);
  });
}

export interface RunPlanOptions {
  readonly seed: number;
  readonly runPlan: SeedPlan['runPlan'];
  readonly sourceDatabase: string;
  readonly unitCount?: number;
  /**
   * The tables this run actually covers, when they are already known.
   *
   * A run started from the wizard carries its own 源基线, and the monitor must show the
   * tables the operator selected rather than a fresh draw from the generator.
   */
  readonly tables?: readonly { readonly name: string; readonly exactRowCount: number }[];
}

/**
 * The deterministic plan behind one 迁移运行.
 *
 * The table names come from the same generator the 迁移范围 uses, so a run reads like a
 * run over this source database rather than over invented placeholders.
 */
export function buildRunPlan({
  seed,
  runPlan,
  sourceDatabase,
  unitCount = MONITORED_UNIT_COUNT,
  tables: knownTables,
}: RunPlanOptions): RunPlan {
  const random = mulberry32(seed ^ 0x5f5e0ff);
  const tables =
    knownTables === undefined || knownTables.length === 0
      ? generateSourceTables({ seed, count: unitCount, sourceDatabase }).map((table) => ({
          name: table.name,
          exactRowCount: Math.max(1_000, Math.round(table.estimatedRowCount * 0.97) + 137),
        }))
      : knownTables.slice(0, unitCount);

  // The stalled unit and the units scheduled alongside it. Fixed indices rather than
  // random ones so 「某表卡死」 always names the same table in the same scenario.
  const stalledIndex = Math.min(3, Math.max(0, tables.length - 1));
  const blockedIndices = new Set([stalledIndex + 1, stalledIndex + 2]);

  const units = tables.map((table, index) => {
    // Tables are admitted a few quanta apart rather than all at once: resources free up
    // over time, so at any instant a run holds tables that are 等待调度 as well as tables
    // that are moving. Spaced so that the seeded instant always shows both.
    const admittedAt = index * 4;
    const transferStartsAt = admittedAt + 1;
    // The last three tables are deliberately long and deliberately late reporters, so that
    // at the seeded instant the run always holds at least one table that is still moving
    // and whose latest observation trails the snapshot. 观测滞后 is a state the interface
    // has to be able to show, and a state that only appears sometimes cannot be reviewed.
    const lateReporter = index >= tables.length - 3;
    const transferQuanta = lateReporter ? 60 : 12 + Math.floor(random() * 40);
    const transferEndsAt = transferStartsAt + transferQuanta;
    const baselineRowCount = Math.max(1_000, table.exactRowCount);

    const fails = runPlan === 'partial-table-failure' && index % 4 === 1;
    const stalls = runPlan === 'stuck-table' && index === stalledIndex;
    const blocked = runPlan === 'stuck-table' && blockedIndices.has(index);

    const outcome: TableMigrationOutcome =
      runPlan === 'accepted-risk' ? 'COMPLETED_WITH_ACCEPTED_RISK' : fails ? 'FAILED' : 'SUCCEEDED';

    return {
      id: `${MONITORED_RUN_ID}-unit-${index + 1}`,
      sourceTable: table.name,
      targetTable: table.name,
      baselineRowCount,
      admittedAt,
      transferStartsAt,
      transferEndsAt,
      validationEndsAt: transferEndsAt + 2,
      outcome,
      failsAt: fails ? transferStartsAt + 6 + Math.floor(random() * 8) : null,
      stallsAt: stalls ? transferStartsAt + 4 : null,
      blockedByStall: blocked,
      // Lagging is a normal state of the transport, not a fault, and the interface has to
      // be able to show one without alarming anybody.
      observationLagQuanta: lateReporter ? 2 : index % 3 === 1 ? 1 : 0,
      cumulativeRead: cumulativeReadCurve(random, transferQuanta),
    } satisfies UnitPlan;
  });

  return {
    units,
    seededCancellationAt: runPlan === 'operator-cancellation' ? 25 : null,
    // Retained whole; the interface presents both platform domains as 迁移平台.
    rootCauseDomain: 'KAFKA_CONNECT',
  };
}

/** The 源基线 of the monitored run: exact counts, captured under the 写冻结. */
export function runPlanBaselineEntries(plan: RunPlan): readonly SourceBaselineEntry[] {
  return plan.units.map((unit) => ({
    sourceTable: unit.sourceTable,
    exactRowCount: unit.baselineRowCount,
    terminalPrimaryKeyValue: String(unit.baselineRowCount),
  }));
}

interface UnitState {
  readonly phase: TableMigrationPhase;
  readonly outcome: TableMigrationOutcome | null;
  /** The quantum at which this unit's reported progress stopped moving, if it has. */
  readonly frozenAt: number | null;
}

/** The quantum at which a stall would be diagnosed as 卡死, or null when nothing stalls. */
function stuckDiagnosisQuantum(plan: RunPlan): number | null {
  const stalled = plan.units.filter((unit) => unit.stallsAt !== null);
  const first = stalled[0];
  if (first === null || first === undefined || first.stallsAt === null) {
    return null;
  }
  return first.stallsAt + STUCK_THRESHOLD_MOCK_MS / OBSERVATION_INTERVAL_MOCK_MS;
}

/**
 * Where one unit stands at quantum `q`.
 *
 * The order of the clauses is the order of the domain's own precedence: an operator's
 * 取消 stops a unit whatever it was doing; a unit's own failure is its own; a stall leaves
 * the unit exactly where it was, **with no outcome at all**, because ADR-0004 forbids
 * inventing per-table blame; and being stopped alongside a stalled unit is
 * `BLOCKED_BY_BOX_FAILURE`, whose own technical result is undetermined rather than failed.
 */
function unitStateAt(
  unit: UnitPlan,
  q: number,
  cancelledAt: number | null,
  stuckAt: number | null,
): UnitState {
  const naturalTerminalAt = unit.validationEndsAt;
  const failsAt = unit.failsAt;
  const stallsAt = unit.stallsAt;

  const finishedNaturallyBefore = (limit: number): boolean =>
    naturalTerminalAt <= limit && (failsAt === null || failsAt >= limit);

  if (failsAt !== null && q >= failsAt) {
    return { phase: 'TERMINAL', outcome: 'FAILED', frozenAt: failsAt };
  }

  if (cancelledAt !== null && q >= cancelledAt + CANCELLATION_DRAIN_QUANTA) {
    return finishedNaturallyBefore(cancelledAt)
      ? { phase: 'TERMINAL', outcome: unit.outcome, frozenAt: unit.transferEndsAt }
      : { phase: 'TERMINAL', outcome: 'CANCELLED', frozenAt: cancelledAt };
  }

  if (stuckAt !== null && unit.blockedByStall && q >= stuckAt) {
    return finishedNaturallyBefore(stuckAt)
      ? { phase: 'TERMINAL', outcome: unit.outcome, frozenAt: unit.transferEndsAt }
      : { phase: 'TERMINAL', outcome: 'BLOCKED_BY_BOX_FAILURE', frozenAt: stuckAt };
  }

  if (stallsAt !== null && q >= stallsAt) {
    // The stalled unit itself: still `TRANSFERRING`, still without an outcome. 卡死 is a
    // diagnosis about the run, and the unit's own technical result is simply not known.
    return { phase: 'TRANSFERRING', outcome: null, frozenAt: stallsAt };
  }

  if (q < unit.admittedAt) {
    return { phase: 'WAITING_FOR_BOX', outcome: null, frozenAt: null };
  }
  if (q < unit.transferStartsAt) {
    return { phase: 'CREATING_TARGET', outcome: null, frozenAt: null };
  }
  if (q < unit.transferEndsAt) {
    return { phase: 'TRANSFERRING', outcome: null, frozenAt: null };
  }
  if (q < unit.validationEndsAt) {
    return { phase: 'VALIDATING', outcome: null, frozenAt: unit.transferEndsAt };
  }
  return { phase: 'TERMINAL', outcome: unit.outcome, frozenAt: unit.transferEndsAt };
}

/** The cumulative read fraction this unit had reported by quantum `q`. */
function readFractionAt(unit: UnitPlan, q: number): number {
  const step = q - unit.transferStartsAt;
  if (step < 0) {
    return 0;
  }
  const index = Math.min(step, unit.cumulativeRead.length - 1);
  return unit.cumulativeRead[index] ?? 0;
}

/** The last quantum at or before `q` at which this unit's read count actually moved. */
function lastMovementQuantum(unit: UnitPlan, q: number): number | null {
  for (let candidate = q; candidate > unit.transferStartsAt; candidate -= 1) {
    if (readFractionAt(unit, candidate) > readFractionAt(unit, candidate - 1)) {
      return candidate;
    }
  }
  return q >= unit.transferStartsAt ? unit.transferStartsAt : null;
}

export interface RunProjectionOptions {
  readonly run: MigrationRun;
  readonly plan: RunPlan;
  /** Mock time now. */
  readonly nowMs: number;
  /** When a 取消 was requested, in mock milliseconds, or null. */
  readonly cancellationRequestedAtMs: number | null;
  /** The run's true 表迁移单元 count, when the plan holds only a window of them. */
  readonly unitTotalCount?: number;
}

/**
 * Projects the whole run at one instant: units, 卡死, timeline, log and run status.
 *
 * The run record handed in is the immutable one the store froze at start. What comes back
 * carries the *observed* status and end time, which are ADR-0004's 「deterministic
 * projection of the run's units」 — a projection is computed, never edited into the record.
 */
export function projectRunProgress({
  run,
  plan,
  nowMs,
  cancellationRequestedAtMs,
  unitTotalCount,
}: RunProjectionOptions): RunProgressSnapshot {
  const startedAtMs = Date.parse(run.startedAt);
  const interval = OBSERVATION_INTERVAL_MOCK_MS;
  const at = (quantum: number): IsoTimestamp =>
    new Date(startedAtMs + quantum * interval).toISOString();

  const now = Math.max(startedAtMs, nowMs);
  const q = Math.floor((now - startedAtMs) / interval);
  const cancelledAt =
    cancellationRequestedAtMs === null
      ? null
      : Math.max(0, Math.ceil((cancellationRequestedAtMs - startedAtMs) / interval));
  const stuckCandidate = stuckDiagnosisQuantum(plan);
  // A 取消 that arrives before the threshold ends the run; the diagnosis never happens.
  const stuckAt =
    stuckCandidate !== null &&
    (cancelledAt === null || stuckCandidate <= cancelledAt) &&
    stuckCandidate <= q
      ? stuckCandidate
      : null;

  const units = plan.units.map((unit) =>
    unitOf(run, unit, q, cancelledAt, stuckAt, at, startedAtMs, interval),
  );

  const stalled = plan.units.filter((unit) => unit.stallsAt !== null);
  const stuck: StuckDiagnosis | null =
    stuckAt === null
      ? null
      : {
          diagnosedAt: at(stuckAt),
          lastProgressAt: at(stalled[0]?.stallsAt ?? stuckAt),
          thresholdMs: STUCK_THRESHOLD_MOCK_MS,
          noProgressForMs: (q - (stalled[0]?.stallsAt ?? stuckAt)) * interval,
          stalledUnitIds: stalled.map((unit) => unit.id),
          blockedUnitIds: plan.units.filter((unit) => unit.blockedByStall).map((unit) => unit.id),
          rootCauseDomain: plan.rootCauseDomain,
        };

  const status = runStatusAt(plan, q, cancelledAt, stuckAt);
  const endedAtQuantum = terminalQuantum(plan, q, cancelledAt, stuckAt);
  const events = buildEvents(plan, q, cancelledAt, stuckAt, at, status);
  const log = buildLog(plan, q, cancelledAt, stuckAt, at);

  return {
    observedAt: at(q),
    run: {
      ...run,
      status,
      endedAt: endedAtQuantum === null ? null : at(endedAtQuantum),
      cancellationRequestedAt: cancelledAt === null ? run.cancellationRequestedAt : at(cancelledAt),
    },
    units,
    unitTotalCount: unitTotalCount ?? plan.units.length,
    stuck,
    events: events.slice(0, RUN_EVENT_LIMIT),
    eventTotalCount: events.length,
    log: log.slice(0, RUN_LOG_LIMIT),
    logTotalCount: log.length,
  };
}

function unitOf(
  run: MigrationRun,
  unit: UnitPlan,
  q: number,
  cancelledAt: number | null,
  stuckAt: number | null,
  at: (quantum: number) => IsoTimestamp,
  startedAtMs: number,
  interval: number,
): TableMigrationUnit {
  const state = unitStateAt(unit, q, cancelledAt, stuckAt);
  // The observation the platform has actually delivered: capped by whatever froze the unit,
  // and then trailed by this unit's own reporting lag.
  const observedQuantum = Math.min(state.frozenAt ?? q, q - unit.observationLagQuanta);
  const hasProgress = observedQuantum >= unit.transferStartsAt;
  const fraction = hasProgress ? readFractionAt(unit, observedQuantum) : 0;
  // Writes trail reads by one quantum: the target is never ahead of the source, and the
  // two columns are separate observations rather than one number shown twice.
  const writtenFraction = hasProgress ? readFractionAt(unit, observedQuantum - 1) : 0;
  const movement = hasProgress ? lastMovementQuantum(unit, observedQuantum) : null;

  return {
    id: unit.id,
    runId: run.id,
    sourceTable: unit.sourceTable,
    targetTable: unit.targetTable,
    phase: state.phase,
    outcome: state.outcome,
    preflight: {
      // Only a `SUPPORTED` 预检 can have been approved into a 迁移运行 at all.
      conclusion: 'SUPPORTED',
      evaluatedAt: run.startedAt,
      findings: [],
      largeRecordTable: false,
      largestValueBytes: null,
      largestRowBytes: null,
    },
    // The 表写入契约, its 结构证明 and the 校验执行 belong to the single-table evidence
    // view (#39/#40) and are not assembled by 运行监控; the monitor shows phase, progress,
    // technical result and observation time, and says nothing it has not read.
    tableWriteContract: null,
    structuralProof: null,
    sourceBaselineRowCount: unit.baselineRowCount,
    progress: hasProgress
      ? {
          observedAt: new Date(startedAtMs + observedQuantum * interval).toISOString(),
          sourceRowsRead: Math.round(unit.baselineRowCount * fraction),
          targetRowsWritten: Math.round(unit.baselineRowCount * writtenFraction),
          lastProgressAt: movement === null ? null : at(movement),
        }
      : null,
    latestValidationExecutionId: null,
  };
}

/** ADR-0004: the run's status is a deterministic projection of its units. */
function runStatusAt(
  plan: RunPlan,
  q: number,
  cancelledAt: number | null,
  stuckAt: number | null,
): MigrationRunStatus {
  if (cancelledAt !== null && q >= cancelledAt) {
    return q >= cancelledAt + CANCELLATION_DRAIN_QUANTA ? 'CANCELLED' : 'CANCELLING';
  }
  if (stuckAt !== null) {
    return 'ATTENTION_REQUIRED';
  }
  const states = plan.units.map((unit) => unitStateAt(unit, q, cancelledAt, stuckAt));
  if (states.every((state) => state.phase === 'TERMINAL')) {
    if (states.some((state) => state.outcome === 'FAILED')) {
      return 'COMPLETED_WITH_FAILURES';
    }
    if (states.some((state) => state.outcome === 'COMPLETED_WITH_ACCEPTED_RISK')) {
      return 'COMPLETED_WITH_ACCEPTED_RISK';
    }
    return 'COMPLETED';
  }
  return states.every((state) => state.phase === 'WAITING_FOR_BOX') ? 'PREPARING' : 'RUNNING';
}

/** The quantum at which the run stopped, or null while it is still going. */
function terminalQuantum(
  plan: RunPlan,
  q: number,
  cancelledAt: number | null,
  stuckAt: number | null,
): number | null {
  if (cancelledAt !== null && q >= cancelledAt + CANCELLATION_DRAIN_QUANTA) {
    return cancelledAt + CANCELLATION_DRAIN_QUANTA;
  }
  if (stuckAt !== null) {
    // A 卡死 run has stopped moving but has not ended: DBX preserves target data and
    // diagnostic evidence and waits for a decision, so there is no end time to state.
    return null;
  }
  const last = Math.max(...plan.units.map((unit) => unit.validationEndsAt));
  return q >= last ? last : null;
}

function buildEvents(
  plan: RunPlan,
  q: number,
  cancelledAt: number | null,
  stuckAt: number | null,
  at: (quantum: number) => IsoTimestamp,
  status: MigrationRunStatus,
): readonly RunEvent[] {
  const events: RunEvent[] = [];
  const push = (quantum: number, event: Omit<RunEvent, 'id' | 'occurredAt'>): void => {
    if (quantum <= q) {
      events.push({
        id: `${event.unitId ?? 'run'}-${event.type}-${quantum}-${events.length}`,
        occurredAt: at(quantum),
        ...event,
      });
    }
  };

  for (const unit of plan.units) {
    const base = { unitId: unit.id, sourceTable: unit.sourceTable, runStatus: null };
    const phaseEvent = (quantum: number, phase: TableMigrationPhase): void =>
      push(quantum, { ...base, type: 'PHASE_ENTERED', phase, outcome: null });

    phaseEvent(0, 'WAITING_FOR_BOX');
    phaseEvent(unit.admittedAt, 'CREATING_TARGET');
    phaseEvent(unit.transferStartsAt, 'TRANSFERRING');

    const state = unitStateAt(unit, q, cancelledAt, stuckAt);
    if (state.phase === 'TERMINAL' && state.outcome !== null) {
      const terminalAt = state.frozenAt ?? unit.validationEndsAt;
      if (state.outcome === unit.outcome && unit.failsAt === null) {
        phaseEvent(unit.transferEndsAt, 'VALIDATING');
        phaseEvent(unit.validationEndsAt, 'TERMINAL');
        push(unit.validationEndsAt, {
          ...base,
          type: 'OUTCOME_RECORDED',
          phase: null,
          outcome: state.outcome,
        });
      } else {
        phaseEvent(terminalAt, 'TERMINAL');
        push(terminalAt, {
          ...base,
          type: 'OUTCOME_RECORDED',
          phase: null,
          outcome: state.outcome,
        });
      }
    }
  }

  if (cancelledAt !== null) {
    push(cancelledAt, {
      unitId: null,
      sourceTable: null,
      type: 'CANCELLATION_REQUESTED',
      phase: null,
      outcome: null,
      runStatus: 'CANCELLING',
    });
  }

  if (stuckAt !== null) {
    push(stuckAt, {
      unitId: null,
      sourceTable: null,
      type: 'STUCK_DIAGNOSED',
      phase: null,
      outcome: null,
      runStatus: 'ATTENTION_REQUIRED',
    });
  }

  push(q, {
    unitId: null,
    sourceTable: null,
    type: 'RUN_STATUS_CHANGED',
    phase: null,
    outcome: null,
    runStatus: status,
  });

  return events.sort((a, b) => b.occurredAt.localeCompare(a.occurredAt, 'en'));
}

/**
 * The outcome as the log names it.
 *
 * `BLOCKED_BY_BOX_FAILURE` is written out as the glossary's own English term for the same
 * concept — **Blocked by an upstream failure** — because Gate 7 binds server-produced
 * evidence too: a log a DBA pastes into a ticket must not be where the execution platform
 * finally introduces itself. Nothing is invented here; `CONTEXT.md` names the term.
 */
function logOutcomeToken(outcome: TableMigrationOutcome): string {
  return outcome === 'BLOCKED_BY_BOX_FAILURE'
    ? 'blocked_by_upstream_failure'
    : outcome.toLowerCase();
}

/**
 * The run's technical log: one line per delivered observation, most recent first.
 *
 * Table-centric by construction. The execution platform's own vocabulary — its scheduling
 * groups, its connectors, its topics — does not appear, because an operator reading this
 * log is not required to understand any of it (ADR-0007, Gate 7).
 */
function buildLog(
  plan: RunPlan,
  q: number,
  cancelledAt: number | null,
  stuckAt: number | null,
  at: (quantum: number) => IsoTimestamp,
): readonly RunLogLine[] {
  const lines: RunLogLine[] = [];
  for (const unit of plan.units) {
    const state = unitStateAt(unit, q, cancelledAt, stuckAt);
    const last = Math.min(state.frozenAt ?? q, q - unit.observationLagQuanta);
    for (let quantum = unit.transferStartsAt; quantum <= last; quantum += 1) {
      const read = Math.round(unit.baselineRowCount * readFractionAt(unit, quantum));
      const written = Math.round(unit.baselineRowCount * readFractionAt(unit, quantum - 1));
      const moved = read - Math.round(unit.baselineRowCount * readFractionAt(unit, quantum - 1));
      lines.push({
        at: at(quantum),
        sourceTable: unit.sourceTable,
        text:
          `${unit.sourceTable} read=${read}/${unit.baselineRowCount} ` +
          `written=${written} delta=${moved}`,
      });
    }
    if (state.phase === 'TERMINAL' && state.outcome !== null) {
      lines.push({
        at: at(state.frozenAt ?? unit.validationEndsAt),
        sourceTable: unit.sourceTable,
        text: `${unit.sourceTable} terminal outcome=${logOutcomeToken(state.outcome)}`,
      });
    }
  }
  if (stuckAt !== null) {
    for (const unit of plan.units.filter((entry) => entry.stallsAt !== null)) {
      lines.push({
        at: at(stuckAt),
        sourceTable: unit.sourceTable,
        text:
          `${unit.sourceTable} no observable progress for ` +
          `${STUCK_THRESHOLD_MOCK_MS / 60_000} minutes; hard threshold exceeded`,
      });
    }
  }
  return lines.sort((a, b) => b.at.localeCompare(a.at, 'en'));
}

/** The seeded 迁移运行's start instant: far enough back to be past its own turning point. */
export function seededRunStartedAt(clock: ControllableClock): IsoTimestamp {
  return new Date(
    clock.now() - SEEDED_RUN_ELAPSED_QUANTA * OBSERVATION_INTERVAL_MOCK_MS,
  ).toISOString();
}

export interface SeededMonitoredRun {
  readonly task: MigrationTask;
  readonly run: MigrationRun;
  readonly plan: RunPlan;
}

/**
 * The 迁移任务 and 迁移运行 that 运行监控 is entered through, seeded at boot.
 *
 * Lead decision D22, applied to this stage: `/runs/run-monitored?scenario=stuck-table` has
 * to land on a run that is already in the state under review. Walking the wizard to reach
 * one would mean four navigations, a selection out of 1200 tables and then waiting — and a
 * scenario parameter that only survives to first paint is not a deep link.
 */
export function seedMonitoredRun(
  seedPlan: SeedPlan,
  seed: number,
  clock: ControllableClock,
): SeededMonitoredRun | null {
  if (seedPlan.migrationTasks === 'none') {
    return null;
  }

  const sourceDatabase = 'orders';
  const targetSchema = 'orders_live';
  const plan = buildRunPlan({ seed, runPlan: seedPlan.runPlan, sourceDatabase });
  const startedAt = seededRunStartedAt(clock);
  const accountableOperator = 'zhang.wei';

  const run: MigrationRun = {
    id: MONITORED_RUN_ID,
    taskId: MONITORED_TASK_ID,
    // The stored record carries the status the run started with. Everything later is a
    // projection of its units, computed on read rather than written back into it.
    status: 'PREPARING',
    startedAt,
    endedAt: null,
    sourceConnectionId: 'conn-mysql-orders',
    sourceDatabase,
    targetConnectionId: 'conn-pg-analytics',
    targetSchema,
    writeFreeze: {
      accountableOperator,
      confirmedAt: startedAt,
      expiresAt: new Date(Date.parse(startedAt) + 8 * 60 * 60 * 1000).toISOString(),
      scope: sourceDatabase,
      changeReference: 'CHG-2026-0901-1',
      declaredBrokenAt: null,
    },
    sourceBaseline: { capturedAt: startedAt, entries: runPlanBaselineEntries(plan) },
    selectedTableCount: plan.units.length,
    excludedTableCount: 2,
    cancellationRequestedAt: null,
  };

  const task: MigrationTask = {
    id: MONITORED_TASK_ID,
    name: `${sourceDatabase} → ${targetSchema}`,
    databasePair: { sourceDialect: 'MYSQL_8_0', targetDialect: 'POSTGRESQL_15' },
    sourceConnectionId: run.sourceConnectionId,
    sourceDatabase,
    targetConnectionId: run.targetConnectionId,
    targetSchema,
    approvedAt: startedAt,
    approvedBy: accountableOperator,
    selectedTableCount: plan.units.length,
    runCount: 1,
    latestRunId: run.id,
    latestRunStatus: run.status,
  };

  return { task, run, plan };
}
