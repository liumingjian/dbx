package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.SWITCHES_OFF;
import static com.dbx.dialect.api.MappingCase.ZERO_DATE_ON;
import static com.dbx.dialect.api.MappingCase.of;
import static com.dbx.dialect.api.SourceColumns.column;

import java.util.List;

/**
 * Golden rows for TP §6.4, as MySQL 8.0 reports them in {@code information_schema.COLUMNS}: no
 * {@code datetime_precision} for {@code DATE} and {@code YEAR}, {@code 0..6} for the rest. One row per
 * matrix row, precision boundary and zero-date switch position. Rendered to {@code temporal.txt}.
 */
final class TemporalMappingCases {

    static final List<MappingCase> CASES = List.of(
            of("DATE", column("date", "date"), SWITCHES_OFF),
            of("DATE, zero-date switch on", column("date", "date"), ZERO_DATE_ON),
            of("DATE NOT NULL, zero-date switch on", column("date", "date").notNull(), ZERO_DATE_ON),
            of("DATETIME", column("datetime", "datetime").datetimePrecision(0), SWITCHES_OFF),
            of("DATETIME(3)", column("datetime", "datetime(3)").datetimePrecision(3), SWITCHES_OFF),
            of("DATETIME(4)", column("datetime", "datetime(4)").datetimePrecision(4), SWITCHES_OFF),
            of("DATETIME(6)", column("datetime", "datetime(6)").datetimePrecision(6), SWITCHES_OFF),
            of("DATETIME(6), zero-date switch on", column("datetime", "datetime(6)").datetimePrecision(6),
                    ZERO_DATE_ON),
            of("DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)", column("datetime", "datetime(6)")
                    .datetimePrecision(6).notNull().columnDefault("CURRENT_TIMESTAMP(6)").extra("DEFAULT_GENERATED"),
                    SWITCHES_OFF),
            of("TIMESTAMP", column("timestamp", "timestamp").datetimePrecision(0), SWITCHES_OFF),
            of("TIMESTAMP(3)", column("timestamp", "timestamp(3)").datetimePrecision(3), SWITCHES_OFF),
            of("TIMESTAMP(6)", column("timestamp", "timestamp(6)").datetimePrecision(6), SWITCHES_OFF),
            of("TIMESTAMP NOT NULL, zero-date switch on", column("timestamp", "timestamp").datetimePrecision(0)
                    .notNull(), ZERO_DATE_ON),
            of("TIMESTAMP(6), zero-date switch on", column("timestamp", "timestamp(6)").datetimePrecision(6),
                    ZERO_DATE_ON),
            of("TIME", column("time", "time").datetimePrecision(0), SWITCHES_OFF),
            of("TIME(3)", column("time", "time(3)").datetimePrecision(3), SWITCHES_OFF),
            of("TIME(6)", column("time", "time(6)").datetimePrecision(6), SWITCHES_OFF),
            of("TIME(6), zero-date switch on", column("time", "time(6)").datetimePrecision(6), ZERO_DATE_ON),
            of("YEAR", column("year", "year"), SWITCHES_OFF),
            of("YEAR(4), pre-8.0.19 display width", column("year", "year(4)"), SWITCHES_OFF),
            of("YEAR, zero-date switch on", column("year", "year"), ZERO_DATE_ON),
            of("DATETIME(6) with datetime_precision contradicting column_type", column("datetime", "datetime(6)")
                    .datetimePrecision(3), SWITCHES_OFF),
            of("DATETIME without datetime_precision", column("datetime", "datetime"), SWITCHES_OFF),
            of("TIME UNSIGNED", column("time", "time unsigned").datetimePrecision(0), SWITCHES_OFF));

    private TemporalMappingCases() {
    }
}
