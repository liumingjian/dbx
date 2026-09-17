package com.dbx.dialect.api;

/**
 * The closed outcomes of a required proof (ADR-0008 §Ownership). Only {@link #PROVEN} lets the
 * migration core continue; there is deliberately no "probably fine". {@code dialect} reports an
 * outcome and never acts on it.
 */
public enum ProofOutcome {
    PROVEN,
    INCONCLUSIVE,
    REJECTED
}
