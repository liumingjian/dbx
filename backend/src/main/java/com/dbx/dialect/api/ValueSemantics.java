package com.dbx.dialect.api;

/** How source and target values are compared once transferred (TP §6.2–6.4, §9). Owned by slice 3. */
public enum ValueSemantics {
    EXACT,
    TRAILING_SPACE_PADDED,
    WALL_CLOCK_MILLISECONDS,
    UTC_INSTANT_MILLISECONDS,
    TIME_OF_DAY_MILLISECONDS,
    YEAR_AS_FIRST_OF_JANUARY,
    JSON_TEXT
}
