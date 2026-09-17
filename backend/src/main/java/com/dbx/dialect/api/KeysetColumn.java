package com.dbx.dialect.api;

/** The approved keyset column (键集列) a table is read in chunks by (ADR-0037). */
public record KeysetColumn(ColumnCoordinate column) {

    public KeysetColumn {
        Checks.present(column, "column");
    }
}
