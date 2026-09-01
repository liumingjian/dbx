import type { MigrationDraft } from '@/contract';

/**
 * Draft durability (lead decision D3 for #32, relied on by #34).
 *
 * A migration draft must survive a browser refresh — half a day spent selecting tables out
 * of 1200 cannot be lost to a closed tab. A purely in-memory mock store cannot promise
 * that, so the store takes a persistence adapter and *only the draft goes through it*.
 *
 * Migration tasks, runs, table migration units, preflight results and validation
 * executions stay in memory and are re-seeded on boot. They are server-owned in the real
 * system, and persisting them would fake a durability the backend has not promised. A
 * draft is genuinely client-side work in progress until it is approved, which is exactly
 * what `CONTEXT.md` says it is.
 */
export interface DraftPersistence {
  read(): readonly MigrationDraft[] | null;
  write(drafts: readonly MigrationDraft[]): void;
  clear(): void;
}

export const DRAFT_STORAGE_KEY = 'dbx.migration-drafts';

/**
 * Bumped whenever the persisted shape changes — version 2 added the draft's scope kind
 * (#34), version 3 the user 映射规则 of its per-table configuration (#35), version 4
 * the columns cut out of a table by ADR-0003's second exit (#36), and version 5 the
 * 写冻结 the operator declares at 执行确认 (#37). `CONTEXT.md` counts
 * per-table configuration as part of a 迁移草稿, so all of it has to survive a refresh. A
 * payload written by an older version is discarded rather than migrated: a draft is
 * discardable by definition, so guessing at a stale shape would buy nothing and could
 * resurrect a half-valid selection.
 */
export const DRAFT_SCHEMA_VERSION = 5;

interface PersistedPayload {
  readonly schemaVersion: number;
  readonly drafts: readonly MigrationDraft[];
}

/** Used by tests and by every URL-selected scenario, so runs stay independent. */
export function createMemoryDraftPersistence(): DraftPersistence {
  let held: readonly MigrationDraft[] | null = null;
  return {
    read: () => held,
    write: (drafts) => {
      held = drafts;
    },
    clear: () => {
      held = null;
    },
  };
}

/**
 * The browser-backed adapter. Anything unreadable — absent, malformed, or written by an
 * older schema version — is discarded and the key is cleared, so a bad payload cannot
 * wedge the application on every subsequent load.
 */
export function createBrowserDraftPersistence(storage: Storage): DraftPersistence {
  const clear = (): void => {
    try {
      storage.removeItem(DRAFT_STORAGE_KEY);
    } catch {
      // A storage that refuses to be written to (private mode, quota) is not an error the
      // operator can act on; the draft simply is not durable in that browser.
    }
  };

  return {
    read() {
      let raw: string | null = null;
      try {
        raw = storage.getItem(DRAFT_STORAGE_KEY);
      } catch {
        return null;
      }
      if (raw === null) {
        return null;
      }
      try {
        const parsed = JSON.parse(raw) as PersistedPayload;
        if (parsed?.schemaVersion !== DRAFT_SCHEMA_VERSION || !Array.isArray(parsed.drafts)) {
          clear();
          return null;
        }
        return parsed.drafts;
      } catch {
        clear();
        return null;
      }
    },
    write(drafts) {
      const payload: PersistedPayload = { schemaVersion: DRAFT_SCHEMA_VERSION, drafts };
      try {
        storage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(payload));
      } catch {
        // As above: not durable here, but not a failure the operator can do anything with.
      }
    },
    clear,
  };
}

/** Picks the adapter a scenario asks for, falling back to memory where there is no storage. */
export function createDraftPersistence(kind: 'browser' | 'memory'): DraftPersistence {
  if (kind === 'memory' || typeof globalThis.localStorage === 'undefined') {
    return createMemoryDraftPersistence();
  }
  return createBrowserDraftPersistence(globalThis.localStorage);
}
