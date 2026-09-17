package com.dbx.dialect.api;

/** A column that may become the keyset column, and where it came from (ADR-0037 §Choice). Owned by slice 5. */
public record KeysetCandidate(ColumnCoordinate column, Origin origin) {

    public KeysetCandidate {
        Checks.present(column, "column");
        Checks.present(origin, "origin");
    }

    public enum Origin {
        PRIMARY_KEY,
        UNIQUE_INDEX
    }
}
