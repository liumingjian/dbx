package com.dbx.dialect.api;

/**
 * How source and target values are compared once transferred (TP §6.2–6.4, §9). Grouped by the TP §6
 * family that introduced each constant; add yours to your group.
 */
public enum ValueSemantics {
    // numeric (TP §6.2)
    /** Numeric equality after normalising both sides to arbitrary-precision decimals (TP §9). */
    EXACT,
    /** The actual Java IEEE values, without an invented tolerance (TP §9). */
    IEEE_FLOATING_POINT,
    /** A source {@code 0}/{@code 1} compared as {@code false}/{@code true} (TP §6.2, Boolean switch on). */
    ZERO_ONE_AS_BOOLEAN,

    // character, binary and special (TP §6.3)
    TRAILING_SPACE_PADDED,
    JSON_TEXT,

    // temporal (TP §6.4)
    /** A calendar date, compared by its year, month and day fields. */
    CALENDAR_DATE,
    WALL_CLOCK_MILLISECONDS,
    UTC_INSTANT_MILLISECONDS,
    TIME_OF_DAY_MILLISECONDS,
    YEAR_AS_FIRST_OF_JANUARY
}
