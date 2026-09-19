package com.dbx.connection.api;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.dbx.architecture.BackendModules;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The boundary rules {@code docs/spec/connection.md} §Obligations A gives this module, plus the
 * exactly-six-entry-points check of E20.
 *
 * <p>They live beside the module rather than in {@code com.dbx.architecture} because they are
 * {@code connection}'s obligations, not ADR-0018's fourteen-module rules: obligation 3 forbids an
 * inbound reference that ADR-0018 rule 1 is perfectly happy with, and obligation 4 adds effects
 * ({@code IMPURE_TYPES} has no Kafka client) that the pure-module rule never looks for.
 *
 * <p>Every rule takes its package root, for the reason {@code ModuleBoundaryRules} gives: a leaf
 * module with almost no code passes all of these vacuously, so {@code ConnectionBoundaryTest} points
 * them at the production sources and {@code ConnectionBoundaryRuleFixtureTest} points the same rule
 * objects at {@code com.dbx.archfixture}, where a fixture violates each one on purpose (ADR-0022).
 *
 * <p>Each failure names the obligation and the ADR behind it, so a red rule reads as "fix the code",
 * never "fix the rule" (ADR-0018 §Enforcement).
 */
final class ConnectionBoundaryRules {

    /**
     * The six entry points of {@code docs/spec/connection.md} §Interface, in the sub-spec's own words.
     * Widening this set is a decision about the module's interface, which is why it is a constant a
     * reviewer sees change rather than a count.
     */
    static final Set<String> ENTRY_POINTS =
            Set.of("encrypt", "decrypt", "wrap", "unwrap", "erase", "fingerprint");

    /**
     * What obligations 2 and 4 keep out: H2, a repository of its own, an HTTP client, a Kafka client
     * and Flyway. Prefixes ending in a dot match a package, the rest match one type, and the
     * {@code Repository} suffix is matched on the simple name because a repository is named, not typed.
     *
     * <p>{@code java.sql.Types} and friends are deliberately absent for the reason
     * {@code ModuleBoundaryRules} gives; what is forbidden here is opening a connection, not naming a
     * JDBC constant. Kafka and Flyway cannot be planted in the fixture tree — neither is a dependency
     * of this build (ADR-0012) — so they are guarded, not proven.
     */
    private static final List<String> FORBIDDEN_TYPES = List.of(
            "org.springframework.jdbc.",
            "javax.sql.DataSource",
            "java.sql.Connection",
            "java.sql.DriverManager",
            "org.flywaydb.",
            "java.net.http.",
            "java.net.HttpURLConnection",
            "org.springframework.web.client.",
            "org.springframework.web.reactive.function.client.",
            "org.apache.hc.",
            "org.apache.http.",
            "okhttp3.",
            "org.apache.kafka.");

    /** Writing types obligations 10 and 19 keep out; reading the master key is the one side effect. */
    private static final List<String> WRITING_TYPES =
            List.of("java.io.FileOutputStream", "java.io.FileWriter", "java.io.PrintWriter");

    /** {@code java.nio.file.Files} both reads and writes, so it is filtered by method name. */
    private static final List<String> WRITING_FILES_METHODS = List.of(
            "write",
            "writeString",
            "newOutputStream",
            "newBufferedWriter",
            "createFile",
            "createDirectory",
            "createDirectories",
            "createTempFile",
            "copy",
            "move",
            "delete",
            "deleteIfExists");

    private ConnectionBoundaryRules() {
    }

