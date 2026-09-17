package com.dbx.dialect.api;

import java.util.Optional;

/**
 * One statement of supplemental SQL for the DBA: executable, or commented out with a stable reason (ADR-0026).
 * The reason is present exactly when the disposition is {@link Disposition#COMMENT_ONLY}. Slice 7.
 */
public record Statement(String sql, Disposition disposition, Optional<SupplementalCommentReason> reason) {

    public Statement {
        Checks.nonEmpty(sql, "sql");
        Checks.present(disposition, "disposition");
        Checks.present(reason, "reason");
        if (reason.isPresent() != (disposition == Disposition.COMMENT_ONLY)) {
            throw new IllegalArgumentException("ADR-0026: a reason is present exactly when a statement is "
                    + "COMMENT_ONLY, but disposition " + disposition + " has reason " + reason);
        }
    }

    public static Statement executable(String sql) {
        return new Statement(sql, Disposition.EXECUTABLE, Optional.empty());
    }

    public static Statement commentOnly(String sql, SupplementalCommentReason reason) {
        return new Statement(sql, Disposition.COMMENT_ONLY, Optional.of(Checks.present(reason, "reason")));
    }

    public enum Disposition {
        EXECUTABLE,
        COMMENT_ONLY
    }
}
