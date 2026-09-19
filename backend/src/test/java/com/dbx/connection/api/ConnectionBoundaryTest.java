package com.dbx.connection.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.architecture.BackendModules;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/spec/connection.md} §Obligations A over the real backend sources: the leaf position
 * (1), no table and no connection of its own (2, 4), no inbound reference from {@code gateway} or
 * {@code connector} (3), nothing but {@code api} visible from outside (5), no file written (10, 19),
 * and exactly six entry points (E20, pulled forward from slice 4 by #149).
 *
 * <p>{@code ConnectionBoundaryRuleFixtureTest} is the other half: it watches every one of these same
 * rules fail against a fixture that violates it. Neither half is worth anything alone — a leaf module
 * with two dozen small types satisfies all of these by having almost no code.
 */
class ConnectionBoundaryTest {

    /**
     * Imported by {@link BackendModules#productionClasses()} so that the fixture test asserting the
     * exclusion holds is talking about this very import and not one of its own.
     */
    private static final JavaClasses PRODUCTION_CLASSES = BackendModules.productionClasses();

    @Test
    void connectionDependsOnNoOtherModule() {
        ConnectionBoundaryRules.connectionDependsOnNoOtherModule(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void connectionHoldsNoTableAndOpensNoConnection() {
        ConnectionBoundaryRules.connectionHoldsNoTableAndOpensNoConnection(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void neitherGatewayNorConnectorReferencesConnection() {
        ConnectionBoundaryRules.neitherGatewayNorConnectorReferencesConnection(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyTheConnectionApiIsReferencedFromOutside() {
        ConnectionBoundaryRules.onlyTheConnectionApiIsReferencedFromOutside(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void connectionWritesNoFile() {
        ConnectionBoundaryRules.connectionWritesNoFile(BackendModules.PRODUCTION_ROOT).check(PRODUCTION_CLASSES);
    }

    @Test
    void theApiExposesExactlySixEntryPoints() {
        ConnectionBoundaryRules.theApiExposesExactlySixEntryPoints(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    /**
     * Obligation 2's other half, which no dependency rule can see: a migration is a resource, not a
     * type. {@code connection} ships no schema of its own — H2 belongs to {@code workflow} — so neither
     * the module tree nor the resources carry SQL in its name.
     *
     * <p>Gradle runs tests with the project directory as the working directory, as
     * {@code ModuleReadmeLimitTest} relies on too.
     */
    @Test
    void connectionShipsNoSchemaOfItsOwn() throws IOException {
        Path module = Path.of("src", "main", "java", "com", "dbx", "connection");
        try (Stream<Path> tree = Files.walk(module)) {
            List<Path> sql = tree.filter(path -> path.getFileName().toString().endsWith(".sql")).toList();
            assertTrue(
                    sql.isEmpty(),
                    "docs/spec/connection.md obligation 2: connection holds no H2 table, so it ships no SQL; "
                            + "found " + sql);
        }

        Path resources = Path.of("src", "main", "resources");
        if (Files.isDirectory(resources)) {
            try (Stream<Path> tree = Files.walk(resources)) {
                List<Path> owned = tree.filter(Files::isRegularFile)
                        .filter(path -> path.toString().contains("connection"))
                        .toList();
                assertTrue(
                        owned.isEmpty(),
                        "docs/spec/connection.md obligation 2: a Flyway migration or schema resource named for "
                                + "connection means the module grew a table; workflow owns H2 (ADR-0036 "
                                + "§Modules). Found " + owned);
            }
        }
    }
}
