package com.dbx.connection.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * A {@link DataEncryptionKey} encrypted under the master key — the form {@code workflow} stores beside
 * the backup artifact in {@code backups/}, never in the append-only ledger (obligation 16; ADR-0006 as
 * amended by #97).
 *
 * <p>It is the argument of {@link ConnectionCrypto#unwrap} and {@link ConnectionCrypto#erase}, and it
 * holds no key bytes a reader can use: without the master key it is inert, which is what makes the
 * erasure story of ADR-0006 true.
 */
public final class WrappedKey {

    private final byte[] bytes;

    public WrappedKey(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WrappedKey wrapped && Arrays.equals(bytes, wrapped.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "WrappedKey[" + bytes.length + " bytes]";
    }
}
