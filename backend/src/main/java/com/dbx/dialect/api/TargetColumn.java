package com.dbx.dialect.api;

/** A target column as the contract states it and as the catalog reads it back. Owned by slices 7 and 8. */
public record TargetColumn(TargetIdentifier name, TargetType type, Nullability nullability) {

    public TargetColumn {
        Checks.present(name, "name");
        Checks.present(type, "type");
        Checks.present(nullability, "nullability");
    }
}
