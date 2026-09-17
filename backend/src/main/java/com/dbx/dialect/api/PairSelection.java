package com.dbx.dialect.api;

/** {@code catalog.select}'s closed result: {@link DatabasePair} or {@link Unsupported}. */
public sealed interface PairSelection permits DatabasePair, Unsupported {
}
