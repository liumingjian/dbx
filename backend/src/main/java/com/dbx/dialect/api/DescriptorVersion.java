package com.dbx.dialect.api;

/** The version a frozen dialect descriptor was written with (ADR-0008 §Registration). */
public record DescriptorVersion(int value) implements UnsupportedEvidence {

    public DescriptorVersion {
        Checks.positive(value, "descriptor version");
    }
}
