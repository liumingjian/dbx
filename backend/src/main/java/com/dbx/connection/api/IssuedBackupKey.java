package com.dbx.connection.api;

import java.util.Objects;

/**
 * {@link ConnectionCrypto#wrap}'s result: the fresh {@link DataEncryptionKey} the caller encrypts the
 * backup with, and the {@link WrappedKey} it stores beside the artifact (obligation 16).
 *
 * <p>Both halves come back from one call because they must stay together: a key without its wrapped
 * form cannot be recovered after the run ends, and a wrapped form without the key encrypts nothing.
 * The caller is expected to use the key and forget it; only the wrapped form is stored, and never in
 * the append-only ledger (ADR-0006 as amended by #97).
 *
 * <p>The generated {@code toString} is safe under obligation 11 because every component redacts itself —
 * which is the point of redacting structurally rather than filtering output (ADR-0028).
 */
public record IssuedBackupKey(BackupId backup, DataEncryptionKey key, WrappedKey wrappedForm) {

    public IssuedBackupKey {
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(wrappedForm, "wrappedForm");
    }
}
