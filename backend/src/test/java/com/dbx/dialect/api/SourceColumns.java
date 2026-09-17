package com.dbx.dialect.api;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Builds a {@link SourceColumn} the way {@code information_schema.COLUMNS} reports it: start from
 * {@code data_type} and {@code column_type}, then set only the facts that row would carry. Every
 * other fact stays absent, never a made-up default.
 */
final class SourceColumns {

    private final String dataType;
    private final String columnType;
    private boolean unsigned;
    private OptionalLong characterMaximumLength = OptionalLong.empty();
    private OptionalLong characterOctetLength = OptionalLong.empty();
    private OptionalInt numericPrecision = OptionalInt.empty();
    private OptionalInt numericScale = OptionalInt.empty();
    private OptionalInt datetimePrecision = OptionalInt.empty();
    private Optional<String> characterSetName = Optional.empty();
    private Optional<String> collationName = Optional.empty();
    private Nullability nullability = Nullability.NULLABLE;
    private Optional<String> columnDefault = Optional.empty();
    private String extra = "";
    private int ordinalPosition = 1;

    private SourceColumns(String dataType, String columnType) {
        this.dataType = dataType;
        this.columnType = columnType;
    }

    /** A column of {@code data_type} and {@code column_type}; the unsigned flag follows {@code column_type}. */
    static SourceColumns column(String dataType, String columnType) {
        SourceColumns builder = new SourceColumns(dataType, columnType);
        builder.unsigned = columnType.contains("unsigned");
        return builder;
    }

    /** Overrides the flag derived from {@code column_type}, to build contradictory facts on purpose. */
    SourceColumns unsignedFlag(boolean value) {
        unsigned = value;
        return this;
    }

    SourceColumns precision(int precision) {
        numericPrecision = OptionalInt.of(precision);
        return this;
    }

    SourceColumns precision(int precision, int scale) {
        numericPrecision = OptionalInt.of(precision);
        numericScale = OptionalInt.of(scale);
        return this;
    }

    SourceColumns characterLength(long characters, long octets) {
        characterMaximumLength = OptionalLong.of(characters);
        characterOctetLength = OptionalLong.of(octets);
        return this;
    }

    SourceColumns datetimePrecision(int precision) {
        datetimePrecision = OptionalInt.of(precision);
        return this;
    }

    SourceColumns charset(String charset, String collation) {
        characterSetName = Optional.of(charset);
        collationName = Optional.of(collation);
        return this;
    }

    SourceColumns notNull() {
        nullability = Nullability.NOT_NULL;
        return this;
    }

    SourceColumns columnDefault(String value) {
        columnDefault = Optional.of(value);
        return this;
    }

    SourceColumns extra(String value) {
        extra = value;
        return this;
    }

    SourceColumns ordinal(int value) {
        ordinalPosition = value;
        return this;
    }

    SourceColumn build() {
        return new SourceColumn(new ColumnCoordinate("shop", "orders", "c"), dataType, columnType, unsigned,
                characterMaximumLength, characterOctetLength, numericPrecision, numericScale, datetimePrecision,
                characterSetName, collationName, nullability, columnDefault, extra, ordinalPosition);
    }
}
