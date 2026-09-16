
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.dbx"
version = "0.1.0-SNAPSHOT"

// `./gradlew` with no argument runs L1, so the cheapest thing to type is the rung ADR-0022 asks for.
defaultTasks("check")

java {
    toolchain {
        // Pinned rather than inherited: the build must not follow whatever JDK the running machine
        // happens to have. settings.gradle.kts adds the resolver that downloads this toolchain.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// --- ADR-0012: no JPA, no Hibernate, asserted against the resolved graph -----------------------
//
// ADR-0012 rejects JPA and Hibernate because implicit flush, cascading and lazy reads hide the
// commit boundaries recovery correctness depends on. A rule an agent remembers is not enough: a
// transitive starter could pull Hibernate in without anybody choosing it, so the rung resolves the
// classpaths and reads the artifact names back.

abstract class ForbiddenPersistenceDependencies : DefaultTask() {

    @get:InputFiles
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val forbiddenFragments: ListProperty<String>

    @TaskAction
    fun verify() {
        val fragments = forbiddenFragments.get()
        val offenders = classpath.files
            .map { it.name }
            .filter { artifact -> fragments.any { artifact.contains(it, ignoreCase = true) } }
            .distinct()
            .sorted()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0012 forbids JPA and Hibernate on the backend classpath, " +
                    "but the resolved graph contains: ${offenders.joinToString(", ")}. " +
                    "Persist through Spring JDBC instead, and drop whatever starter pulled these in."
            )
        }
    }
}

val forbiddenPersistenceDependencies =
    tasks.register<ForbiddenPersistenceDependencies>("forbiddenPersistenceDependencies") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Fails when JPA or Hibernate reaches a resolved classpath (ADR-0012)."
        classpath.from(configurations.named("runtimeClasspath"))
        classpath.from(configurations.named("testRuntimeClasspath"))
        forbiddenFragments.set(
            listOf(
                "hibernate",
                "jakarta.persistence",
                "javax.persistence",
                "spring-data-jpa",
                "spring-boot-starter-data-jpa",
            )
        )
    }

tasks.named("check") {
    dependsOn(forbiddenPersistenceDependencies)
}

// L1's own duration line is registered by gradle/rungs.gradle.kts along with the other three rungs,
// so all four report the same way from one place.

// The document budget (#107) hangs off L1; see gradle/docbudget.gradle.kts.
apply(from = "gradle/docbudget.gradle.kts")

// L2, L3 and L4 of ADR-0022's ladder. Kept in their own script so that the tickets landing the
// other L1 gates do not all edit the same file.
apply(from = "gradle/rungs.gradle.kts")

// ADR-0022 §Explicit updates. Kept in its own script so one initiative at a time touches this file.
apply(from = "gradle/golden.gradle.kts")
