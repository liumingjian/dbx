package com.dbx.connection.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * One self-contained AES-256-GCM encryption of {@link SecretMaterial}, the value {@code workflow}
 * stores as an immutable credential version (凭据版本) (obligations 12, 15).
 *
 * <p>Self-contained means everything needed to decrypt it under the right master key travels with it,
 * so nothing about a credential version depends on state {@code connection} would have to keep
 * (obligation 15). What that envelope contains is slice 2's decision; only the fact that it is one
 * value is fixed here.
 *
 * <p>It carries no plaintext, so {@link #toString} may say how many bytes it holds — but it says
 * nothing else, because a ciphertext printed in full invites a reader to treat it as an identifier.
 */
public final class Ciphertext {

    private final byte[] bytes;

    public Ciphertext(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Ciphertext ciphertext && Arrays.equals(bytes, ciphertext.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "Ciphertext[" + bytes.length + " bytes]";
    }
}
