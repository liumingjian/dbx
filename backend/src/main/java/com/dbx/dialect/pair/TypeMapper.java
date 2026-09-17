package com.dbx.dialect.pair;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.SourceColumn;

/** The pair-owned {@code TypeMapper} of TP §6.1–6.4. Slice 3. */
final class TypeMapper {

    private TypeMapper() {
    }

    static MappingDecision map(SourceColumn column, MappingOptions options) {
        throw new NotImplementedInSlice("pair.map", 3);
    }
}
