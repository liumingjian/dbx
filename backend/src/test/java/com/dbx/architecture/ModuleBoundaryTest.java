package com.dbx.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The four boundary rules, run against the real backend sources.
 *
 * <p>{@code ModuleBoundaryRuleFixtureTest} is the other half: it watches each of these same rules
 * fail. Neither half is worth anything alone.
 */
class ModuleBoundaryTest {

    /**
     * Production classes only. The violation fixtures live in the test sources under
     * {@code com.dbx.archfixture}, so both import options are needed: the first keeps test classes
     * out, the second says why the fixture tree in particular must never reach this run.
     */
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(location -> !location.contains("/com/dbx/archfixture/"))
            .importPackages(BackendModules.PRODUCTION_ROOT);

    @Test
    void noModuleReferencesAnotherModulesNonApiPackage() {
        ModuleBoundaryRules.noReferenceIntoAnotherModulesNonApiPackage(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void theModuleGraphIsAcyclic() {
        ModuleBoundaryRules.noDependencyCycleBetweenModules(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void pureModulesDependOnNoEffect() {
        ModuleBoundaryRules.noPureModuleDependsOnAnEffect(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyOrchestrationReferencesWorkflowCommands() {
        ModuleBoundaryRules.noCallerButOrchestrationReferencesWorkflowCommands(BackendModules.PRODUCTION_ROOT)
                .check(PRODUCTION_CLASSES);
    }
}
