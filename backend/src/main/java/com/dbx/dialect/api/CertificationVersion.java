package com.dbx.dialect.api;

/** The version of the certification a pair passed (ADR-0008 §Certification). */
public record CertificationVersion(int value) {

    public CertificationVersion {
        Checks.positive(value, "certification version");
    }
}
