package com.dbx.connection.api;

/**
 * Obligation 18's first unwrap failure: the wrapped form is intact, but the mounted master key is not
 * the one it was wrapped under, or no key is mounted at all.
 *
 * <p>The DBA's action is to restore {@code secrets/} from the copy kept off the machine (ADR-0035
 * §Master key). It is an outcome rather than a thrown {@link MasterKeyFailure} because
 * {@link ConnectionCrypto#unwrap} is the one entry point where "the key does not open this" and "there
 * is no usable key" lead to the same instruction and must not be told apart by probing: restoring a
 * backup is a recovery path, and it reports what it found instead of throwing part way through.
 *
 * <p>It carries nothing, for the reason obligation 11 gives.
 */
public record MasterKeyWrongOrMissing() implements Unwrapping {
}
