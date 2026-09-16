package com.dbx.archfixture.dialect;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A pure module reaching for all three effects rule 3 forbids, plus the wall-clock read that names
 * no Clock type.
 */
public final class ImpureDialect {

    private final JdbcTemplate jdbcTemplate = null;
    private final HttpClient httpClient = null;
    private final Clock clock = null;

    public Instant readTheWallClock() {
        return Instant.now();
    }
}
