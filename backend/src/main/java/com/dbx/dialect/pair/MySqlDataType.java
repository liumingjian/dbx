package com.dbx.dialect.pair;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The {@code information_schema.COLUMNS.DATA_TYPE} values this pair decides, parsed once so that
 * {@link TypeMapper} dispatches by an exhaustive switch rather than a string lookup. A value that
 * does not parse is outside the whitelist. One enum per TP §6 family, so each family class switches
 * exhaustively over its own types and the compiler, not a {@code default} branch, proves coverage.
 */
sealed interface MySqlDataType permits NumericType, CharacterBinarySpecialType, TemporalType {

    String name();

    /** The {@code data_type} as {@code information_schema} spells it, compared without case. */
    static Optional<MySqlDataType> parse(String dataType) {
        String wanted = dataType.toLowerCase(Locale.ROOT);
        return Stream.<MySqlDataType[]>of(NumericType.values(), CharacterBinarySpecialType.values(),
                        TemporalType.values())
                .flatMap(Stream::of)
                .filter(type -> type.name().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }
}
