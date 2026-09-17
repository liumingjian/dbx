package com.dbx.dialect.api;

import java.util.OptionalLong;

/**
 * A table's {@code information_schema.TABLES} statistics as MySQL reported them, read fresh (obligation
 * 19b). A statistic MySQL reports as {@code NULL} is absent, never {@code 0}: whether the statistics are
 * usable is {@code preflight}'s judgement (ADR-0002 ¶4), not this type's.
 */
public record TableStatistics(OptionalLong tableRows, OptionalLong averageRowLength, OptionalLong dataLength) {

    public TableStatistics {
        nonNegative(tableRows, "tableRows");
        nonNegative(averageRowLength, "averageRowLength");
        nonNegative(dataLength, "dataLength");
    }

    private static void nonNegative(OptionalLong value, String what) {
        Checks.present(value, what);
        if (value.isPresent() && value.getAsLong() < 0) {
            throw new IllegalArgumentException(what + " is a count of rows or bytes and cannot be negative, was "
                    + value.getAsLong());
        }
    }
}
