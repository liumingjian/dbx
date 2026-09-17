package com.dbx.dialect.pair;

import java.util.Locale;
import java.util.Optional;

/**
 * The {@code information_schema.COLUMNS.DATA_TYPE} values this pair decides, parsed once so that
 * {@link TypeMapper} dispatches by an exhaustive switch rather than a string lookup. A value that
 * does not parse is outside the whitelist. Grouped by TP §6 family; each group routes to one class.
 */
enum MySqlDataType {
    // numeric (TP §6.2) → NumericMapping
    TINYINT,
    SMALLINT,
    MEDIUMINT,
    INT,
    BIGINT,
    DECIMAL,
    FLOAT,
    DOUBLE,
    BIT,

    // character, binary and special (TP §6.3) → CharacterBinarySpecialMapping
    CHAR,
    VARCHAR,
    TINYTEXT,
    TEXT,
    MEDIUMTEXT,
    LONGTEXT,
    BINARY,
    VARBINARY,
    TINYBLOB,
    BLOB,
    MEDIUMBLOB,
    LONGBLOB,
    ENUM,
    SET,
    JSON,
    GEOMETRY,
    POINT,
    LINESTRING,
    POLYGON,
    MULTIPOINT,
    MULTILINESTRING,
    MULTIPOLYGON,
    GEOMCOLLECTION,
    VECTOR,

    // temporal (TP §6.4) → TemporalMapping
    DATE,
    DATETIME,
    TIMESTAMP,
    TIME,
    YEAR;

    /** The {@code data_type} as {@code information_schema} spells it, compared without case. */
    static Optional<MySqlDataType> parse(String dataType) {
        String wanted = dataType.toLowerCase(Locale.ROOT);
        for (MySqlDataType type : values()) {
            if (type.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
