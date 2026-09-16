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
     * Production classes only, imported by {@link BackendModules#productionClasses()} so that the
     * fixture test asserting the exclusion holds is talking about this very import and not one of
     * its own.
     */
    private static final JavaClasses PRODUCTION_CLASSES = BackendModules.productionClasses();

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
