package com.dbx.architecture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Watches each boundary rule fail.
 *
 * <p>Four rules over fourteen shell packages are green because there is nothing to bite on, and
 * that green is the "looks like success" output ADR-0022 exists to prevent. So each rule is pointed
 * at {@code com.dbx.archfixture}, a tree that violates it on purpose, and has to report it — with a
 * message that names the rule and the ADR that owns it, because the next agent has to be able to
 * tell "fix the code" from "fix the rule" (ADR-0018 §Enforcement).
 */
class ModuleBoundaryRuleFixtureTest {

    private static final String FIXTURE_ROOT = "com.dbx.archfixture";

    private static final JavaClasses FIXTURE_CLASSES = new ClassFileImporter().importPackages(FIXTURE_ROOT);

    @Test
    void theNonApiReferenceRuleReportsItsFixture() {
        String failure = failureOf(ModuleBoundaryRules.noReferenceIntoAnotherModulesNonApiPackage(FIXTURE_ROOT));

        assertNames(failure, "rule 1");
        assertTrue(
                failure.contains("ReachesIntoDialectInternals") && failure.contains("DialectInternal"),
                "the rule names the class that reached and what it reached: " + failure);
    }

    @Test
    void theModuleCycleRuleReportsItsFixture() {
        String failure = failureOf(ModuleBoundaryRules.noDependencyCycleBetweenModules(FIXTURE_ROOT));

        assertNames(failure, "rule 2");
        assertTrue(
                failure.contains("condition") && failure.contains("preflight"),
                "the rule names both modules of the cycle: " + failure);
    }

    @Test
    void thePureModuleRuleReportsItsFixture() {
        String failure = failureOf(ModuleBoundaryRules.noPureModuleDependsOnAnEffect(FIXTURE_ROOT));

        assertNames(failure, "rule 3");
        assertTrue(failure.contains("JdbcTemplate"), "the rule names the JdbcTemplate: " + failure);
        assertTrue(failure.contains("HttpClient"), "the rule names the HTTP client: " + failure);
        assertTrue(failure.contains("Clock"), "the rule names the clock: " + failure);
        assertTrue(failure.contains("reads the wall clock"), "the rule names the now() read: " + failure);
    }

    @Test
    void theWorkflowCommandCallerRuleReportsItsFixture() {
        String failure =
                failureOf(ModuleBoundaryRules.noCallerButOrchestrationReferencesWorkflowCommands(FIXTURE_ROOT));

        assertNames(failure, "rule 4");
        assertTrue(failure.contains("CallsWorkflowCommand"), "the rule names the caller: " + failure);
    }

    /** The fixture tree is the rules' subject here and must never be the production run's. */
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

    private static void assertNames(String failure, String rule) {
        assertTrue(failure.contains("ADR-0018"), "the failure names the ADR that owns the rule: " + failure);
        assertTrue(failure.contains(rule), "the failure names the rule: " + failure);
    }
}
