package com.dbx.connection.api;

/**
 * {@link ConnectionCrypto#unwrap}'s closed result: the {@link DataEncryptionKey}, or exactly the two
 * distinct failures of obligation 18 — {@link MasterKeyWrongOrMissing} or
 * {@link WrappedFormCorruptOrErased}.
 *
 * <p>Exactly two, and never merged: they send the DBA to different places — restore {@code secrets/}
 * from the copy kept off the machine, versus abandon this backup and choose another. An erased key
 * surfacing as a key problem would have the DBA hunt a key that is working fine, which is why
 * {@link WrappedFormCorruptOrErased} covers erasure and corruption together and the key case stands
 * alone.
 *
 * <p>These are separate types from {@link WrongOrLostKey} and {@link CorruptCiphertext} on purpose: a
 * caller unwrapping a backup key is in a different conversation with the DBA than one decrypting a
 * credential version, and the sealed unions keep the two from being handled by one branch.
 */
public sealed interface Unwrapping
        permits DataEncryptionKey, MasterKeyWrongOrMissing, WrappedFormCorruptOrErased {
}
