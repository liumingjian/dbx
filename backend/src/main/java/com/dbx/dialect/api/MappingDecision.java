package com.dbx.dialect.api;

/** {@code pair.map}'s closed result: {@link Supported} or {@link Unsupported}, never null (TP §6.1). */
public sealed interface MappingDecision permits Supported, Unsupported {

    /**
     * Deterministic over the whole decision: equal decisions fingerprint equal, on any machine, in any
     * run (TP §15.1). A contract freezing a decision can compare this instead of re-deciding.
     */
    default MappingFingerprint mappingFingerprint() {
        return MappingDecisionEncoding.fingerprint(this);
    }
}
