import { describe, expect, it } from 'vitest';
import type { MigrationDraft } from '@/contract';
import {
  DRAFT_SCHEMA_VERSION,
  DRAFT_STORAGE_KEY,
  createBrowserDraftPersistence,
  createMemoryDraftPersistence,
} from './persistence';

/**
 * The draft persistence adapter (lead decision D3).
 *
 * This is the one place in the mocks that promises durability, and the whole promise rests
 * on one rule: **anything unreadable is discarded, and the key is cleared.** A payload the
 * current schema cannot read has to leave, or every subsequent load wedges on it — and a
 * 迁移草稿 is discardable by definition, so guessing at a stale shape would buy nothing and
 * could resurrect a half-valid 迁移范围.
 *
 * Driven against a fake `Storage` rather than a browser: what is asserted is the adapter's
 * behaviour, including the two ways a real browser refuses to co-operate (private mode and
 * quota), which no jsdom would produce on its own.
 */

interface FakeStorage extends Storage {
  readonly entries: Map<string, string>;
}

function fakeStorage(overrides: Partial<Storage> = {}): FakeStorage {
  const entries = new Map<string, string>();
  return {
    entries,
    get length() {
      return entries.size;
    },
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => void entries.set(key, String(value)),
    removeItem: (key: string) => void entries.delete(key),
    clear: () => entries.clear(),
    key: (index: number) => [...entries.keys()][index] ?? null,
    ...overrides,
  } as FakeStorage;
}

const draft = { id: 'draft-1', name: '' } as unknown as MigrationDraft;

describe('the browser-backed draft persistence', () => {
  it('reads back what it wrote', () => {
    const storage = fakeStorage();
    const persistence = createBrowserDraftPersistence(storage);
    persistence.write([draft]);
    expect(persistence.read()).toEqual([draft]);
  });

  it('says nothing is stored rather than inventing an empty list', () => {
    expect(createBrowserDraftPersistence(fakeStorage()).read()).toBeNull();
  });

  it('discards a payload written by an older schema version, and clears the key', () => {
    const storage = fakeStorage();
    storage.setItem(
      DRAFT_STORAGE_KEY,
      JSON.stringify({ schemaVersion: DRAFT_SCHEMA_VERSION - 1, drafts: [draft] }),
    );

    expect(createBrowserDraftPersistence(storage).read()).toBeNull();
    // Cleared, not merely ignored: a payload that stays behind is read again on every load.
    expect(storage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
  });

  it('discards malformed JSON, and clears the key', () => {
    const storage = fakeStorage();
    storage.setItem(DRAFT_STORAGE_KEY, '{ this is not json');

    expect(createBrowserDraftPersistence(storage).read()).toBeNull();
    expect(storage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
  });

  it('discards a payload whose drafts are not a list, and clears the key', () => {
    const storage = fakeStorage();
    storage.setItem(
      DRAFT_STORAGE_KEY,
      JSON.stringify({ schemaVersion: DRAFT_SCHEMA_VERSION, drafts: { id: 'draft-1' } }),
    );

    expect(createBrowserDraftPersistence(storage).read()).toBeNull();
    expect(storage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
  });

  it('tolerates a storage that refuses to be read', () => {
    // Private mode: `getItem` throws. The draft is simply not durable in that browser, and
    // that is not an error the operator can act on.
    const storage = fakeStorage({
      getItem: () => {
        throw new Error('SecurityError');
      },
    });
    expect(() => createBrowserDraftPersistence(storage).read()).not.toThrow();
    expect(createBrowserDraftPersistence(storage).read()).toBeNull();
  });

  it('tolerates a storage that refuses to be written to', () => {
    // Quota exceeded. Losing durability must not lose the draft the operator is holding.
    const storage = fakeStorage({
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
      removeItem: () => {
        throw new Error('QuotaExceededError');
      },
    });
    const persistence = createBrowserDraftPersistence(storage);
    expect(() => persistence.write([draft])).not.toThrow();
    expect(() => persistence.clear()).not.toThrow();
  });
});

describe('the in-memory draft persistence', () => {
  it('holds and clears without touching any browser storage', () => {
    // Every URL-selected scenario uses this one, so one review or test run cannot poison
    // the next (D3).
    const persistence = createMemoryDraftPersistence();
    expect(persistence.read()).toBeNull();
    persistence.write([draft]);
    expect(persistence.read()).toEqual([draft]);
    persistence.clear();
    expect(persistence.read()).toBeNull();
  });
});
