package com.dbx.connection.api;

import java.util.Objects;

/**
 * What {@link ConnectionCrypto#erase} returns: the instruction that makes one backup's
 * {@link DataEncryptionKey} unrecoverable, for {@code workflow} to carry out (obligation 19; #97).
 *
 * <p>{@code connection} erases nothing itself — it persists nothing and never touches {@code backups/}
 * or the ledger — so the instruction names the wrapped form to destroy and the module that owns those
 * files acts on it. Once it is carried out, no path through {@code connection.api} returns the key
 * again, even with the master key and its off-machine copy (obligation 19a), because the wrapped form
 * was the only copy of it.
 *
 * <p>Producing one for an already-erased key succeeds: cleanup is retried, so the instruction states a
 * wanted end state rather than recording an event (obligation 19b; ADR-0006 §Recovery).
 */
public record ErasureInstruction(WrappedKey wrappedForm) {

    public ErasureInstruction {
        Objects.requireNonNull(wrappedForm, "wrappedForm");
    }
}
