package com.dbx.dialect.api;

/** One line of supplemental SQL for the DBA: executable, or a comment stating why not (ADR-0026). Slice 7. */
public record Statement(String sql, Disposition disposition) {

    public Statement {
        Checks.nonEmpty(sql, "sql");
        Checks.present(disposition, "disposition");
    }

    public enum Disposition {
        EXECUTABLE,
        COMMENT_ONLY
    }
}
