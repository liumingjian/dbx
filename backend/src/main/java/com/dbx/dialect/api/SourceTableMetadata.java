package com.dbx.dialect.api;

import java.util.List;

/** One source table's normalised metadata. Keys, indexes and statistics are added by slice 5. */
public record SourceTableMetadata(TableCoordinate table, List<SourceColumn> columns) {

    public SourceTableMetadata {
        Checks.present(table, "table");
        columns = Checks.list(columns, "columns");
    }
}
