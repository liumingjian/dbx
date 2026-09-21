package com.dbx.connection.api;

import java.util.Objects;

/**
 * Which backup a wrapped key belongs to — {@link ConnectionCrypto#wrap}'s one argument and what an
 * {@link ErasureInstruction} points at.
 *
 * <p>It is an identity, not a location: {@code connection} never opens {@code backups/} and never
 * writes a file (obligation 19), so a path here would suggest an access this module must not have.
 * {@code workflow} owns the backup record and knows where the artifact lives.
 */
public record BackupId(String value) {

    public BackupId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a backup id is the identity workflow stores, so it cannot be blank");
        }
    }
}
