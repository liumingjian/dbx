package com.dbx.dialect.api;

/** A stable notice a supported mapping states instead of staying silent (TP §6.3–6.4). Owned by slice 3. */
public enum MappingNotice {
    MICROSECONDS_TRUNCATED_TO_MILLISECONDS,
    JSON_BYTE_FIDELITY_NOT_CLAIMED,
    SET_WITHOUT_CHECK_CONSTRAINT
}
