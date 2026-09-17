package com.dbx.dialect.api;

/**
 * The one refusal every closed {@code dialect} result shares: a stable reason code and the
 * evidence it needs (ADR-0008 §Contract; CONTEXT.md "Unsupported"). There is no "probably
 * supported" and no fallback beside it.
 */
public record Unsupported(UnsupportedReason reason, UnsupportedEvidence evidence)
        implements PairSelection, MappingDecision, IdentifierMapping, CodecSelection {

    public Unsupported {
        Checks.present(reason, "reason");
        Checks.present(evidence, "evidence");
    }
}
