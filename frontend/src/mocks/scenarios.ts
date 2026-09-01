import { DEFAULT_CLOCK_RATE, normaliseRate } from './clock';

/**
 * The URL scenario parameter (ADR-0016).
 *
 * Every mocked state DBX can be in is reachable by opening a URL: `?scenario=error` on any
 * route puts that route into its error state, `?scenario=stuck-table` runs the migration
 * that gets stuck. This is what makes failure states reviewable and deep-linkable, and it
 * is the reason the state-coverage matrix (#42) can be driven entirely from seam ①.
 *
 * The registry is deliberately shaped around two orthogonal ideas:
 *
 *  - **what the store boots with** (`seed`) — expresses empty, blocked, inconclusive and
 *    disposed, because those are facts about data;
 *  - **how the transport behaves** (`transport`) — expresses loading and error, because
 *    those are facts about a request, not about data.
 *
 * A scenario that needed a third idea would be a sign that a view is reading state from
 * somewhere other than the contract.
 */

/** The contract resources a scenario can seed or fault, keyed as the store holds them. */
export type MockResource =
  | 'databaseConnections'
  | 'migrationDrafts'
  | 'migrationTasks'
  | 'migrationRuns'
  | 'tableMigrationUnits'
  /**
   * 逐表配置与预检 (#35): the per-table summaries and single-table workspace of a
   * 迁移草稿. Faulted apart from `tableMigrationUnits` because a draft has no 迁移运行 and
   * therefore no 表迁移单元 — and because stage three has to be reachable while the
   * discovery read behind stage two is healthy.
   */
  | 'draftTableConfigurations'
  | 'validationExecutions';

/**
 * How the mock transport misbehaves for one resource.
 *
 * `pending` never settles, which is how a loading state is reached deterministically —
 * far steadier than racing a timer.
 */
export type TransportFault =
  | { readonly kind: 'pending' }
  | { readonly kind: 'slow'; readonly realMilliseconds: number }
  | { readonly kind: 'failure'; readonly status: number };

/** The six states #42 requires every key view to reach from a URL. */
export type StateCoverage = 'loading' | 'empty' | 'error' | 'blocked' | 'inconclusive' | 'disposed';

/** What the store is seeded with when a scenario boots. */
export interface SeedPlan {
  readonly databaseConnections: 'standard' | 'none' | 'unchecked';
  readonly migrationTasks: 'standard' | 'none';
  /**
   * Whether the store boots with a 迁移草稿 already parked at 逐表配置与预检.
   *
   * A scenario is only reachable while its parameter is on the URL, and client-side
   * navigation drops it — so a stage the operator would normally *walk* to cannot be
   * reached in a faulted scenario unless the draft is already there when the page loads.
   * Seeding one gives stage three (and, later, 执行确认) a deep-linkable entry with a
   * deterministic identifier. Default scenarios seed none: an empty 迁移任务 page is a
   * state of its own, and a phantom draft would take it away.
   */
  readonly migrationDrafts: 'none' | 'ready-for-tables';
  /**
   * The shape a migration run takes as the clock advances. Consumed from #38 onwards;
   * declared here because the run scenarios ADR-0016 requires are part of *this* registry,
   * not a second one bolted on later.
   */
  readonly runPlan:
    | 'all-tables-succeed'
    | 'partial-table-failure'
    | 'stuck-table'
    | 'operator-cancellation'
    | 'inconclusive-validation'
    | 'accepted-risk';
  /** Whether preflight seeds blocking findings (`UNSUPPORTED` / `INCONCLUSIVE`). */
  readonly preflight: 'all-supported' | 'blocked';
}

export interface ScenarioDefinition {
  readonly id: string;
  /** Engineering description. Scenarios are infrastructure and are never rendered. */
  readonly summary: string;
  /** Seed for the deterministic fixture generators (#33 owns the 1200-table one). */
  readonly seed: number;
  readonly clockRate: number;
  /**
   * Which draft persistence the store uses. Every scenario but the default one is memory
   * backed, so a review or a test run cannot poison the next one (#32 lead decision D3).
   */
  readonly draftPersistence: 'browser' | 'memory';
  readonly seedPlan: SeedPlan;
  readonly transport: Partial<Record<MockResource, TransportFault>>;
  /** The coverage states this scenario is the entry point for. */
  readonly covers: readonly StateCoverage[];
}

const standardSeedPlan: SeedPlan = {
  databaseConnections: 'standard',
  migrationTasks: 'standard',
  migrationDrafts: 'none',
  runPlan: 'all-tables-succeed',
  preflight: 'all-supported',
};

function scenario(definition: ScenarioDefinition): ScenarioDefinition {
  return definition;
}

/** The default scenario id, used when the URL names none. */
export const DEFAULT_SCENARIO_ID = 'default';

export const SCENARIO_PARAM = 'scenario';
export const CLOCK_RATE_PARAM = 'clockRate';

