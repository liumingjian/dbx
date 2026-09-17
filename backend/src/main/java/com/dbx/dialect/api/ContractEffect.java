package com.dbx.dialect.api;

/** What a mapping adds to the table write contract (ADR-0011; TP §7.3). Owned by slice 3. */
public enum ContractEffect {
    ENUM_CHECK_CONSTRAINT,
    OWNED_SEQUENCE_FOR_IDENTITY,
    ORIGINAL_SOURCE_TYPE_RETAINED
}
