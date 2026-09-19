package com.dbx.connection.api;

/**
 * Obligation 14's first decryption failure: the ciphertext is intact, but the present master key is not
 * the one it was written under — so either the wrong key is mounted or the original is lost
 * (ADR-0035 §Master key, "If the key is lost").
 *
 * <p>It carries nothing. A failure that carried the ciphertext or a partial plaintext would be the
 * leak obligation 11 forbids, and there is nothing else to say: the caller's action is fixed by the
 * type, which is to have the DBA re-enter the password and create a new credential version (凭据版本).
 */
public record WrongOrLostKey() implements Decryption {
}
