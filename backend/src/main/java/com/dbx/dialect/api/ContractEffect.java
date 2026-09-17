package com.dbx.dialect.api;

/** What a mapping adds to the table write contract (ADR-0011; TP §7.3). Owned by slice 3. */
public enum ContractEffect {
    ENUM_CHECK_CONSTRAINT,
    OWNED_SEQUENCE_FOR_IDENTITY,
    /**
     * The original MySQL type is {@code DATETIME}. Connect carries {@code DATETIME} and {@code TIMESTAMP}
     * as the same Timestamp, so after conversion only this record tells them apart (TP §6.4).
     */
    ORIGINAL_SOURCE_TYPE_DATETIME,
    /** The original MySQL type is {@code TIMESTAMP}; see {@link #ORIGINAL_SOURCE_TYPE_DATETIME} (TP §6.4). */
    ORIGINAL_SOURCE_TYPE_TIMESTAMP
}
