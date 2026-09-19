package com.dbx.connection.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * The per-backup data-encryption key {@link ConnectionCrypto#wrap} issues and
 * {@link ConnectionCrypto#unwrap} returns (obligations 16, 17).
 *
 * <p>Key bytes are material, so redaction is structural for the same reason as
 * {@link SecretMaterial}: {@link #toString} prints nothing of the key (obligation 11). Copies in and
 * out keep the issued key independent of whatever the caller does with its array.
 */
public final class DataEncryptionKey implements Unwrapping {

    private final byte[] bytes;

    public DataEncryptionKey(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataEncryptionKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Names the type and nothing else (obligation 11). */
    @Override
    public String toString() {
        return "DataEncryptionKey[redacted]";
    }
}
