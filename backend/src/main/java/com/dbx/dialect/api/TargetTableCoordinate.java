package com.dbx.dialect.api;

/** An approved target table (or sequence) by schema and name. */
public record TargetTableCoordinate(TargetIdentifier schema, TargetIdentifier name) {

    public TargetTableCoordinate {
        Checks.present(schema, "schema");
        Checks.present(name, "name");
    }
}
