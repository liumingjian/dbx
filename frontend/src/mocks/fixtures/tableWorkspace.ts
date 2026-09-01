import type {
  DraftMappingRule,
  PreflightFindingCode,
  DraftTableConfiguration,
  DraftTableWorkspace,
  MappingException,
  MappingRule,
  MappingRuleOption,
  Preflight,
  PreflightFinding,
  SourceTableSummary,
  TableObjectNode,
  TableWriteContract,
  TableWriteContractColumn,
} from '@/contract';

/**
 * The single-table fixture behind 逐表配置与预检.
 *
 * Everything here is derived from a `SourceTableSummary` and a fixed seed, so the same
 * table opens the same way twice — the same requirement that governs the 1200-table
 * generator, for the same reason: a screenshot of a table's DDL has to be comparable with
 * the next one, and a review link has to reproduce a particular table's exceptions.
 *
 * The shape follows ADR-0011 rather than convenience. The source table is rendered as
 * MySQL reports it; the 表写入契约 is assembled from the source metadata plus the
 * 映射规则 in force; the target DDL is generated **from the contract** and from nothing
 * else. Objects outside the v1 writable-table boundary — unique constraints other than the
 * primary key, ordinary indexes, foreign keys, comments — are preserved as 补建 SQL and are
 * never part of the migration path.
 */

