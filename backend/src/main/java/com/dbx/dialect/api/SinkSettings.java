package com.dbx.dialect.api;

/**
 * The JDBC Sink settings obligation 29 fixes. Slice 7 makes the fixed values the only expressible
 * ones; until then this is the shape they are declared in.
 */
public record SinkSettings(
        boolean autoCreate,
        boolean autoEvolve,
        InsertMode insertMode,
        PrimaryKeyMode primaryKeyMode,
        boolean deleteEnabled,
        QuoteIdentifiers quoteIdentifiers) {

    public SinkSettings {
        Checks.present(insertMode, "insertMode");
        Checks.present(primaryKeyMode, "primaryKeyMode");
        Checks.present(quoteIdentifiers, "quoteIdentifiers");
    }

    public enum InsertMode {
        INSERT
    }

    public enum PrimaryKeyMode {
        NONE
    }

    public enum QuoteIdentifiers {
        ALWAYS
    }
}
