package com.dbx.dialect.api;

import java.util.OptionalLong;

/**
 * A table's {@code information_schema.TABLES} statistics as MySQL reported them, read fresh (obligation
 * 19b). A statistic MySQL reports as {@code NULL} is absent, never {@code 0}: whether the statistics are
 * usable is {@code preflight}'s judgement (ADR-0002 ¶4), not this type's.
 *
 * <p>{@code autoIncrement} is the table's next auto-increment value. TP §6.6 check 7 and TP §7.3 need it and
 * it is an {@code information_schema.TABLES} fact, never a scanned one, so {@code source.preflightScanPlan}
 * plans no column for it (spec #134 correction 5). Whether it exceeds 2^63-1 is {@code preflight}'s
 * comparison (preflight obligation 13), not this type's.
 */
public record TableStatistics(OptionalLong tableRows, OptionalLong averageRowLength, OptionalLong dataLength,
        OptionalLong autoIncrement) {

    public TableStatistics {
        nonNegative(tableRows, "tableRows");
        nonNegative(averageRowLength, "averageRowLength");
        nonNegative(dataLength, "dataLength");
        nonNegative(autoIncrement, "autoIncrement");
    }

    private static void nonNegative(OptionalLong value, String what) {
        Checks.present(value, what);
        if (value.isPresent() && value.getAsLong() < 0) {
            throw new IllegalArgumentException(what + " is a count of rows or bytes and cannot be negative, was "
                    + value.getAsLong());
        }
    }
}
