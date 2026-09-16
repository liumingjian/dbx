// The backend is one Gradle module (ADR-0018 §Enforcement). Module boundaries are enforced by
// package rules inside this module, not by Gradle subprojects.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions the pinned Java 21 toolchain when the machine running the build has no JDK 21.
    // Without this, a machine whose newest JDK is 17 cannot build at all.
    // This is the one version that cannot live in gradle/libs.versions.toml: settings plugins are
    // applied before any version catalog exists.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dbx-backend"