const definitions: readonly ScenarioDefinition[] = [
  scenario({
    id: DEFAULT_SCENARIO_ID,
    summary: 'Registered connections, approved tasks, and a run in which every table succeeds.',
    seed: 20260901,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'browser',
    seedPlan: standardSeedPlan,
    transport: {},
    covers: [],
  }),
  scenario({
    id: 'empty',
    summary: 'A first-run installation: nothing has been registered yet.',
    seed: 20260901,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, databaseConnections: 'none', migrationTasks: 'none' },
    transport: {},
    covers: ['empty'],
  }),
  scenario({
    id: 'loading',
    summary: 'Every read hangs, so every view stays in its loading state for inspection.',
    seed: 20260901,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: standardSeedPlan,
    transport: {
      databaseConnections: { kind: 'pending' },
      migrationDrafts: { kind: 'pending' },
      migrationTasks: { kind: 'pending' },
      migrationRuns: { kind: 'pending' },
      tableMigrationUnits: { kind: 'pending' },
      draftTableConfigurations: { kind: 'pending' },
      validationExecutions: { kind: 'pending' },
    },
    covers: ['loading'],
  }),
  scenario({
    id: 'error',
    summary: 'Every read fails, so every view must offer a retry rather than a blank page.',
    seed: 20260901,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: standardSeedPlan,
    transport: {
      databaseConnections: { kind: 'failure', status: 503 },
      migrationDrafts: { kind: 'failure', status: 503 },
      migrationTasks: { kind: 'failure', status: 503 },
      migrationRuns: { kind: 'failure', status: 503 },
      tableMigrationUnits: { kind: 'failure', status: 503 },
      draftTableConfigurations: { kind: 'failure', status: 503 },
      validationExecutions: { kind: 'failure', status: 503 },
    },
    covers: ['error'],
  }),
  scenario({
    id: 'blocked-preflight',
    summary: 'Preflight blocks tables with UNSUPPORTED and INCONCLUSIVE conclusions.',
    seed: 20260902,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, preflight: 'blocked' },
    transport: {},
    covers: ['blocked'],
  }),
  // Stage three on its own: the 迁移草稿 and the discovery read behind 迁移范围 stay
  // healthy, so the wizard can be walked as far as 逐表配置与预检 and left in each state.
  scenario({
    id: 'stage-tables-loading',
    summary: 'The per-table configuration read hangs, holding stage three in its loading state.',
    seed: 20260901,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, migrationDrafts: 'ready-for-tables' },
    transport: { draftTableConfigurations: { kind: 'pending' } },
    covers: ['loading'],
  }),
  scenario({
    id: 'stage-tables-error',
    summary: 'The per-table configuration read fails, so stage three must offer a retry.',
    seed: 20260901,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, migrationDrafts: 'ready-for-tables' },
    transport: { draftTableConfigurations: { kind: 'failure', status: 503 } },
    covers: ['error'],
  }),
  scenario({
    id: 'partial-table-failure',
    summary: 'A run in which some tables fail while others succeed.',
    seed: 20260903,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, runPlan: 'partial-table-failure' },
    transport: {},
    covers: [],
  }),
  scenario({
    id: 'stuck-table',
    summary: 'A run that stops advancing while its connectors still report healthy.',
    seed: 20260904,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, runPlan: 'stuck-table' },
    transport: {},
    covers: [],
  }),
  scenario({
    id: 'operator-cancellation',
    summary: 'A run the operator cancels part way through.',
    seed: 20260905,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, runPlan: 'operator-cancellation' },
    transport: {},
    covers: [],
  }),
  scenario({
    id: 'inconclusive-validation',
    summary: 'A run whose validation cannot conclude, so INCONCLUSIVE has to hold its own form.',
    seed: 20260906,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, runPlan: 'inconclusive-validation' },
    transport: {},
    covers: ['inconclusive'],
  }),
  scenario({
    id: 'accepted-risk',
    summary: 'A validation disposition has been recorded; the technical result is unchanged.',
    seed: 20260907,
    clockRate: DEFAULT_CLOCK_RATE,
    draftPersistence: 'memory',
    seedPlan: { ...standardSeedPlan, runPlan: 'accepted-risk' },
    transport: {},
    covers: ['disposed'],
  }),
];

export const scenarios: ReadonlyMap<string, ScenarioDefinition> = new Map(
  definitions.map((entry) => [entry.id, entry]),
);

export const defaultScenario: ScenarioDefinition = definitions[0] as ScenarioDefinition;

export interface ResolvedScenario {
  readonly definition: ScenarioDefinition;
  /** The clock rate in force, after any `clockRate` override in the URL. */
  readonly clockRate: number;
  /** Whether the URL named a scenario at all. */
  readonly requested: boolean;
  /** Set when the URL named a scenario that does not exist. */
  readonly unknownScenarioId: string | null;
  /** Identity of this resolution; the mock context rebuilds when it changes. */
  readonly key: string;
}

/**
 * Read the scenario out of a URL query string.
 *
 * An unknown id falls back to the default scenario but is reported rather than swallowed:
 * a mistyped scenario in a review link should be visible, not silently ignored.
 */
export function resolveScenario(search: string): ResolvedScenario {
  const params = new URLSearchParams(search);
  const requestedId = params.get(SCENARIO_PARAM);
  const found = requestedId === null ? undefined : scenarios.get(requestedId);
  const definition = found ?? defaultScenario;

  const rateParam = params.get(CLOCK_RATE_PARAM);
  const clockRate =
    rateParam === null ? definition.clockRate : normaliseRate(Number.parseFloat(rateParam));

  return {
    definition,
    clockRate,
    requested: requestedId !== null,
    unknownScenarioId: requestedId !== null && found === undefined ? requestedId : null,
    key: `${definition.id}@${clockRate}`,
  };
}

/** All scenario ids, for documentation and for #42's coverage matrix. */
export function scenarioIds(): readonly string[] {
  return definitions.map((entry) => entry.id);
}
