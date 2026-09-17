package com.dbx.dialect.api;

/** What the gateway persists as evidence when it runs a plan (ADR-0008 §Plans; ADR-0028: no values in packages). */
public enum EvidencePolicy {
    STATEMENT_AND_RESULT,
    STATEMENT_AND_AGGREGATES,
    STATEMENT_ONLY
}
