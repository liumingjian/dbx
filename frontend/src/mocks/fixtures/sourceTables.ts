import type { SourceTableSummary } from '@/contract';

/**
 * The production-scale source-table fixture.
 *
 * A four-table demonstration cannot answer the question the 迁移范围 stage exists to
 * answer — whether choosing tables in a 1200-table production database is still workable —
 * so this generator produces that scale deterministically from a fixed seed. Determinism
 * is a hard requirement rather than tidiness (#30 Testing Decisions): without it two
 * screenshots cannot be compared, a review link cannot reproduce a particular table's
 * state, and every test that touches the fixture flakes.
 *
 * The mix is the one #30 fixed: about 85% pass automatic mapping, 8% carry structured
 * mapping exceptions, 4% are 大记录表, and 3% are blocked or inconclusive at preflight.
 * Those are the *primary* categories, assigned by `sourceTableCategory` in severity order,
 * and tables deliberately overlap inside the more severe ones — a table that is both a
 * 大记录表 and carries mapping exceptions is exactly the case a per-table interface is
 * most likely to render only half of (user story 47).
 */

/** The production scale #30 fixed. */
export const SOURCE_TABLE_FIXTURE_SIZE = 1200;

/**
 * A small, fast, well-distributed PRNG with a 32-bit state (mulberry32).
 *
 * Written out rather than taken from a dependency because the fixture's value is that the
 * same seed produces the same bytes forever: a transitive upgrade that improved an
 * algorithm would silently invalidate every stored screenshot.
 */
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

const domains = [
  'order',
  'customer',
  'payment',
  'inventory',
  'shipment',
  'catalog',
  'billing',
  'account',
  'promotion',
  'settlement',
  'audit',
  'warehouse',
  'refund',
  'ledger',
  'subscription',
  'notification',
] as const;

const entities = [
  'item',
  'header',
  'detail',
  'event',
  'attachment',
  'snapshot',
  'history',
  'mapping',
  'profile',
  'address',
  'invoice',
  'batch_log',
  'attribute',
  'reference',
  'summary',
] as const;

const qualifiers = ['', '_archive', '_2023', '_2024', '_daily', '_tmp', '_v2', '_ext'] as const;

function pick<T>(values: readonly T[], random: () => number): T {
  // `noUncheckedIndexedAccess` makes the fallback explicit rather than assumed.
  const index = Math.floor(random() * values.length);
  return values[index] ?? (values[0] as T);
}

export type SourceTableCategory =
  'automaticMapping' | 'mappingException' | 'largeRecord' | 'preflightBlocked';

/**
 * The primary category of one table, derived from its own facts in severity order.
 *
 * Deriving it rather than storing it is what keeps the proportions honest: the summary
 * counts what the tables actually say, so a generator that drifted could not keep claiming
 * the mix it was asked for.
 */
export function sourceTableCategory(table: SourceTableSummary): SourceTableCategory {
  if (table.preflightConclusion !== 'SUPPORTED') return 'preflightBlocked';
  if (table.largeRecordTable) return 'largeRecord';
  if (table.mappingExceptionCount > 0) return 'mappingException';
  return 'automaticMapping';
}

export interface SourceTableMix {
  readonly total: number;
  readonly automaticMapping: number;
  readonly mappingException: number;
  readonly largeRecord: number;
  readonly preflightBlocked: number;
  /** Tables hitting two or more of the three conditions at once (user story 47). */
  readonly multiCondition: number;
}

export function summariseSourceTables(tables: readonly SourceTableSummary[]): SourceTableMix {
  const counts = { automaticMapping: 0, mappingException: 0, largeRecord: 0, preflightBlocked: 0 };
  let multiCondition = 0;
  for (const table of tables) {
    counts[sourceTableCategory(table)] += 1;
    const conditions =
      (table.largeRecordTable ? 1 : 0) +
      (table.mappingExceptionCount > 0 ? 1 : 0) +
      (table.preflightConclusion === 'SUPPORTED' ? 0 : 1);
    if (conditions >= 2) multiCondition += 1;
  }
  return { total: tables.length, ...counts, multiCondition };
}

export interface SourceTableFixtureOptions {
  /** The scenario's seed. The same seed always produces byte-identical output. */
  readonly seed: number;
  readonly count?: number;
  readonly sourceDatabase?: string;
}

