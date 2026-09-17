package com.dbx.dialect.api;

/** A stable notice a supported mapping states instead of staying silent (TP §6.3–6.4). Owned by slice 3. */
public enum MappingNotice {
    /** Connect logical time is milliseconds: digits after the third fractional one are lost (TP §6.4). */
    MICROSECONDS_TRUNCATED_TO_MILLISECONDS,
    JSON_BYTE_FIDELITY_NOT_CLAIMED,
    SET_WITHOUT_CHECK_CONSTRAINT,
    /** The operator's zero-date switch is on: a zero date is lost and arrives as {@code NULL} (TP §6.1, §6.5). */
    ZERO_DATE_CONVERTED_TO_NULL
}
