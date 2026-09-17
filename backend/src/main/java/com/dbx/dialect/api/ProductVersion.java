package com.dbx.dialect.api;

/**
 * Product and version exactly as connection probing reported them (ADR-0008 §Registration). Raw
 * facts, not a lookup key: the catalog decides what they match.
 */
public record ProductVersion(String product, String version) {

    public ProductVersion {
        Checks.nonEmpty(product, "product");
        Checks.nonEmpty(version, "version");
    }
}
