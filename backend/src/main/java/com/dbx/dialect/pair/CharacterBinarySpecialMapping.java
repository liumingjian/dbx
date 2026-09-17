package com.dbx.dialect.pair;

import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingUnsupportedReason;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Unsupported;

/**
 * TP §6.3: character, binary and special types (#116). Until that ticket lands these types are
 * outside the whitelist, and the exhaustive test's deferred list names every one of them.
 */
final class CharacterBinarySpecialMapping {

    private CharacterBinarySpecialMapping() {
    }

    static MappingDecision map(MySqlDataType type, SourceColumn column, MappingOptions options) {
        return new Unsupported(MappingUnsupportedReason.NOT_WHITELISTED, column);
    }
}
