package com.dbx.dialect.api;

import java.util.List;

/** The minimal writable table an approved contract describes (ADR-0011 §DDL). Keys and identity are added by slice 7. */
public record TargetTable(TargetTableCoordinate table, List<TargetColumn> columns) {

    public TargetTable {
        Checks.present(table, "table");
        columns = Checks.nonEmptyList(columns, "columns");
    }
}
