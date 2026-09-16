package com.dbx.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;

/**
 * The four boundary rules of ADR-0018 §Enforcement, built over {@link BackendModules}.
 *
 * <p>Every rule takes the package root it applies to. The production run passes
 * {@link BackendModules#PRODUCTION_ROOT}; {@code ModuleBoundaryRuleFixtureTest} passes the root of a
 * mirrored fixture tree that deliberately violates each rule. Four rules over fourteen shell
 * packages would otherwise pass vacuously, which is the "looks like success" outcome ADR-0022 was
 * written against, so the rules have to be the same objects in both runs.
 *
 * <p>Each failure message names the rule and the ADR that owns it: when one of these goes red, the
 * code is wrong, not the rule (ADR-0018 §Enforcement).
 */
public final class ModuleBoundaryRules {

    /**
     * Types a pure module may not touch, from ADR-0018's "JdbcTemplate, an HTTP client, or a
     * clock". Prefixes ending in a dot match a package; the others match one type. {@code java.sql}
     * as a whole is deliberately absent: a dialect maps to {@code java.sql.Types} constants without
     * ever opening a connection.
     */
    private static final List<String> IMPURE_TYPES = List.of(
            "org.springframework.jdbc.",
            "javax.sql.DataSource",
            "java.sql.Connection",
            "java.sql.DriverManager",
            "java.net.http.",
            "java.net.HttpURLConnection",
            "org.springframework.web.client.",
            "org.springframework.web.reactive.function.client.",
            "org.apache.hc.",
            "org.apache.http.",
            "okhttp3.",
            "java.time.Clock");

    private ModuleBoundaryRules() {
    }

    /** Rule 1: module A reaches module B only through {@code b.api}. */
    public static ArchRule noReferenceIntoAnotherModulesNonApiPackage(String root) {
        // A hand-written condition reports the offending dependency, so it is phrased positively and
        // driven from classes(): noClasses() inverts a custom condition's events.
        return classes()
                .should(new ArchCondition<JavaClass>("reference another module only through its api package") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String ownModule = moduleOf(root, item.getPackageName());
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String targetPackage = dependency.getTargetClass().getPackageName();
                            String targetModule = moduleOf(root, targetPackage);
                            if (targetModule == null || targetModule.equals(ownModule)) {
                                continue;
                            }
                            if (isInApiOf(root, targetModule, targetPackage)) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(
                                    item,
                                    dependency.getDescription()
                                            + " — reaches past " + targetModule + ".api into "
                                            + targetPackage));
                        }
                    }
                })
                .as("ADR-0018 §Enforcement rule 1: no reference into another module's non-api package")
                .because("only <module>.api may be referenced from outside a module, so a module can be "
                        + "changed after reading only that module")
                .allowEmptyShould(true);
    }

    /** Rule 2: the module graph stays acyclic. */
    public static ArchRule noDependencyCycleBetweenModules(String root) {
        return SlicesRuleDefinition.slices()
                .matching(root + ".(*)..")
                .should()
                .beFreeOfCycles()
                .as("ADR-0018 §Enforcement rule 2: no dependency cycle between modules")
                .because("dependencies point downward (ADR-0018 §Dependency direction), so a cycle means a "
                        + "module can no longer be read or changed on its own")
                .allowEmptyShould(true);
    }

    /**
     * Rule 3: a pure module depends on no {@code JdbcTemplate}, no HTTP client and no clock. The
     * pure list is ADR-0036's, and a partly pure module is pinned at its pure subpackage.
     */
    public static ArchRule noPureModuleDependsOnAnEffect(String root) {
        String[] purePackages = BackendModules.PURE_PACKAGES.stream()
                .map(pure -> root + "." + pure + "..")
                .toArray(String[]::new);
        return classes()
                .that()
                .resideInAnyPackage(purePackages)
                .should(new ArchCondition<JavaClass>(
                        "depend on no JdbcTemplate, no HTTP client and no clock") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getFullName();
                            if (isImpureType(target)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription() + " — " + target + " is an effect"));
                            }
                        }
                        for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                            // Instant.now() and friends read the wall clock without naming a Clock type.
                            if (call.getTarget().getName().equals("now")
                                    && call.getTargetOwner().getPackageName().startsWith("java.time")) {
                                events.add(SimpleConditionEvent.violated(
                                        item, call.getDescription() + " — reads the wall clock"));
                            }
                        }
                    }
                })
                .as("ADR-0018 §Enforcement rule 3: no pure module depends on a JdbcTemplate, an HTTP client "
                        + "or a clock (pure list: ADR-0036 §Dependencies and purity)")
                .because("purity is what keeps L1 fast and the platform testable without Docker "
                        + "(ADR-0022 §The ladder); take the effect as an argument instead")
                .allowEmptyShould(true);
    }

    /** Rule 4: {@code orchestration} is the only caller of {@code workflow.api.command}. */
    public static ArchRule noCallerButOrchestrationReferencesWorkflowCommands(String root) {
        String[] allowed = java.util.stream.Stream.concat(
                        BackendModules.SINGLE_WRITER_CALLERS.stream(),
                        // The owning module is not a caller: workflow references its own command package.
                        java.util.stream.Stream.of(BackendModules.SINGLE_WRITER_OWNER))
                .map(module -> root + "." + module + "..")
                .toArray(String[]::new);
        return noClasses()
                .that()
                .resideOutsideOfPackages(allowed)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(root + "." + BackendModules.SINGLE_WRITER_PACKAGE + "..")
                .as("ADR-0018 §Enforcement rule 4: no caller other than orchestration references "
                        + BackendModules.SINGLE_WRITER_PACKAGE)
                .because("the single-threaded command queue is the only writer (ADR-0012), and it stays that "
                        + "way only if every write goes through an orchestration use case")
                .allowEmptyShould(true);
    }

    /** The declared module a package belongs to, or {@code null} when it is outside every module. */
    private static String moduleOf(String root, String packageName) {
        String prefix = root + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        String candidate = dot < 0 ? rest : rest.substring(0, dot);
        return BackendModules.MODULES.contains(candidate) ? candidate : null;
    }

    private static boolean isInApiOf(String root, String module, String packageName) {
        String api = root + "." + module + ".api";
        return packageName.equals(api) || packageName.startsWith(api + ".");
    }

    private static boolean isImpureType(String fullName) {
        return IMPURE_TYPES.stream()
                .anyMatch(impure -> impure.endsWith(".") ? fullName.startsWith(impure) : fullName.equals(impure));
    }
}
