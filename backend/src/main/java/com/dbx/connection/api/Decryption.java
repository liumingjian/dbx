package com.dbx.connection.api;

/**
 * {@link ConnectionCrypto#decrypt}'s closed result: the {@link SecretMaterial}, or one of the two
 * distinct failures of obligations 13 and 14 — {@link WrongOrLostKey} or {@link CorruptCiphertext}.
 *
 * <p>The union is closed and its members are separate types on purpose. The caller leads the DBA
 * somewhere different in each case (restore {@code secrets/} versus re-enter the password, which
 * creates a new credential version), and a union of types makes the compiler ask for both branches
 * where a reason string would not (obligation 14).
 */
public sealed interface Decryption permits SecretMaterial, WrongOrLostKey, CorruptCiphertext {
}
