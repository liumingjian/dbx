package com.dbx.archfixture.connection;

import java.net.http.HttpClient;
import java.sql.DriverManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A {@code connection} that persists and talks to the network: obligations 2 and 4's violation.
 *
 * <p>Four planted effects at once — a {@code JdbcTemplate}, a repository of its own, a {@code
 * DriverManager} and an HTTP client — because the rule reports them one by one and a module that grew
 * any single one of them would have broken the same obligation. Kafka and Flyway are on the rule's list
 * too but cannot be planted: neither is a dependency of this build (ADR-0012), so there is no type to
 * name.
 */
public final class PersistingConnection {

    private final JdbcTemplate jdbcTemplate = null;
    private final CredentialRepository repository = null;
    private final HttpClient httpClient = null;

    public void openItsOwnConnection() throws Exception {
        DriverManager.getConnection("jdbc:h2:mem:not-connections-business");
    }
}
