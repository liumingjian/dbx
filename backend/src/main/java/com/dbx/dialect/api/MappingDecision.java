package com.dbx.dialect.api;

/** {@code pair.map}'s closed result: {@link Supported} or {@link Unsupported}, never null (TP §6.1). */
public sealed interface MappingDecision permits Supported, Unsupported {
}