/** Deterministic Fisher–Yates, so the categories are spread rather than blocked together. */
function shuffle<T>(values: T[], random: () => number): T[] {
  for (let index = values.length - 1; index > 0; index -= 1) {
    const swap = Math.floor(random() * (index + 1));
    const left = values[index] as T;
    const right = values[swap] as T;
    values[index] = right;
    values[swap] = left;
  }
  return values;
}

export function generateSourceTables({
  seed,
  count = SOURCE_TABLE_FIXTURE_SIZE,
  sourceDatabase = 'orders',
}: SourceTableFixtureOptions): readonly SourceTableSummary[] {
  const random = mulberry32(seed);

  const blockedCount = Math.round(count * 0.03);
  const largeRecordCount = Math.round(count * 0.04);
  const mappingExceptionCount = Math.round(count * 0.08);
  const automaticCount = count - blockedCount - largeRecordCount - mappingExceptionCount;

  const plan: SourceTableCategory[] = shuffle(
    [
      ...Array.from({ length: automaticCount }, () => 'automaticMapping' as const),
      ...Array.from({ length: mappingExceptionCount }, () => 'mappingException' as const),
      ...Array.from({ length: largeRecordCount }, () => 'largeRecord' as const),
      ...Array.from({ length: blockedCount }, () => 'preflightBlocked' as const),
    ],
    random,
  );

  const used = new Set<string>();
  const tables: SourceTableSummary[] = [];
  let blockedSeen = 0;
  let largeSeen = 0;

  for (const category of plan) {
    let name = `${pick(domains, random)}_${pick(entities, random)}${pick(qualifiers, random)}`;
    if (used.has(name)) {
      // Production schemas really do contain `order_item` and `order_item_2`; a numeric
      // suffix is truer than reaching for another random draw.
      let suffix = 2;
      while (used.has(`${name}_${suffix}`)) suffix += 1;
      name = `${name}_${suffix}`;
    }
    used.add(name);

    const columnCount = 6 + Math.floor(random() * 60);
    const estimatedRowCount = Math.floor(random() * random() * 90_000_000);
    const estimatedBytes = estimatedRowCount * (80 + Math.floor(random() * 900));

    let mappingExceptions = 0;
    let largeRecordTable = false;
    let conclusion: SourceTableSummary['preflightConclusion'] = 'SUPPORTED';
    let blockingFindings = 0;

    if (category === 'mappingException') {
      mappingExceptions = 1 + Math.floor(random() * 5);
    }

    if (category === 'largeRecord') {
      largeRecordTable = true;
      // A quarter of the 大记录表 also carry mapping exceptions: the interface has to be
      // able to say both things about one table, not pick the more dramatic one.
      if (largeSeen % 4 === 0) mappingExceptions = 1 + Math.floor(random() * 4);
      largeSeen += 1;
    }

    if (category === 'preflightBlocked') {
      conclusion = blockedSeen % 2 === 0 ? 'UNSUPPORTED' : 'INCONCLUSIVE';
      blockingFindings = conclusion === 'UNSUPPORTED' ? 1 + Math.floor(random() * 3) : 0;
      if (blockedSeen % 3 === 0) largeRecordTable = true;
      if (blockedSeen % 4 === 0) mappingExceptions = 1 + Math.floor(random() * 4);
      // The first blocked table hits all three conditions at once, so the case exists in
      // every generated fixture rather than only in a lucky seed.
      if (blockedSeen === 0) {
        largeRecordTable = true;
        mappingExceptions = Math.max(mappingExceptions, 2);
      }
      blockedSeen += 1;
    }

    tables.push({
      name,
      sourceDatabase,
      columnCount,
      estimatedRowCount,
      estimatedBytes,
      mappingExceptionCount: mappingExceptions,
      largeRecordTable,
      largestValueBytes: largeRecordTable ? 1_048_576 + Math.floor(random() * 18_000_000) : null,
      preflightConclusion: conclusion,
      preflightBlockingFindingCount: blockingFindings,
    });
  }

  // Deterministic ordering by name: the same database opens the same way twice, which is
  // what makes two screenshots comparable (user story 31).
  return [...tables].sort((left, right) => left.name.localeCompare(right.name, 'en'));
}
