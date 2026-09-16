import java.time.Duration
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin

// --- ADR-0022: the upper three rungs of the verification ladder --------------------------------
//
// L1 (`check`) lives in build.gradle.kts and is Docker-free. This file adds L2 `seamTest`,
// L3 `e2eTest` and L4 `packageTest` (the last one added to the ladder by ADR-0035) as wired but
// empty rungs, so the first test that needs Docker has a rung to be placed on rather than a
// decision to make. The task names are ADR-0022's verbatim, because a session instruction naming
// a rung has to resolve to a real task.
//
// Their content is owned elsewhere: `packageTest` by the release sub-spec (docs/spec/release.md,
// ADR-0035 §Verification — build, offline fresh install, upgrade, rollback), `e2eTest` by the
// modules that reach L3 against the pinned #9 test bed, and `seamTest` by each module that grows
// a Testcontainers seam test. Nothing here asserts anything; adding a test is the whole change.

// Measured from the configuration phase, exactly like the `check` line in build.gradle.kts, so all
// four rungs report their duration the same way. gradle.properties keeps the configuration cache
// off for this reason.
val rungStartedAtMillis = System.currentTimeMillis()

val sourceSets = extensions.getByType<SourceSetContainer>()
// A script applied with `apply(from = ...)` gets no type-safe `libs` accessor, so the catalog is
// resolved by hand rather than by moving these dependencies into build.gradle.kts.
val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Registers one upper rung: its own source set, its own `Test` task, and a duration line.
 *
 * Every rung is deliberately allowed to run green with no tests at all, which takes two settings
 * because there are two empty cases. A source set with no sources at all leaves the `Test` task with
 * no candidate classes and Gradle skips it as NO-SOURCE — already a pass. Once the source set holds
 * classes but the run discovers no test (an empty rung filtered down to one module, say), Gradle 9
 * fails the task by default; that is the right default for a rung that is supposed to have content
 * and the wrong one for a rung whose content is owned by a later ticket, because an agent typing
 * `./gradlew e2eTest` must get a pass rather than a build error that looks like a broken build.
 * `failOnNoDiscoveredTests` is off for that reason, and the ticket landing a rung's first test may
 * turn it back on.
 */
fun registerRung(
    rungName: String,
    level: String,
    budgetNote: String,
    requiresDocker: Boolean,
    ownedBy: String,
): TaskProvider<Test> {
    val sourceSet = sourceSets.create(rungName)

    // The rung inherits the L1 test dependencies (JUnit 5, and later ArchUnit) so a seam test is
    // written the same way a unit test is; what it adds on top stays on its own configuration and
    // never reaches the `test` classpath.
    configurations.named("${rungName}Implementation") {
        extendsFrom(configurations.getByName("testImplementation"))
    }
    configurations.named("${rungName}RuntimeOnly") {
        extendsFrom(configurations.getByName("testRuntimeOnly"))
    }
    val mainOutput = sourceSets.getByName("main").output
    sourceSet.compileClasspath += mainOutput
    sourceSet.runtimeClasspath += mainOutput

    // The duration line is a separate finalizer rather than a `doLast` on the rung itself, which is
    // where `check` puts it. A `Test` task whose source set is empty is skipped as NO-SOURCE, and a
    // skipped task runs neither its actions nor its `doLast`; an agent would then invoke an upper
    // rung today and see no line at all. A finalizer runs whether the rung ran, was skipped, or
    // failed — and a failed rung's duration is worth reading too.
    val durationReport = tasks.register("${rungName}Duration") {
        description = "Prints how long $rungName took (ADR-0022 budget: $budgetNote, reported only)."
        doLast {
            val elapsed = Duration.ofMillis(System.currentTimeMillis() - rungStartedAtMillis)
            val rendered = "%dm %02ds".format(elapsed.toMinutes(), elapsed.toSecondsPart())
            logger.lifecycle(
                "$level ($rungName) duration: $rendered — ADR-0022 budgets $budgetNote; " +
                    "reported, not enforced."
            )
        }
    }

    return tasks.register<Test>(rungName) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description =
            "$level rung (ADR-0022). Budget: $budgetNote. " +
                (if (requiresDocker) "Needs a Docker daemon. " else "") +
                "Content owned by $ownedBy. Not part of `check`."

        // Declared, not inferred: `./gradlew tasks` and any agent reading this task's properties
        // can see which rungs need a Docker daemon before invoking one.
        extensions.extraProperties.set("requiresDocker", requiresDocker)

        testClassesDirs = sourceSet.output.classesDirs
        classpath = sourceSet.runtimeClasspath
        useJUnitPlatform()

        // See the KDoc above: an empty rung is a pass, not an error.
        failOnNoDiscoveredTests = false
        // Same reasoning for the per-module filter below: filtering a rung down to a module that
        // has no seam test yet is a legitimate no-op, not a failure.
        filter.isFailOnNoMatchingTests = false

        // The rungs are ordered by cost, so a `./gradlew check seamTest` reports the cheap failure
        // first. This is ordering only — it never makes one rung depend on another.
        shouldRunAfter(tasks.named("test"))

        if (requiresDocker) {
            doFirst {
                // Only bites once the rung has content. Probing for Docker on an empty rung would
                // make `seamTest` red on a machine where nothing was going to run anyway.
                if (!sourceSet.output.classesDirs.asFileTree.isEmpty && !dockerDaemonIsReachable()) {
                    throw GradleException(
                        "$level ($rungName) needs a Docker daemon (ADR-0022 §The ladder) and " +
                            "`docker info` did not answer. Every rung runs on the mac through the " +
                            "`rexec` skill; start Docker there rather than here."
                    )
                }
            }
        }

        finalizedBy(durationReport)
    }
}

