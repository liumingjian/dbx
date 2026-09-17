package com.dbx.dialect.api;

/** A class of timeout; the gateway owns the durations behind each (ADR-0008 §Plans). */
public enum TimeoutClass {
    CATALOG_READ,
    CAPABILITY_PROBE,
    EXACT_SCAN,
    DDL,
    MAINTENANCE
}
