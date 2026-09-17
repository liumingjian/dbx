package com.dbx.dialect.api;

/** One golden row of the type-mapping matrix: a named source-metadata variant under one pair of switches. */
record MappingCase(String name, SourceColumn column, MappingOptions options) {

    static final MappingOptions SWITCHES_OFF = MappingOptions.DEFAULTS;
    static final MappingOptions BOOLEAN_ON = new MappingOptions(true, false);
    static final MappingOptions ZERO_DATE_ON = new MappingOptions(false, true);

    static MappingCase of(String name, SourceColumns column, MappingOptions options) {
        return new MappingCase(name, column.build(), options);
    }
}
