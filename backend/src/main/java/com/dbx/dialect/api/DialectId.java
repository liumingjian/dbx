package com.dbx.dialect.api;

/** The stable identifier of a source or target dialect (ADR-0008 §Registration). */
public record DialectId(String value) {

    public DialectId {
        Checks.nonEmpty(value, "dialect id");
    }
}
