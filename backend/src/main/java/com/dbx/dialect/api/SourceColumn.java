package com.dbx.dialect.api;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One column's unmodified {@code information_schema} facts, the only input a mapping decision may
 * depend on (TP §6.1). Absent facts are empty, never a made-up default.
 */
public record SourceColumn(
        ColumnCoordinate coordinate,
        String dataType,
        String columnType,
        boolean unsigned,
        OptionalLong characterMaximumLength,
        OptionalLong characterOctetLength,
        OptionalInt numericPrecision,
        OptionalInt numericScale,
        OptionalInt datetimePrecision,
        Optional<String> characterSetName,
        Optional<String> collationName,
        Nullability nullability,
        Optional<String> columnDefault,
        String extra,
        int ordinalPosition)
        implements UnsupportedEvidence {

    public SourceColumn {
        Checks.present(coordinate, "coordinate");
        Checks.nonEmpty(dataType, "dataType");
        Checks.nonEmpty(columnType, "columnType");
        Checks.present(characterMaximumLength, "characterMaximumLength");
        Checks.present(characterOctetLength, "characterOctetLength");
        Checks.present(numericPrecision, "numericPrecision");
        Checks.present(numericScale, "numericScale");
        Checks.present(datetimePrecision, "datetimePrecision");
        Checks.present(characterSetName, "characterSetName");
        Checks.present(collationName, "collationName");
        Checks.present(nullability, "nullability");
        Checks.present(columnDefault, "columnDefault");
        Checks.present(extra, "extra");
        Checks.positive(ordinalPosition, "ordinalPosition");
    }
}
