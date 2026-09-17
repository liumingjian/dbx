package com.dbx.dialect.api;

/**
 * How a source dialect keeps reads bounded in bytes (ADR-0033; ADR-0037; TP §6.5). A source dialect
 * that cannot declare one cannot be registered (slice 2); the byte budgets are filled by slice 5.
 *
 * @param fetchByteBudget bytes one fetch may hold, from which the fetch size is derived per table
 * @param keysetChunkByteBudget bytes one {@code LIMIT} keyset chunk may hold
 * @param bulkReadCapBytes the most one bulk read may hold
 */
public record BoundedReadRequirement(long fetchByteBudget, long keysetChunkByteBudget, long bulkReadCapBytes) {

    public BoundedReadRequirement {
        Checks.positive(fetchByteBudget, "fetchByteBudget");
        Checks.positive(keysetChunkByteBudget, "keysetChunkByteBudget");
        Checks.positive(bulkReadCapBytes, "bulkReadCapBytes");
    }
}
