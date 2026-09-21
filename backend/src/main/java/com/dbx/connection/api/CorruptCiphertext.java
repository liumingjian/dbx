package com.dbx.connection.api;

/**
 * Obligation 14's second decryption failure: the master key is the right one, but the ciphertext was
 * altered or truncated, so GCM authentication refuses it (obligation 13).
 *
 * <p>Distinct from {@link WrongOrLostKey} because the stored value, not the key, is what went wrong:
 * restoring {@code secrets/} would change nothing. It carries no material, for the reason obligation 11
 * gives.
 */
public record CorruptCiphertext() implements Decryption {
}
