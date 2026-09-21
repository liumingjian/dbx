package com.dbx.connection.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Secret authentication material on its way into {@link ConnectionCrypto#encrypt} or back out of
 * {@link ConnectionCrypto#decrypt}: a password, a TLS client private key, or that key's passphrase
 * (obligations 12, 20; ADR-0005 "private keys").
 *
 * <p>Redaction is structural, not a log filter (ADR-0028): {@link #toString} prints no byte and not
 * even the length, so no diagnostic package, exception text or accidental string concatenation can
 * carry the material out (obligation 11). It is a class rather than a record for that reason — a
 * record would generate a {@code toString} that prints the bytes.
 *
 * <p>It copies on the way in and on the way out, so the caller's array cannot change material already
 * handed over, and clearing the returned copy cannot blind a second reader.
 */
public final class SecretMaterial implements Decryption {

    private final byte[] bytes;

    public SecretMaterial(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretMaterial material && Arrays.equals(bytes, material.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Names the type and nothing else: obligation 11 holds even when a caller logs the value. */
    @Override
    public String toString() {
        return "SecretMaterial[redacted]";
    }
}
