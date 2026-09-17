package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

/**
 * One golden row of the type-mapping matrix: a named source-metadata variant under one pair of switches.
 * Also holds what every type-mapping test shares: the pair, the switch combinations and {@link #supported}.
 */
record MappingCase(String name, SourceColumn column, MappingOptions options) {

    static final DatabasePair PAIR = (DatabasePair) DialectCatalog.compileTime()
            .select(new ProductVersion("MySQL", "8.0.36"), new ProductVersion("PostgreSQL", "15.4"));

    static final MappingOptions SWITCHES_OFF = MappingOptions.DEFAULTS;
    static final MappingOptions BOOLEAN_ON = new MappingOptions(true, false);
    static final MappingOptions ZERO_DATE_ON = new MappingOptions(false, true);
    static final List<MappingOptions> ALL_OPTIONS = List.of(
            SWITCHES_OFF, BOOLEAN_ON, ZERO_DATE_ON, new MappingOptions(true, true));

    static MappingCase of(String name, SourceColumns column, MappingOptions options) {
        return new MappingCase(name, column.build(), options);
    }

    /** The decision for {@code column}, asserted to be {@link Supported}. */
    static Supported supported(SourceColumns column, MappingOptions options) {
        return assertInstanceOf(Supported.class, PAIR.map(column.build(), options),
                "expected a supported decision for " + column.build().columnType());
    }
}
