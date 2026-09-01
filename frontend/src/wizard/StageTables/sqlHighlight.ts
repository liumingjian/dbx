/**
 * DBX's own SQL palette (ADR-0014:41).
 *
 * **Why no highlighting dependency.** Carbon ships `CodeSnippet` with a copy affordance
 * but no highlighter, and ADR-0014 already makes "any syntax highlighting palette" a
 * DBX-owned concern that has to be re-tested whenever Carbon moves. The alternative —
 * Prism or Shiki — buys a general-purpose grammar for a language DBX itself generates, and
 * every one of them highlights by producing HTML that has to be injected with
 * `dangerouslySetInnerHTML`. On the one screen whose entire point is that its SQL is
 * **read-only and platform-owned** (ADR-0011), handing the rendering to a third party and
 * an HTML injection is exactly the wrong trade. So: a small tokeniser here, spans in the
 * component, no new dependency, and no innerHTML anywhere near the DDL.
 *
 * The tokeniser is **lossless** — concatenating the tokens reproduces the input character
 * for character. That is a correctness property, not tidiness: identifiers are preserved
 * character-for-character (ADR-0011), so a highlighter that dropped or normalised a
 * character would be showing DDL that is not the DDL.
 */

export type SqlTokenKind =
  'keyword' | 'type' | 'identifier' | 'string' | 'number' | 'punctuation' | 'plain';

export interface SqlToken {
  readonly kind: SqlTokenKind;
  readonly text: string;
}

/** Only what DBX generates. This is not a general MySQL or PostgreSQL grammar. */
const keywords = new Set([
  'create',
  'table',
  'alter',
  'add',
  'constraint',
  'primary',
  'key',
  'unique',
  'index',
  'foreign',
  'references',
  'not',
  'null',
  'default',
  'auto_increment',
  'generated',
  'by',
  'as',
  'identity',
  'on',
  'engine',
  'charset',
  'collate',
  'comment',
]);

const types = new Set([
  'bigint',
  'int',
  'integer',
  'smallint',
  'tinyint',
  'varchar',
  'char',
  'text',
  'blob',
  'bytea',
  'json',
  'jsonb',
  'date',
  'datetime',
  'timestamp',
  'decimal',
  'numeric',
  'boolean',
  'double',
  'precision',
  'enum',
  'unsigned',
  'sequence',
]);

const pattern =
  /(`[^`]*`|"[^"]*")|('(?:[^']|'')*')|(--[^\n]*)|([A-Za-z_][A-Za-z0-9_]*)|(\d+(?:\.\d+)?)|([(),;.])/g;

/** Splits DDL into tokens. Unrecognised runs come back as `plain`, never dropped. */
export function tokeniseSql(sql: string): readonly SqlToken[] {
  const tokens: SqlToken[] = [];
  let cursor = 0;

  const push = (kind: SqlTokenKind, text: string): void => {
    if (text === '') {
      return;
    }
    const previous = tokens[tokens.length - 1];
    // Merging adjacent plain runs keeps the rendered span count close to the number of
    // things that are actually coloured.
    if (previous !== undefined && previous.kind === 'plain' && kind === 'plain') {
      tokens[tokens.length - 1] = { kind, text: previous.text + text };
      return;
    }
    tokens.push({ kind, text });
  };

  pattern.lastIndex = 0;
  let match = pattern.exec(sql);
  while (match !== null) {
    push('plain', sql.slice(cursor, match.index));
    const [text, quotedIdentifier, string, comment, word, number, punctuation] = match;
    if (quotedIdentifier !== undefined) {
      push('identifier', text);
    } else if (string !== undefined || comment !== undefined) {
      push(string !== undefined ? 'string' : 'plain', text);
    } else if (word !== undefined) {
      const lower = word.toLowerCase();
      push(keywords.has(lower) ? 'keyword' : types.has(lower) ? 'type' : 'plain', text);
    } else if (number !== undefined) {
      push('number', text);
    } else if (punctuation !== undefined) {
      push('punctuation', text);
    } else {
      push('plain', text);
    }
    cursor = match.index + text.length;
    match = pattern.exec(sql);
  }
  push('plain', sql.slice(cursor));

  return tokens;
}
