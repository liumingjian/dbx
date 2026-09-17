package com.dbx.dialect.api;

/** One required preflight on one column, as a mapping decision demanded it. Owned by slice 6. */
public record PreflightObligation(ColumnCoordinate column, RequiredPreflight preflight) {

    public PreflightObligation {
        Checks.present(column, "column");
        Checks.present(preflight, "preflight");
    }
}
