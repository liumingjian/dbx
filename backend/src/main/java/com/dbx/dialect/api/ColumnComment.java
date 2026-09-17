package com.dbx.dialect.api;

/**
 * A column's raw {@code COLUMNS.COLUMN_COMMENT}, empty when the column has none. It sits beside
 * {@link SourceColumn} rather than in it: a comment decides no mapping (TP §6.1), so it stays out of
 * the mapping fingerprint.
 */
public record ColumnComment(ColumnCoordinate column, String comment) {

    public ColumnComment {
        Checks.present(column, "column");
        Checks.present(comment, "comment");
    }
}
