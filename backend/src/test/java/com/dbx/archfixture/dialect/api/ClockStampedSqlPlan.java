package com.dbx.archfixture.dialect.api;

import java.time.Clock;
import java.time.Instant;

/**
 * A {@code dialect.api} plan type that stamps when it was built into its fingerprint. Rule 3 must
 * report it: the effect is a clock, and a fingerprint over it is no longer a function of content.
 */
public final class ClockStampedSqlPlan {

    private final String sql;
    private final Clock clock = null;

    public ClockStampedSqlPlan(String sql) {
        this.sql = sql;
    }

    public String fingerprint() {
        return sql + "@" + Instant.now();
    }
}
