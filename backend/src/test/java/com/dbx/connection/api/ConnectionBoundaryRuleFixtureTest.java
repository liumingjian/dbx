package com.dbx.connection.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.architecture.BackendModules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Watches every rule of {@link ConnectionBoundaryRules} fail.
 *
 * <p>{@code connection} is a leaf with no dependencies and no effects, so all six rules are green over
 * the real sources because there is nothing to bite on — the "looks like success" output ADR-0022 exists
 * to prevent. So each rule is pointed at {@code com.dbx.archfixture}, where
 * {@code archfixture/connection/} and its two callers violate it on purpose, and has to report the
 * offending class by name: the next agent must be able to tell "fix the code" from "fix the rule"
 * (ADR-0018 §Enforcement).
 */
class ConnectionBoundaryRuleFixtureTest {

    private static final String FIXTURE_ROOT = "com.dbx.archfixture";

    private static final JavaClasses FIXTURE_CLASSES = new ClassFileImporter().importPackages(FIXTURE_ROOT);

    @Test
    void theLeafRuleReportsItsFixture() {
        String failure = failureOf(ConnectionBoundaryRules.connectionDependsOnNoOtherModule(FIXTURE_ROOT));

        assertNames(failure, "obligation 1");
        assertTrue(
                failure.contains("ReachesIntoWorkflow") && failure.contains("names module workflow"),
                "the rule names the connection class that reached and the module it reached: " + failure);
    }

    @Test
    void theNoTableRuleReportsItsFixture() {
        String failure =
                failureOf(ConnectionBoundaryRules.connectionHoldsNoTableAndOpensNoConnection(FIXTURE_ROOT));

        assertNames(failure, "obligations 2 and 4");
        assertTrue(failure.contains("JdbcTemplate"), "the rule names the JdbcTemplate: " + failure);
        assertTrue(failure.contains("DriverManager"), "the rule names the DriverManager: " + failure);
        assertTrue(failure.contains("HttpClient"), "the rule names the HTTP client: " + failure);
        assertTrue(
                failure.contains("CredentialRepository") && failure.contains("is a repository"),
                "the rule names the repository: " + failure);
    }

    @Test
    void theInboundReferenceRuleReportsBothCallers() {
        String failure =
                failureOf(ConnectionBoundaryRules.neitherGatewayNorConnectorReferencesConnection(FIXTURE_ROOT));

        assertNames(failure, "obligation 3");
        assertTrue(failure.contains("ReadsConnectionApi"), "the rule names the gateway class: " + failure);
        assertTrue(failure.contains("DecryptsItsOwnSecret"), "the rule names the connector class: " + failure);
    }

    @Test
    void theApiOnlyRuleReportsItsFixture() {
        String failure = failureOf(ConnectionBoundaryRules.onlyTheConnectionApiIsReferencedFromOutside(FIXTURE_ROOT));

        assertNames(failure, "obligation 5");
        assertTrue(
                failure.contains("ReachesIntoConnectionInternals") && failure.contains("PersistingConnection"),
                "the rule names the caller and the internal it reached: " + failure);
    }

    @Test
    void theWritesNothingRuleReportsItsFixture() {
        String failure = failureOf(ConnectionBoundaryRules.connectionWritesNoFile(FIXTURE_ROOT));

        assertNames(failure, "obligations 10 and 19");
        assertTrue(
                failure.contains("WritesTheKeyFile") && failure.contains("Files.writeString writes"),
                "the rule names the class that wrote and the call it wrote with: " + failure);
    }

    @Test
    void theExactlySixEntryPointsRuleReportsItsFixture() {
        String failure = failureOf(ConnectionBoundaryRules.theApiExposesExactlySixEntryPoints(FIXTURE_ROOT));

        assertNames(failure, "E20");
        assertTrue(
                failure.contains("declares 7 entry points") && failure.contains("unexpected: [rotate]"),
                "the rule names the count and the seventh entry point: " + failure);
    }

    /** The fixture tree is these rules' subject and must never be the production run's. */
    @Test
    void theFixtureTreeIsExcludedFromTheProductionRun() {
        JavaClasses productionClasses = BackendModules.productionClasses();

        assertTrue(
                productionClasses.stream().noneMatch(c -> c.getPackageName().startsWith(FIXTURE_ROOT)),
                "no fixture class reaches the production run");
    }

    private static String failureOf(ArchRule rule) {
        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> rule.check(FIXTURE_CLASSES),
                "the rule has to report the fixture that violates it");
        return failure.getMessage();
    }

    private static void assertNames(String failure, String obligation) {
        assertTrue(
                failure.contains("docs/spec/connection.md"),
                "the failure names the sub-spec that owns the obligation: " + failure);
        assertTrue(failure.contains(obligation), "the failure names the obligation: " + failure);
    }
}
