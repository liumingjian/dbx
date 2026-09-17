package com.dbx.dialect.api;

/**
 * Why {@code pair.map} refused a column (TP §6.2–6.4). The constant name is the stable code; renaming
 * one breaks every consumer keyed on it. Grouped by the TP §6 family that introduced each constant.
 */
public enum MappingUnsupportedReason implements UnsupportedReason {
    // any family
    /** The {@code data_type} is outside the v1 whitelist (TP §6.3). */
    NOT_WHITELISTED,
    /**
     * The facts contradict each other, or lack one {@code information_schema} always reports, e.g. an
     * unsigned flag that disagrees with {@code column_type}. Refused rather than guessed.
     */
    SOURCE_FACTS_INCONSISTENT,

    // numeric (TP §6.2)
    /** {@code BIT(n >= 8)}: values can truncate or overflow before the Sink sees them. */
    BIT_WIDTH_AT_LEAST_8,

    // character, binary and special (TP §6.3)
    /** {@code GEOMETRY} and its subtypes are outside the v1 whitelist; pruning the column is the only way on. */
    GEOMETRY,
    /** MySQL 9.0+ {@code VECTOR} is outside the v1 whitelist; pruning the column is the only way on. */
    VECTOR,
    /**
     * {@code CHAR(0)}/{@code VARCHAR(0)} of a text character set: MySQL allows length 0, PostgreSQL's
     * {@code char(n)}/{@code varchar(n)} require {@code n >= 1}, and TP §6.3 keeps {@code M} exactly.
     */
    CHARACTER_LENGTH_ZERO
}
