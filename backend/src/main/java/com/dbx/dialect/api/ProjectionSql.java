package com.dbx.dialect.api;

/**
 * The rendered {@code SELECT <expr> AS <alias>, …} projection. {@code connector} places this exact
 * text into the Source query and fingerprints it, so it is rendered once, here (#92). Slice 5.
 */
public record ProjectionSql(String text) {

    public ProjectionSql {
        Checks.nonEmpty(text, "text");
    }
}
