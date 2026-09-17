package com.dbx.dialect.api;

/**
 * A stable reason code. Each capability owns its own enum, in its own file, so the slices adding
 * codes do not edit one shared list; the code is the enum constant's name, which diagnosis keys on
 * without parsing prose (ADR-0005).
 */
public sealed interface UnsupportedReason
        permits CatalogUnsupportedReason, MappingUnsupportedReason, IdentifierUnsupportedReason,
                CodecUnsupportedReason {

    /** The stable code. Renaming a constant is a breaking change for every consumer keyed on it. */
    String name();
}
