package com.dbx.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;

/**
 * The one declaration of ADR-0036's module table as the boundary rules see it.
 *
 * <p>Every rule in {@link ModuleBoundaryRules} is derived from these three lists, so adding a
 * module to the backend is one edit here rather than an edit per rule. The lists are relative
 * names: the rules prefix them with a root package, which is {@link #PRODUCTION_ROOT} for the real
 * sources and the fixture root for the tests that watch each rule fail.
 */
public final class BackendModules {

    /** The fourteen Java packages of ADR-0036. {@code release} is a sub-spec and has no package. */
    public static final List<String> MODULES = List.of(
            "dialect",
            "preflight",
            "contract",
            "scheduling",
            "connector",
            "validation",
            "diagnosis",
            "gateway",
            "connection",
            "environment",
            "condition",
            "workflow",
            "orchestration",
            "web");

    /**
     * ADR-0036 §Dependencies and purity, which supersedes ADR-0018's older pure-core list. A module
     * that is only partly pure names its pure subpackage, because the rest of it is the effectful
     * shell the pure half is protected from: {@code connector}'s data-plane clients are allowed to
     * speak HTTP, {@code connector.judge} is not.
     */
    public static final List<String> PURE_PACKAGES = List.of(
            "dialect",
            "contract",
            "scheduling",
            "diagnosis",
            "condition",
            "validation",
            "connector.judge");

    /** The single-writer entry point of ADR-0018 §Dependency direction. */
    public static final String SINGLE_WRITER_PACKAGE = "workflow.api.command";

    /** The module that owns {@link #SINGLE_WRITER_PACKAGE}; it is not one of its callers. */
    public static final String SINGLE_WRITER_OWNER = "workflow";

    /** ADR-0018 §Enforcement: {@code orchestration} is the sole caller of the command side. */
    public static final List<String> SINGLE_WRITER_CALLERS = List.of("orchestration");

    /** The package root the fourteen modules live under in the production sources. */
    public static final String PRODUCTION_ROOT = "com.dbx";

    /**
     * The classes the rules are enforced against: production sources with the violation fixtures
     * kept out.
     *
     * <p>It lives here, beside the module table, rather than in {@code ModuleBoundaryTest}, because
     * two tests depend on this exact import and they have to depend on the <em>same</em> one.
     * {@code ModuleBoundaryTest} checks the rules against it; {@code ModuleBoundaryRuleFixtureTest}
     * asserts no fixture class reaches it. When each built its own importer, the second test was
     * asserting a filter it had just written itself, so deleting the real exclusion would not have
     * failed anything — the vacuous green ADR-0022 exists to catch.
     *
     * <p>Both import options are needed: the first keeps test classes out, the second says why the
     * fixture tree in particular must never reach this run.
     */
    public static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(location -> !location.contains("/com/dbx/archfixture/"))
                .importPackages(PRODUCTION_ROOT);
    }

    private BackendModules() {
    }
}