/** `docker info` rather than the socket path, because that is the thing Testcontainers itself needs. */
fun dockerDaemonIsReachable(): Boolean =
    try {
        val process = ProcessBuilder("docker", "info")
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    } catch (ignored: Exception) {
        false
    }

val seamTest = registerRung(
    rungName = "seamTest",
    level = "L2",
    budgetNote = "10m",
    requiresDocker = true,
    ownedBy = "each module that grows a Testcontainers seam test",
)

registerRung(
    rungName = "e2eTest",
    level = "L3",
    budgetNote = "no limit",
    requiresDocker = true,
    ownedBy = "the modules that reach L3, against the pinned test bed of #9",
)

registerRung(
    rungName = "packageTest",
    level = "L4",
    budgetNote = "no limit",
    requiresDocker = true,
    ownedBy = "the release sub-spec (ADR-0035 §Verification)",
)

// --- L2's Docker-bound dependencies ------------------------------------------------------------
//
// Testcontainers reaches `seamTest` only. On the L1 classpath it would let a unit test start a
// container and quietly push `check` past its two-minute budget and out of "no Docker".
// Versions come from the Spring Boot BOM, which is itself pinned in gradle/libs.versions.toml; the
// engines are the three ADR-0022 names for L2 (MySQL 8, PostgreSQL 15, Kafka).
dependencies {
    add("seamTestImplementation", versionCatalog.findLibrary("testcontainers-junit-jupiter").get())
    add("seamTestImplementation", versionCatalog.findLibrary("testcontainers-mysql").get())
    add("seamTestImplementation", versionCatalog.findLibrary("testcontainers-postgresql").get())
    add("seamTestImplementation", versionCatalog.findLibrary("testcontainers-kafka").get())
}

// --- L2 is filterable per module (ADR-0022 §The ladder) ----------------------------------------
//
// ADR-0018 §Session rule: one session changes one module. Making a `gateway` session pay for every
// seam test in the repository is how an agent learns to skip the rung, so L2 takes a module list:
//
//     ./gradlew seamTest -Pseam.module=gateway
//     ./gradlew seamTest -Pseam.module=gateway,workflow
//
// Omit the property to run all of L2. The value is matched against the module's Java package under
// `com.dbx`, so it stays the same word the module is called everywhere else. No list of valid
// module names is repeated here: the package layout is the list, and a name with no seam test is a
// no-op rather than an error, because a module legitimately has none until it grows one.
val seamModuleProperty = "seam.module"
seamTest.configure {
    // Stated on the task so `./gradlew tasks` answers "how do I run only my module's L2?".
    description = description.orEmpty() +
        " Filter to one module with -P$seamModuleProperty=<module>[,<module>]."
    if (project.hasProperty(seamModuleProperty)) {
        val requested = project.property(seamModuleProperty).toString()
            .split(",")
            .map { it.trim() }
        val moduleName = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*")
        // `all` is rejected rather than treated as "everything": it would silently filter down to a
        // package named `all` and report a green rung that ran nothing.
        val malformed = requested.filter { !it.matches(moduleName) || it == "all" }
        if (malformed.isNotEmpty()) {
            throw GradleException(
                "-P$seamModuleProperty takes a comma-separated list of ADR-0036 module packages, " +
                    "for example `-P$seamModuleProperty=gateway,workflow`, but got: " +
                    "${malformed.joinToString(", ") { "'$it'" }}. " +
                    "Wildcards and `all` are not accepted — omit the property to run all of L2."
            )
        }
        requested.forEach { module -> filter.includeTestsMatching("com.dbx.$module.*") }
    }
}
