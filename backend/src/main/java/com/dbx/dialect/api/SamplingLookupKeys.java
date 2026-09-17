package com.dbx.dialect.api;

import java.util.List;

/** Sampled key tuples to look up on the target, bound as typed values (TP §9.3). Owned by slice 8. */
public record SamplingLookupKeys(TargetTableCoordinate table, List<TargetIdentifier> keyColumns, List<ResultRows.Row> keys) {

    public SamplingLookupKeys {
        Checks.present(table, "table");
        keyColumns = Checks.nonEmptyList(keyColumns, "keyColumns");
        keys = Checks.list(keys, "keys");
    }
}
