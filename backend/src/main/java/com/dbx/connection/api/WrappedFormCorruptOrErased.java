package com.dbx.connection.api;

/**
 * Obligation 18's second unwrap failure: the master key is fine, but this wrapped form does not open —
 * because it was altered, truncated, wrapped elsewhere, or because its erasure instruction has been
 * carried out (obligation 19a).
 *
 * <p>Erasure and corruption share one type deliberately. Telling them apart would require the module
 * to remember which keys were erased, and {@code connection} keeps no state and persists nothing; it
 * would also hand a reader a way to learn that a backup once existed. Either way the DBA's action is
 * the same: abandon this backup and choose another.
 *
 * <p>It carries nothing, for the reason obligation 11 gives.
 */
public record WrappedFormCorruptOrErased() implements Unwrapping {
}
