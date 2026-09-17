package com.dbx.dialect.api;

/** A source table, by database and table name, both exact. */
public record TableCoordinate(String database, String table) implements SourceCoordinate {

    public TableCoordinate {
        Checks.nonEmpty(database, "database");
        Checks.nonEmpty(table, "table");
    }
}