/** FNV-1a over the table's identity, so every table has its own reproducible stream. */
function hashOf(text: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

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

/**
 * The one exception DBX refuses to decide on the operator's behalf.
 *
 * ADR-0011 allows `NOT NULL` 「except the approved per-column zero-date relaxation」. The
 * word is *approved*: a relaxation the platform granted itself would not be one. So a table
 * carrying a zero-date default has no complete 表写入契约 until a person chooses, and this
 * predicate is what says which tables those are — cheaply enough to answer for all 1200.
 *
 * The column is always named `deleted_at`, so the summary and the full workspace agree
 * without the summary having to generate every column of every table.
 */
export const ZERO_DATE_COLUMN = 'deleted_at';

export function requiresZeroDateDecision(seed: number, table: SourceTableSummary): boolean {
  return table.mappingExceptionCount > 0 && hashOf(`${seed}:${table.name}:zero-date`) % 4 === 0;
}

interface SourceColumn {
  readonly name: string;
  readonly sourceType: string;
  readonly nullable: boolean;
  readonly sourceDefault: string | null;
  readonly primaryKey: boolean;
  readonly autoIncrement: boolean;
  /** The target type automatic mapping produces, before any 映射规则 is applied. */
  readonly automaticTargetType: string;
  readonly exception: ExceptionSeed | null;
}

interface ExceptionSeed {
  readonly reason: MappingException['reason'];
  readonly action: MappingException['action'];
  readonly options: readonly MappingRuleOption[];
  /** What DBX proposes automatically, or null when it will not choose. */
  readonly platformTargetValue: string | null;
}

const columnNames = [
  'order_id',
  'customer_id',
  'status_code',
  'amount',
  'currency',
  'quantity',
  'created_at',
  'updated_at',
  'remark',
  'external_ref',
  'channel',
  'payload',
  'checksum',
  'version_no',
  'operator',
  'settled_at',
  'region_code',
  'attributes',
  'weight_gram',
  'is_active',
] as const;

interface TypePair {
  readonly source: string;
  readonly target: string;
}

const ordinaryTypes: readonly TypePair[] = [
  { source: 'bigint', target: 'bigint' },
  { source: 'int', target: 'integer' },
  { source: 'smallint', target: 'smallint' },
  { source: 'varchar(64)', target: 'varchar(64)' },
  { source: 'varchar(255)', target: 'varchar(255)' },
  { source: 'char(32)', target: 'char(32)' },
  { source: 'text', target: 'text' },
  { source: 'datetime(3)', target: 'timestamp(3)' },
  { source: 'timestamp', target: 'timestamp(0)' },
  { source: 'decimal(18,4)', target: 'numeric(18,4)' },
  { source: 'tinyint(1)', target: 'boolean' },
  { source: 'json', target: 'jsonb' },
  { source: 'blob', target: 'bytea' },
  { source: 'date', target: 'date' },
  { source: 'double', target: 'double precision' },
];

const enumException: ExceptionSeed = {
  reason: 'ENUM_VALUE_DOMAIN',
  action: 'TARGET_TYPE',
  options: [
    { targetValue: 'text', consequence: 'PRESERVES_VALUE_DOMAIN' },
    { targetValue: 'varchar(32)', consequence: 'FIXED_WIDTH_MAY_TRUNCATE' },
  ],
  platformTargetValue: 'text',
};

const unsignedException: ExceptionSeed = {
  reason: 'UNSIGNED_INTEGER_RANGE',
  action: 'TARGET_TYPE',
  options: [
    { targetValue: 'bigint', consequence: 'WIDER_TARGET_TYPE' },
    { targetValue: 'numeric(20,0)', consequence: 'EXACT_NUMERIC_TARGET' },
  ],
  platformTargetValue: 'bigint',
};

const identityException: ExceptionSeed = {
  reason: 'AUTO_INCREMENT_IDENTITY',
  action: 'IDENTITY',
  options: [
    { targetValue: 'GENERATED BY DEFAULT AS IDENTITY', consequence: 'PLATFORM_OWNED_IDENTITY' },
    { targetValue: 'OWNED SEQUENCE', consequence: 'OWNED_EXPLICIT_SEQUENCE' },
  ],
  platformTargetValue: 'GENERATED BY DEFAULT AS IDENTITY',
};

/** The one exception with no platform proposal: see `requiresZeroDateDecision`. */
const zeroDateException: ExceptionSeed = {
  reason: 'ZERO_DATE_DEFAULT',
  action: 'NULLABILITY',
  options: [
    { targetValue: 'NOT NULL', consequence: 'REJECTS_ZERO_DATE' },
    { targetValue: 'NULL', consequence: 'APPROVED_ZERO_DATE_RELAXATION' },
  ],
  platformTargetValue: null,
};

function sourceColumnsOf(seed: number, table: SourceTableSummary): readonly SourceColumn[] {
  const random = mulberry32(hashOf(`${seed}:${table.name}`));
  const zeroDate = requiresZeroDateDecision(seed, table);
  const identity = table.mappingExceptionCount > 0 && hashOf(`${seed}:${table.name}:id`) % 3 === 0;

  const ordinaryCount = Math.max(4, table.columnCount - 1 - (zeroDate ? 1 : 0));
  const columns: SourceColumn[] = [
    {
      name: 'id',
      sourceType: identity ? 'bigint unsigned' : 'bigint',
      nullable: false,
      sourceDefault: null,
      primaryKey: true,
      autoIncrement: true,
      automaticTargetType: identity ? 'numeric(20,0)' : 'bigint',
      exception: identity ? identityException : null,
    },
  ];

  const used = new Set(['id', ZERO_DATE_COLUMN]);
  for (let index = 0; index < ordinaryCount; index += 1) {
    const base = columnNames[index % columnNames.length] as string;
    let name = base;
    let suffix = 2;
    while (used.has(name)) {
      name = `${base}_${suffix}`;
      suffix += 1;
    }
    used.add(name);
    const pair = ordinaryTypes[Math.floor(random() * ordinaryTypes.length)] as TypePair;
    columns.push({
      name,
      sourceType: pair.source,
      nullable: false,
      sourceDefault:
        pair.source === 'timestamp'
          ? 'CURRENT_TIMESTAMP'
          : pair.source === 'int' || pair.source === 'bigint'
            ? '0'
            : null,
      primaryKey: false,
      autoIncrement: false,
      automaticTargetType: pair.target,
      exception: null,
    });
  }

  if (zeroDate) {
    columns.push({
      name: ZERO_DATE_COLUMN,
      sourceType: 'datetime(3)',
      nullable: false,
      sourceDefault: "'0000-00-00 00:00:00'",
      primaryKey: false,
      autoIncrement: false,
      automaticTargetType: 'timestamp(3)',
      exception: zeroDateException,
    });
  }

  // The remaining structured exceptions land on ordinary columns, whose source type is
  // rewritten to the one that produced the exception — the fixture is a source database,
  // so an exception has to have a cause in the source metadata rather than a label.
  let remaining = table.mappingExceptionCount - (zeroDate ? 1 : 0) - (identity ? 1 : 0);
  let placed = 0;
  for (let index = 1; index < columns.length && remaining > 0; index += 1) {
    const column = columns[index] as SourceColumn;
    if (column.exception !== null || column.name === ZERO_DATE_COLUMN) {
      continue;
    }
    const asEnum = placed % 2 === 0;
    columns[index] = {
      ...column,
      sourceType: asEnum ? "enum('pending','paid','shipped','cancelled')" : 'int unsigned',
      sourceDefault: null,
      automaticTargetType: asEnum ? 'text' : 'bigint',
      exception: asEnum ? enumException : unsignedException,
    };
    placed += 1;
    remaining -= 1;
  }

  return columns;
}

/** The rule in force for one coordinate: the user's if there is one, else DBX's. */
function ruleInForce(
  column: SourceColumn,
  userRules: readonly DraftMappingRule[],
): MappingRule | null {
  const seed = column.exception;
  if (seed === null) {
    return null;
  }
  const user = userRules.find(
    (rule) => rule.sourceColumn === column.name && rule.action === seed.action,
  );
  if (user !== undefined) {
    return {
      id: `${column.name}:${seed.action}`,
      sourceColumn: column.name,
      action: seed.action,
      targetValue: user.targetValue,
      origin: 'USER',
    };
  }
  if (seed.platformTargetValue === null) {
    return null;
  }
  return {
    id: `${column.name}:${seed.action}`,
    sourceColumn: column.name,
    action: seed.action,
    targetValue: seed.platformTargetValue,
    origin: 'PLATFORM',
  };
}

function quotedMysql(name: string): string {
  return `\`${name}\``;
}

/** ADR-0011: identifiers are preserved character-for-character and always quoted. */
function quotedPostgres(name: string): string {
  return `"${name}"`;
}

interface OutOfContractObject {
  readonly kind: 'UNIQUE_CONSTRAINT' | 'INDEX' | 'FOREIGN_KEY';
  readonly name: string;
  readonly columns: readonly string[];
  readonly references: string | null;
}

function outOfContractObjectsOf(
  table: SourceTableSummary,
  columns: readonly SourceColumn[],
): readonly OutOfContractObject[] {
  const objects: OutOfContractObject[] = [];
  const text = columns.find((column) => column.sourceType.startsWith('varchar'));
  const timestamp = columns.find((column) => column.sourceType.startsWith('datetime'));
  const reference = columns.find((column) => column.name.endsWith('_id') && !column.primaryKey);
  if (text !== undefined) {
    objects.push({
      kind: 'UNIQUE_CONSTRAINT',
      name: `uk_${table.name}_${text.name}`,
      columns: [text.name],
      references: null,
    });
  }
  if (timestamp !== undefined) {
    objects.push({
      kind: 'INDEX',
      name: `idx_${table.name}_${timestamp.name}`,
      columns: [timestamp.name],
      references: null,
    });
  }
  if (reference !== undefined) {
    objects.push({
      kind: 'FOREIGN_KEY',
      name: `fk_${table.name}_${reference.name}`,
      columns: [reference.name],
      references: reference.name.replace(/_id$/, ''),
    });
  }
  return objects;
}

function sourceDdlOf(
  table: SourceTableSummary,
  columns: readonly SourceColumn[],
  objects: readonly OutOfContractObject[],
): string {
  const lines = columns.map((column) => {
    const parts = [`  ${quotedMysql(column.name)} ${column.sourceType}`];
    parts.push(column.nullable ? 'NULL' : 'NOT NULL');
    if (column.sourceDefault !== null) {
      parts.push(`DEFAULT ${column.sourceDefault}`);
    }
    if (column.autoIncrement) {
      parts.push('AUTO_INCREMENT');
    }
    return `${parts.join(' ')},`;
  });

  const primaryKey = columns.filter((column) => column.primaryKey).map((column) => column.name);
  lines.push(`  PRIMARY KEY (${primaryKey.map(quotedMysql).join(', ')}),`);
  for (const object of objects) {
    if (object.kind === 'UNIQUE_CONSTRAINT') {
      lines.push(
        `  UNIQUE KEY ${quotedMysql(object.name)} (${object.columns.map(quotedMysql).join(', ')}),`,
      );
    } else if (object.kind === 'INDEX') {
      lines.push(
        `  KEY ${quotedMysql(object.name)} (${object.columns.map(quotedMysql).join(', ')}),`,
      );
    } else {
      lines.push(
        `  CONSTRAINT ${quotedMysql(object.name)} FOREIGN KEY (${object.columns
          .map(quotedMysql)
          .join(', ')}) REFERENCES ${quotedMysql(object.references ?? '')} (\`id\`),`,
      );
    }
  }

  const body = lines.join('\n').replace(/,$/, '');
  return [
    `CREATE TABLE ${quotedMysql(table.sourceDatabase)}.${quotedMysql(table.name)} (`,
    body,
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;',
  ].join('\n');
}

/** The default whitelist of ADR-0011: literals, and `CURRENT_TIMESTAMP(n)` as local time. */
function targetDefaultOf(column: SourceColumn): string | null {
  if (column.sourceDefault === null) {
    return null;
  }
  if (column.sourceDefault === 'CURRENT_TIMESTAMP') {
    return 'LOCALTIMESTAMP(0)';
  }
  return column.sourceDefault;
}

function contractColumnsOf(
  columns: readonly SourceColumn[],
  rules: ReadonlyMap<string, MappingRule>,
): readonly TableWriteContractColumn[] {
  return columns.map((column) => {
    const rule = rules.get(`${column.name}:TARGET_TYPE`);
    return {
      sourceColumn: column.name,
      sourceType: column.sourceType,
      targetColumn: column.name,
      targetType: rule?.targetValue ?? column.automaticTargetType,
      mappingRuleId: rule?.id ?? null,
    };
  });
}

function targetDdlOf(
  targetSchema: string,
  table: SourceTableSummary,
  columns: readonly SourceColumn[],
  rules: ReadonlyMap<string, MappingRule>,
): string {
  const lines = columns.map((column) => {
    const typeRule = rules.get(`${column.name}:TARGET_TYPE`);
    const nullabilityRule = rules.get(`${column.name}:NULLABILITY`);
    const identityRule = rules.get(`${column.name}:IDENTITY`);
    const parts = [
      `  ${quotedPostgres(column.name)} ${typeRule?.targetValue ?? column.automaticTargetType}`,
    ];
    if (identityRule !== undefined) {
      parts.push(
        identityRule.targetValue === 'OWNED SEQUENCE'
          ? `DEFAULT nextval('${targetSchema}.${table.name}_${column.name}_seq')`
          : 'GENERATED BY DEFAULT AS IDENTITY',
      );
    }
    const nullable = nullabilityRule?.targetValue === 'NULL';
    parts.push(nullable ? 'NULL' : 'NOT NULL');
    const defaultValue = targetDefaultOf(column);
    if (defaultValue !== null && identityRule === undefined) {
      parts.push(`DEFAULT ${defaultValue}`);
    }
    return `${parts.join(' ')},`;
  });

  const primaryKey = columns.filter((column) => column.primaryKey).map((column) => column.name);
  lines.push(
    `  CONSTRAINT ${quotedPostgres(`pk_${table.name}`)} PRIMARY KEY (${primaryKey
      .map(quotedPostgres)
      .join(', ')})`,
  );

  return [
    `CREATE TABLE ${quotedPostgres(targetSchema)}.${quotedPostgres(table.name)} (`,
    lines.join('\n'),
    ');',
  ].join('\n');
}

/**
 * 补建 SQL: delivered, never executed as part of migration (`CONTEXT.md`, ADR-0011).
 *
 * Returning it as part of the contract is what keeps the omission visible. A target that
 * silently lost its indexes and foreign keys would look like a completed structural
 * migration, which v1 explicitly does not perform.
 */
function supplementalSqlOf(
  targetSchema: string,
  table: SourceTableSummary,
  objects: readonly OutOfContractObject[],
): string | null {
  if (objects.length === 0) {
    return null;
  }
  const qualified = `${quotedPostgres(targetSchema)}.${quotedPostgres(table.name)}`;
  return objects
    .map((object) => {
      const columns = object.columns.map(quotedPostgres).join(', ');
      if (object.kind === 'UNIQUE_CONSTRAINT') {
        return `CREATE UNIQUE INDEX ${quotedPostgres(object.name)} ON ${qualified} (${columns});`;
      }
      if (object.kind === 'INDEX') {
        return `CREATE INDEX ${quotedPostgres(object.name)} ON ${qualified} (${columns});`;
      }
      return `ALTER TABLE ${qualified} ADD CONSTRAINT ${quotedPostgres(
        object.name,
      )} FOREIGN KEY (${columns}) REFERENCES ${quotedPostgres(targetSchema)}.${quotedPostgres(
        object.references ?? '',
      )} ("id");`;
    })
    .join('\n');
}

function objectTreeOf(
  columns: readonly SourceColumn[],
  objects: readonly OutOfContractObject[],
  pruned: ReadonlySet<string>,
): readonly TableObjectNode[] {
  const nodes: TableObjectNode[] = columns.map((column) => ({
    id: `column:${column.name}`,
    kind: 'COLUMN',
    name: column.name,
    detail: column.sourceType,
    // A pruned column is outside the write contract by the operator's decision, and stays
    // listed so the decision is reviewable and reversible (ADR-0003).
    inWritableContract: !pruned.has(column.name),
    hasMappingException: column.exception !== null,
    pruned: pruned.has(column.name),
  }));
  const primaryKey = columns.filter((column) => column.primaryKey).map((column) => column.name);
  nodes.push({
    id: 'primary-key',
    kind: 'PRIMARY_KEY',
    name: primaryKey.join(', '),
    detail: primaryKey.join(', '),
    inWritableContract: true,
    hasMappingException: false,
    pruned: false,
  });
  for (const object of objects) {
    nodes.push({
      id: `${object.kind}:${object.name}`,
      kind: object.kind,
      name: object.name,
      detail: object.columns.join(', '),
      // Outside the live v1 contract: preserved as 补建 SQL, not migrated.
      inWritableContract: false,
      hasMappingException: false,
      pruned: false,
    });
  }
  return nodes;
}

/**
 * ADR-0003's two boundaries, as exact byte counts.
 *
 * 「A table becomes a large record table when either boundary exceeds 1 MiB」 and the v1
 * support envelope is 20 MiB per individual value and per row payload. They are written
 * out here because the interface quotes both figures back to the operator.
 */
export const LARGE_RECORD_TABLE_BYTES = 1_048_576;
export const LARGE_RECORD_ENVELOPE_BYTES = 20_971_520;

/**
 * The residual maximum once the column carrying the large value has been pruned.
 *
 * A fixed, small figure rather than a re-derivation: the point being modelled is ADR-0003's
 * rule that DBX 「reruns preflight against the approved selected columns」, so what matters
 * is that the measurement is taken again over what is actually selected.
 */
const ORDINARY_VALUE_BYTES = 4_096;

/** The column the table's largest source value lives in. Deterministic, like everything here. */
function largeValueColumnOf(columns: readonly SourceColumn[]): string | null {
  const wide = columns.find(
    (column) =>
      !column.primaryKey &&
      (column.automaticTargetType === 'bytea' ||
        column.automaticTargetType === 'text' ||
        column.automaticTargetType === 'jsonb'),
  );
  if (wide !== undefined) {
    return wide.name;
  }
  const last = [...columns].reverse().find((column) => !column.primaryKey);
  return last?.name ?? null;
}

/**
 * Which coordinates a value-domain block is found on.
 *
 * A blocking finding has to name a column, or ADR-0003's second exit — 「裁剪超限字段后
 * 重新预检」 — has nothing to act on. The primary key is never chosen: a table cannot be
 * migrated without it, so offering to cut it would be offering an exit that does not exist.
 */
function valueDomainColumnsOf(
  seed: number,
  table: SourceTableSummary,
  columns: readonly SourceColumn[],
): readonly string[] {
  const candidates = columns.filter(
    (column) => !column.primaryKey && column.name !== ZERO_DATE_COLUMN,
  );
  if (candidates.length === 0) {
    return [];
  }
  const chosen: string[] = [];
  for (let index = 0; index < table.preflightBlockingFindingCount; index += 1) {
    const start = hashOf(`${seed}:${table.name}:finding:${index}`) % candidates.length;
    for (let step = 0; step < candidates.length; step += 1) {
      const name = (candidates[(start + step) % candidates.length] as SourceColumn).name;
      if (!chosen.includes(name)) {
        chosen.push(name);
        break;
      }
    }
  }
  return chosen;
}

/** Why a scan could not conclude. A technical literal, as ADR-0003 words it. */
function inconclusiveReasonOf(seed: number, table: SourceTableSummary): string {
  const reasons = ['QUERY_TIMEOUT', 'PERMISSION_DENIED', 'CONNECTION_LOST'] as const;
  return reasons[hashOf(`${seed}:${table.name}:reason`) % reasons.length] as string;
}

function finding(
  code: PreflightFindingCode,
  sourceColumn: string | null,
  blocking: boolean,
  detail: string,
): PreflightFinding {
  return { code, sourceColumn, blocking, detail };
}

interface PreflightInputs {
  readonly seed: number;
  readonly table: SourceTableSummary;
  /** Every source column, pruned ones included. */
  readonly columns: readonly SourceColumn[];
  readonly pruned: ReadonlySet<string>;
  readonly rules: ReadonlyMap<string, MappingRule>;
  readonly evaluatedAt: string;
  /** True while the scan is running again and has not concluded yet. */
  readonly inFlight: boolean;
}

/**
 * The 预检, computed from the source facts, the 映射规则 in force and the selected columns.
 *
 * **This is what makes a rerun mean something.** ADR-0011 says 「A mapping change creates a
 * new draft and reruns every affected preflight」 and ADR-0003 says 「Excluding one field
 * does not waive the row check: DBX reruns preflight against the approved selected
 * columns」. Both are only true if the conclusion is a *function* of those inputs rather
 * than a stored label, so it is derived here every time and never cached against the table.
 *
 * Two of the rules are the product's judgement rather than arithmetic:
 *
 *  - a `NOT NULL` rule on a zero-date column is a blocking finding, because the source
 *    holds values the target would reject — that is exactly what the 「保持 NOT NULL；零
 *    日期值在预检阶段被判为阻断」 wording already promises;
 *  - an inconclusive scan stays inconclusive no matter what is pruned. ADR-0003:
 *    「cannot be overridden into a runnable table」.
 */
function preflightOf({
  seed,
  table,
  columns,
  pruned,
  rules,
  evaluatedAt,
  inFlight,
}: PreflightInputs): Preflight {
  if (inFlight) {
    // No conclusion, no findings, no measurement: the scan is running, and reporting the
    // previous answer beside a 「进行中」 label is how a stale conclusion stays on screen.
    return {
      conclusion: null,
      evaluatedAt: null,
      findings: [],
      largeRecordTable: false,
      largestValueBytes: null,
      largestRowBytes: null,
    };
  }

  const selected = columns.filter((column) => !pruned.has(column.name));
  const findings: PreflightFinding[] = [];

  const carrier = largeValueColumnOf(columns);
  const carrierSelected = carrier !== null && !pruned.has(carrier);
  const largestValueBytes =
    table.largestValueBytes === null
      ? null
      : carrierSelected
        ? table.largestValueBytes
        : ORDINARY_VALUE_BYTES;
  const largestRowBytes =
    largestValueBytes === null ? null : largestValueBytes + ORDINARY_VALUE_BYTES;

  if (largestValueBytes !== null && largestValueBytes > LARGE_RECORD_ENVELOPE_BYTES) {
    findings.push(finding('LARGE_RECORD_VALUE', carrier, true, String(largestValueBytes)));
  } else {
    if (largestValueBytes !== null && largestValueBytes > LARGE_RECORD_TABLE_BYTES) {
      findings.push(finding('LARGE_RECORD_VALUE', carrier, false, String(largestValueBytes)));
    }
    if (largestRowBytes !== null && largestRowBytes > LARGE_RECORD_ENVELOPE_BYTES) {
      findings.push(finding('LARGE_RECORD_ROW', null, true, String(largestRowBytes)));
    } else if (largestRowBytes !== null && largestRowBytes > LARGE_RECORD_TABLE_BYTES) {
      findings.push(finding('LARGE_RECORD_ROW', null, false, String(largestRowBytes)));
    }
  }

  const sourceOverEnvelope =
    table.largestValueBytes !== null && table.largestValueBytes > LARGE_RECORD_ENVELOPE_BYTES;
  if (table.preflightConclusion === 'UNSUPPORTED' && !sourceOverEnvelope) {
    for (const column of valueDomainColumnsOf(seed, table, columns)) {
      if (!pruned.has(column)) {
        findings.push(
          finding('VALUE_DOMAIN_OUT_OF_RANGE', column, true, `${column} @ ${table.sourceDatabase}`),
        );
      }
    }
  }

  const zeroDate = selected.find((column) => column.name === ZERO_DATE_COLUMN);
  if (zeroDate !== undefined) {
    const rule = rules.get(`${ZERO_DATE_COLUMN}:NULLABILITY`);
    if (rule?.targetValue === 'NOT NULL') {
      findings.push(
        finding('ZERO_DATE_VALUE_REJECTED', ZERO_DATE_COLUMN, true, "'0000-00-00 00:00:00'"),
      );
    }
  }

  const inconclusive = table.preflightConclusion === 'INCONCLUSIVE';
  if (inconclusive) {
    findings.push(
      finding('ENVELOPE_SCAN_INCONCLUSIVE', null, true, inconclusiveReasonOf(seed, table)),
    );
  }

  return {
    conclusion: inconclusive
      ? 'INCONCLUSIVE'
      : findings.some((entry) => entry.blocking)
        ? 'UNSUPPORTED'
        : 'SUPPORTED',
    evaluatedAt,
    findings,
    // Still a 大记录表 only if a boundary is still exceeded once the selected columns are
    // the ones actually measured.
    largeRecordTable:
      (largestValueBytes !== null && largestValueBytes > LARGE_RECORD_TABLE_BYTES) ||
      (largestRowBytes !== null && largestRowBytes > LARGE_RECORD_TABLE_BYTES),
    largestValueBytes,
    largestRowBytes,
  };
}

export interface TableWorkspaceOptions {
  readonly seed: number;
  readonly table: SourceTableSummary;
  readonly targetSchema: string;
  readonly userRules: readonly DraftMappingRule[];
  /** The columns the operator has cut out of this table's selected columns. */
  readonly prunedColumns: readonly string[];
  /** True while this table's 预检 is being rerun and has not concluded yet. */
  readonly preflightInFlight: boolean;
  /** The clock's reading, so 「重新生成于」 is a fact that moves rather than a constant. */
  readonly generatedAt: string;
}

/** Everything both the summary and the full workspace derive from, assembled once. */
function assemble({
  seed,
  table,
  userRules,
  prunedColumns,
  preflightInFlight,
  generatedAt,
}: Omit<TableWorkspaceOptions, 'targetSchema'>) {
  const columns = sourceColumnsOf(seed, table);
  const pruned = new Set(prunedColumns);
  const rules = new Map<string, MappingRule>();
  const exceptions: MappingException[] = [];

  for (const column of columns) {
    if (column.exception === null || pruned.has(column.name)) {
      continue;
    }
    const rule = ruleInForce(column, userRules);
    if (rule !== null) {
      rules.set(`${column.name}:${rule.action}`, rule);
    }
    exceptions.push({
      sourceColumn: column.name,
      sourceType: column.sourceType,
      action: column.exception.action,
      reason: column.exception.reason,
      options: column.exception.options,
      rule,
    });
  }

  const preflight = preflightOf({
    seed,
    table,
    columns,
    pruned,
    rules,
    evaluatedAt: generatedAt,
    inFlight: preflightInFlight,
  });

  return { columns, pruned, rules, exceptions, preflight };
}

/**
 * The per-table summary: cheap enough to answer for every table in a 1200-table 迁移范围.
 *
 * It derives from exactly the same assembly the full workspace does, rather than from a
 * cheaper approximation of it. An approximation is how a stage's gate and the workspace
 * beside it end up disagreeing about whether a table may proceed, and a gate that
 * disagrees with the screen is worse than no gate.
 */
export function draftTableConfigurationOf({
  seed,
  table,
  userRules,
  prunedColumns,
  preflightInFlight,
  generatedAt,
}: Omit<TableWorkspaceOptions, 'targetSchema'>): DraftTableConfiguration {
  const { exceptions, preflight } = assemble({
    seed,
    table,
    userRules,
    prunedColumns,
    preflightInFlight,
    generatedAt,
  });
  const undecided = exceptions.filter((exception) => exception.rule === null).length;
  return {
    sourceTable: table.name,
    // Identifiers are preserved character-for-character (ADR-0011): the target table is
    // the source table's name, never a normalised or prefixed variant.
    targetTable: table.name,
    preflightConclusion: preflight.conclusion,
    blockingFindingCount: preflight.findings.filter((entry) => entry.blocking).length,
    largeRecordTable: preflight.largeRecordTable,
    prunedColumnCount: prunedColumns.length,
    mappingExceptionCount: exceptions.length,
    undecidedMappingExceptionCount: undecided,
    // ADR-0011: the contract records an approval revision. Version 1 is the automatic
    // assembly; every recorded user rule and every pruned column produces a new one.
    contractVersion: undecided > 0 ? null : 1 + userRules.length + prunedColumns.length,
  };
}

export function draftTableWorkspaceOf({
  seed,
  table,
  targetSchema,
  userRules,
  prunedColumns,
  preflightInFlight,
  generatedAt,
}: TableWorkspaceOptions): DraftTableWorkspace {
  const { columns, pruned, rules, exceptions, preflight } = assemble({
    seed,
    table,
    userRules,
    prunedColumns,
    preflightInFlight,
    generatedAt,
  });
  const selected = columns.filter((column) => !pruned.has(column.name));
  const objects = outOfContractObjectsOf(table, selected);

  const undecided = exceptions.some((exception) => exception.rule === null);
  const contract: TableWriteContract | null = undecided
    ? null
    : {
        version: 1 + userRules.length + prunedColumns.length,
        generatedAt,
        // Nothing is approved inside a 迁移草稿: approval is 执行确认 (#37).
        approvedAt: null,
        columns: contractColumnsOf(selected, rules),
        targetDdl: targetDdlOf(targetSchema, table, selected, rules),
        supplementalSql: supplementalSqlOf(targetSchema, table, objects),
      };

  return {
    sourceTable: table.name,
    targetTable: table.name,
    sourceDatabase: table.sourceDatabase,
    targetSchema,
    // The source table as MySQL reports it: pruning is a DBX decision about what to write,
    // and never a claim about what the source contains.
    sourceDdl: sourceDdlOf(table, columns, outOfContractObjectsOf(table, columns)),
    objectTree: objectTreeOf(columns, objects, pruned),
    mappingExceptions: exceptions,
    preflight,
    prunedColumns: columns.filter((column) => pruned.has(column.name)).map((column) => column.name),
    tableWriteContract: contract,
  };
}
