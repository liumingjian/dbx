package com.dbx.dialect.api;

/** A source column, by database, table and column name, all exact. */
public record ColumnCoordinate(String database, String table, String column) implements SourceCoordinate {

    public ColumnCoordinate {
        Checks.nonEmpty(database, "database");
        Checks.nonEmpty(table, "table");
        Checks.nonEmpty(column, "column");
    }
}
