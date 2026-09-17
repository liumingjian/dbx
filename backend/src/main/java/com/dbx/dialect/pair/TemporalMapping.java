package com.dbx.dialect.pair;

import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingUnsupportedReason;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Unsupported;

/**
 * TP §6.4: temporal types and the zero-date switch (#117). Until that ticket lands these types are
 * outside the whitelist, and the exhaustive test's deferred list names every one of them.
 */
final class TemporalMapping {

    private TemporalMapping() {
    }

    static MappingDecision map(MySqlDataType type, SourceColumn column, MappingOptions options) {
        return new Unsupported(MappingUnsupportedReason.NOT_WHITELISTED, column);
    }
}
