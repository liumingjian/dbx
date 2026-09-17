package com.dbx.dialect.api;

/**
 * The closed set of things a plan may do, one per plan-producing entry point of
 * {@code docs/spec/dialect.md} §Interface. The gateway executes only kinds allowed in the current
 * state (ADR-0008 §Plans).
 */
public enum OperationKind {
    SOURCE_METADATA_READ,
    SOURCE_CAPABILITY_CHECK,
    SOURCE_PREFLIGHT_SCAN,
    SOURCE_BASELINE_READ,
    SOURCE_VALIDATION_FACTS,
    SOURCE_SAMPLING,
    TARGET_DDL,
    TARGET_CATALOG_READ,
    TARGET_CAPABILITY_PROBE,
    TARGET_MAINTENANCE,
    TARGET_VALIDATION_FACTS,
    TARGET_SAMPLING_LOOKUP
}
