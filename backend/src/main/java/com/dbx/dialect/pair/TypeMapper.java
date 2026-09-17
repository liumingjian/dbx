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

    /**
     * MySQL accepts {@code AUTO_INCREMENT} only on integer and floating-point columns, so on another
     * family it is a contradiction; {@link NumericMapping} decides what it means for its own types.
     */
    private static MappingDecision family(MySqlDataType type, SourceColumn column, MappingOptions options) {
        return switch (type) {
            case NumericType numeric -> NumericMapping.map(numeric, column, options);
            case CharacterBinarySpecialType characterBinarySpecial -> SourceFacts.autoIncrement(column)
                    ? SourceFacts.inconsistent(column)
                    : CharacterBinarySpecialMapping.map(characterBinarySpecial, column, options);
            case TemporalType temporal -> SourceFacts.autoIncrement(column)
                    ? SourceFacts.inconsistent(column)
                    : TemporalMapping.map(temporal, column, options);
        };
    }
}
