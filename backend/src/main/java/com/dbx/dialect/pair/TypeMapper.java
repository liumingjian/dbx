package com.dbx.dialect.pair;

import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingUnsupportedReason;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Unsupported;
import java.util.Objects;

/**
 * The pair-owned {@code TypeMapper} of TP §6.1–6.4: a pure function of the source facts and the two
 * task switches. It only dispatches; each TP §6 family decides in its own class, so the tickets
 * landing families edit different files.
 */
final class TypeMapper {

    private TypeMapper() {
    }

    static MappingDecision map(SourceColumn column, MappingOptions options) {
        Objects.requireNonNull(column, "column is required");
        Objects.requireNonNull(options, "options are required");
        return MySqlDataType.parse(column.dataType())
                .map(type -> family(type, column, options))
                .orElseGet(() -> new Unsupported(MappingUnsupportedReason.NOT_WHITELISTED, column));
    }

    private static MappingDecision family(MySqlDataType type, SourceColumn column, MappingOptions options) {
        return switch (type) {
            case TINYINT, SMALLINT, MEDIUMINT, INT, BIGINT, DECIMAL, FLOAT, DOUBLE, BIT ->
                    NumericMapping.map(type, column, options);
            case CHAR, VARCHAR, TINYTEXT, TEXT, MEDIUMTEXT, LONGTEXT, BINARY, VARBINARY, TINYBLOB, BLOB, MEDIUMBLOB,
                    LONGBLOB, ENUM, SET, JSON, GEOMETRY, POINT, LINESTRING, POLYGON, MULTIPOINT, MULTILINESTRING,
                    MULTIPOLYGON, GEOMCOLLECTION, VECTOR ->
                    CharacterBinarySpecialMapping.map(type, column, options);
            case DATE, DATETIME, TIMESTAMP, TIME, YEAR ->
                    TemporalMapping.map(type, column, options);
        };
    }
}
