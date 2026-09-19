package com.dbx.dialect.api;

/**
 * Why a supplemental statement is emitted commented out (ADR-0026 §Supplemental SQL; obligation 25). The codes
 * are stable: reports count deferred objects by them. Adding one is a comment on spec #123, not an
 * implementation detail.
 */
public enum SupplementalCommentReason {
    /** The statement touches a column the mapping rules pruned. */
    PRUNED_COLUMN,
    /** A foreign key references a table outside the migration scope. */
    REFERENCED_TABLE_OUT_OF_SCOPE,
    /** MySQL {@code col(n)} indexes a prefix; PostgreSQL has no equivalent. */
    PREFIX_KEY_PART,
    /** A functional key part; its MySQL expression is not translated. */
    EXPRESSION_KEY_PART,
    /** A {@code FULLTEXT} or {@code SPATIAL} index has no equivalent PostgreSQL index. */
    FULLTEXT_OR_SPATIAL_INDEX,
    /**
     * An ordinary {@code INVISIBLE} index. PostgreSQL has no invisible index, so building it live would change
     * query plans the source deliberately did not. A <em>unique</em> invisible index is not this: MySQL still
     * enforces it, so it is a constraint and stays executable (ADR-0026 as amended by #133).
     */
    INVISIBLE_INDEX,
    /** {@code ON UPDATE CURRENT_TIMESTAMP} needs a trigger, which #23 forbids DBX to generate. */
    ON_UPDATE_CURRENT_TIMESTAMP,
    /**
     * A MySQL {@code DEFAULT (expression)}. Its expression is not translated, for the reason
     * {@link #EXPRESSION_KEY_PART} is not: DBX cannot prove a translated expression means the same thing in
     * PostgreSQL (ADR-0026 as amended by #133).
     */
    EXPRESSION_DEFAULT,
    /** A MySQL collation has no reliable PostgreSQL equivalent. */
    COLLATION
}
