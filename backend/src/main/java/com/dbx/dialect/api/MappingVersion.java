package com.dbx.dialect.api;

/** The version of a pair's mapping rules (ADR-0008 §Registration). */
public record MappingVersion(int value) {

    public MappingVersion {
        Checks.positive(value, "mapping version");
    }
}
