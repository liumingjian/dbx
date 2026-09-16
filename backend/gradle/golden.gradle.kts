// --- ADR-0022 §Explicit updates: the golden-file harness ---------------------------------------
//
// Golden files pin the outputs where a wrong answer looks like success, so the dangerous act is not
// a failing assertion but a silent regeneration. Two things make regeneration visible:
//
//   1. the only writer lives in its own source set, off the test classpath, so a test cannot repair
//      the file it just failed against under any flag or property;
//   2. `check` reads the commit range under test and goes red when a golden file changed without a
//      `Golden-Update: <name> — <reason>` trailer naming its set.
//
// The set names themselves are a closed registry in `com.dbx.golden.GoldenSets`; a later ticket adds
// one line there and puts its files under `src/test/resources/golden/<name>/`.

import org.gradle.api.tasks.SourceSetContainer

val sourceSets = extensions.getByType(SourceSetContainer::class.java)
val testSources = sourceSets.getByName("test")
val goldenRoot = layout.projectDirectory.dir("src/test/resources/golden")

// The writer compiles against the test sources (it needs the registry and its sources) but is never
// on the test classpath, which is what makes "tests never write golden files" a fact about the
// build rather than a rule an agent has to remember.
val goldenUpdateSources = sourceSets.create("goldenUpdate")
goldenUpdateSources.compileClasspath += testSources.output + testSources.compileClasspath
goldenUpdateSources.runtimeClasspath += testSources.output + testSources.runtimeClasspath

tasks.register<JavaExec>("goldenUpdate") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Rewrites exactly one named golden set: -Pgolden.update=<name> (ADR-0022)."
    mainClass.set("com.dbx.golden.update.GoldenUpdateMain")
    classpath = goldenUpdateSources.runtimeClasspath
    // The name is passed through untouched, including when it is missing: every rejection — absent,
    // `all`, a wildcard, an unregistered name — is decided in one place, `GoldenUpdateRequest`,
    // which L1 watches fire.
    argumentProviders.add {
        listOf(
            "--set=" + (providers.gradleProperty("golden.update").orNull ?: ""),
            "--root=" + goldenRoot.asFile.absolutePath,
        )
    }
    outputs.upToDateWhen { false }
}

val goldenTrailerGate = tasks.register<JavaExec>("goldenTrailerGate") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when a golden file changed in the commit range without its Golden-Update trailer (ADR-0022)."
    mainClass.set("com.dbx.golden.GoldenTrailerGateMain")
    classpath = testSources.runtimeClasspath
    // The gate runs from the repository root: golden paths and commits are the repository's, not the
    // backend subproject's. The range comes from -Pgolden.range or the GOLDEN_RANGE variable CI sets;
    // with neither, GoldenTrailerGateMain falls back to the branch's own commits so the gate bites at
    // L1 too, and skips with a reason when even that cannot be resolved.
    argumentProviders.add {
        val range = providers.gradleProperty("golden.range").orNull
        listOfNotNull(
            "--repo=" + rootDir.parentFile.absolutePath,
            range?.let { "--range=$it" },
        )
    }
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(goldenTrailerGate)
    // The writer is part of the harness, so L1 compiles it; a harness whose updater stopped
    // compiling would only be discovered by the agent who needed to regenerate a set.
    dependsOn(goldenUpdateSources.classesTaskName)
}
