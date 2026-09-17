package com.dbx.dialect.api;

/** A MySQL database, which one task maps to one PostgreSQL schema (TP §7.1). */
public record SchemaCoordinate(String database) implements SourceCoordinate {

    public SchemaCoordinate {
        Checks.nonEmpty(database, "database");
    }
}
