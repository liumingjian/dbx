package com.dbx.dialect.pair;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.IdentifierMapping;
import com.dbx.dialect.api.MappingRule;
import com.dbx.dialect.api.SourceCoordinate;
import java.util.Optional;

/** Byte-counted identifier mapping and the deterministic rename of TP §7.1. Slice 4. */
final class IdentifierMapper {

    private IdentifierMapper() {
    }

    static IdentifierMapping mapIdentifier(SourceCoordinate sourceCoordinate, Optional<MappingRule> mappingRule) {
        throw new NotImplementedInSlice("pair.mapIdentifier", 4);
    }
}
