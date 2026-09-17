package com.dbx.dialect.api;

import java.util.List;

/** The table and key columns deterministic sampling orders by (TP §9.3). Owned by slice 6. */
public record SamplingKey(TableCoordinate table, List<ColumnCoordinate> keyColumns) {

    public SamplingKey {
        Checks.present(table, "table");
        keyColumns = Checks.nonEmptyList(keyColumns, "keyColumns");
    }
}
