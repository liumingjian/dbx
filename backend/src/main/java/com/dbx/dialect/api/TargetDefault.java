package com.dbx.dialect.api;

/**
 * A whitelisted target column default (ADR-0011 §DDL; TP §7.3). Closed: every other source default is
 * omitted and reported by {@code contract} before a table reaches the dialect, so it is not expressible.
 */
public sealed interface TargetDefault {

    /**
     * A constant, rendered by the target dialect's one typed literal renderer (ADR-0008 §Plans as amended by
     * #123). A typed {@code NULL} is refused: "no default" is spelled only as an absent default, so one table
     * cannot have two fingerprints.
     */
    record Literal(SqlValue value) implements TargetDefault {

        public Literal {
            Checks.present(value, "value");
            if (value instanceof SqlValue.Null) {
                throw new IllegalArgumentException("TP §7.3: a NULL default is no default; leave the default absent");
            }
        }
    }

    /**
     * A source {@code CURRENT_TIMESTAMP(n)}, already at the mapped precision {@code min(n,3)} (TP §6.4), and
     * rendered as {@code LOCALTIMESTAMP(precision)} (TP §7.3).
     */
    record LocalTimestamp(int precision) implements TargetDefault {

        /** Connect logical time carries milliseconds, so a mapped precision never exceeds 3 (TP §6.4). */
        public static final int MAX_PRECISION = 3;

        public LocalTimestamp {
            if (precision < 0 || precision > MAX_PRECISION) {
                throw new IllegalArgumentException("TP §6.4: CURRENT_TIMESTAMP(n) arrives at the mapped precision "
                        + "min(n,3), so the precision is 0..3, was " + precision);
            }
        }
    }
}
