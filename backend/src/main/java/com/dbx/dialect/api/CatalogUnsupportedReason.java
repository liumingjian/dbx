package com.dbx.dialect.api;

/** Why {@code catalog.select} refused (ADR-0008 §Registration). Owned by slice 2. */
public enum CatalogUnsupportedReason implements UnsupportedReason {
    MISSING,
    AMBIGUOUS,
    UNCERTIFIED,
    VERSION_INCOMPATIBLE
}