    /** Obligation 1: the leaf names no other module, so {@code workflow → connection} stays one-way. */
    static ArchRule connectionDependsOnNoOtherModule(String root) {
        // Positive phrasing driven from classes(), so the condition can report the offending dependency:
        // noClasses() inverts a custom condition's events (ModuleBoundaryRules rule 1 has the same shape).
        return classes()
                .that()
                .resideInAPackage(connection(root) + "..")
                .should(new ArchCondition<JavaClass>("name no other DBX module") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getPackageName();
                            String module = moduleOf(root, target);
                            if (module == null || module.equals("connection")) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(
                                    item, dependency.getDescription() + " — names module " + module));
                        }
                    }
                })
                .as("docs/spec/connection.md obligation 1: connection depends on no other DBX module")
                .because("it is the leaf of the graph (ADR-0036 §Dependencies and purity), and that is what "
                        + "makes ADR-0006's erasure true: the module that owns H2 and the append-only ledger "
                        + "can never reach the master key")
                .allowEmptyShould(true);
    }

    /** Obligations 2 and 4: no H2 table, no repository, no Flyway, no HTTP or Kafka client. */
    static ArchRule connectionHoldsNoTableAndOpensNoConnection(String root) {
        return classes()
                .that()
                .resideInAPackage(connection(root) + "..")
                .should(new ArchCondition<JavaClass>(
                        "hold no H2 table or repository and open no database, HTTP or Kafka connection") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            JavaClass target = dependency.getTargetClass();
                            String reason = forbiddenReason(target);
                            if (reason != null) {
                                events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription() + " — " + reason));
                            }
                        }
                    }
                })
                .as("docs/spec/connection.md obligations 2 and 4: connection holds no H2 table, no repository "
                        + "and no Flyway migration, and opens no database, HTTP or Kafka connection")
                .because("credential versions, backups, the tombstone ledger and the key fingerprint are "
                        + "workflow's (ADR-0036 §Modules); connection's only side effect is reading the "
                        + "master-key file")
                .allowEmptyShould(true);
    }

    /** Obligation 3: {@code gateway} and {@code connector} never reference {@code connection} at all. */
    static ArchRule neitherGatewayNorConnectorReferencesConnection(String root) {
        return noClasses()
                .that()
                .resideInAnyPackage(root + ".gateway..", root + ".connector..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(connection(root) + "..")
                .as("docs/spec/connection.md obligation 3: neither gateway nor connector references "
                        + "connection — not even connection.api")
                .because("gateway receives decrypted material from orchestration (ADR-0036 §Dependencies and "
                        + "purity); a data-plane module that could ask for a credential itself would put the "
                        + "master key one call away from the wire")
                .allowEmptyShould(true);
    }

    /** Obligation 5: outside this module only {@code connection.api} exists. */
    static ArchRule onlyTheConnectionApiIsReferencedFromOutside(String root) {
        return noClasses()
                .that()
                .resideOutsideOfPackage(connection(root) + "..")
                .should()
                .dependOnClassesThat(resideInAPackage(connection(root) + "..")
                        .and(resideOutsideOfPackage(connection(root) + ".api..")))
                .as("docs/spec/connection.md obligation 5: only connection.api is referenced from outside the "
                        + "module (ADR-0018 §Enforcement rule 1)")
                .because("the master-key loader and the not-implemented marker are the things no caller may "
                        + "name, so the key-reading decisions of slices 2 and 3 can be changed after reading "
                        + "this module alone")
                .allowEmptyShould(true);
    }

    /** Obligations 10 and 19: reading the master key is the only side effect; nothing is ever written. */
    static ArchRule connectionWritesNoFile(String root) {
        return classes()
                .that()
                .resideInAPackage(connection(root) + "..")
                .should(new ArchCondition<JavaClass>("write no file") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                            String target = dependency.getTargetClass().getFullName();
                            if (WRITING_TYPES.contains(target)) {
                                events.add(SimpleConditionEvent.violated(
                                        item, dependency.getDescription() + " — " + target + " writes"));
                            }
                        }
                        for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                            if (call.getTargetOwner().getFullName().equals("java.nio.file.Files")
                                    && WRITING_FILES_METHODS.contains(call.getTarget().getName())) {
                                events.add(SimpleConditionEvent.violated(
                                        item, call.getDescription() + " — Files." + call.getTarget().getName()
                                                + " writes"));
                            }
                        }
                    }
                })
                .as("docs/spec/connection.md obligations 10 and 19: connection writes no file — not secrets/, "
                        + "not backups/, not the ledger")
                .because("ADR-0035 §Master key gives the key to the release script and forbids upgrade and "
                        + "rollback from writing secrets/; a module that could write it could also re-key an "
                        + "installation by accident")
                .allowEmptyShould(true);
    }

    /**
     * E20, pulled forward from slice 4: {@code connection.api} declares exactly the six entry points of
     * §Interface — no seventh, and none missing.
     *
     * <p>An entry point is a public abstract method of a type in {@code connection.api}: that is what a
     * caller can name and what an implementation must supply. Record accessors and the static factory
     * are concrete, so they do not count, and the closed result unions declare no methods at all.
     *
     * <p>The count is taken across the whole package rather than one interface, so a second entry-point
     * type is caught as well as a seventh method. The condition therefore accumulates across classes and
     * reports in {@link ArchCondition#finish}; a rule instance is used for one check only.
     */
    static ArchRule theApiExposesExactlySixEntryPoints(String root) {
        return classes()
                .that()
                .resideInAPackage(connection(root) + ".api..")
                .should(new ArchCondition<JavaClass>("declare exactly the six entry points of §Interface") {

                    private final Set<String> found = new TreeSet<>();

                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaMethod method : item.getMethods()) {
                            if (method.getModifiers().contains(JavaModifier.ABSTRACT)) {
                                found.add(method.getName());
                            }
                        }
                    }

                    @Override
                    public void finish(ConditionEvents events) {
                        if (found.equals(ENTRY_POINTS)) {
                            return;
                        }
                        Set<String> unexpected = new TreeSet<>(found);
                        unexpected.removeAll(ENTRY_POINTS);
                        Set<String> missing = new TreeSet<>(ENTRY_POINTS);
                        missing.removeAll(found);
                        events.add(SimpleConditionEvent.violated(
                                found,
                                "connection.api declares " + found.size() + " entry points " + found
                                        + " — unexpected: " + unexpected + ", missing: " + missing));
                    }
                })
                .as("docs/spec/connection.md §Verification E20: connection.api exposes exactly six entry "
                        + "points " + new TreeSet<>(ENTRY_POINTS))
                .because("the interface is being filled slice by slice, so a capability that widens it has to "
                        + "arrive as a decision; slice 4 must not re-add this check (#149)")
                .allowEmptyShould(true);
    }

    private static String connection(String root) {
        return root + ".connection";
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

    private static String forbiddenReason(JavaClass target) {
        String fullName = target.getFullName();
        for (String forbidden : FORBIDDEN_TYPES) {
            boolean hit = forbidden.endsWith(".") ? fullName.startsWith(forbidden) : fullName.equals(forbidden);
            if (hit) {
                return fullName + " belongs to workflow's side of the module table, not connection's";
            }
        }
        if (target.getSimpleName().endsWith("Repository")) {
            return fullName + " is a repository, and connection persists nothing";
        }
        return null;
    }
}
