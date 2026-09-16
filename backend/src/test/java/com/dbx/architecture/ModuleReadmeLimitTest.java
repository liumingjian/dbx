package com.dbx.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0018 §Module context: a module README is navigation, at most 40 lines, and a test enforces
 * the limit. It runs over the real files, so it fails on the repository rather than on a fixture.
 */
class ModuleReadmeLimitTest {

    private static final int MAX_LINES = 40;

    /** Gradle runs tests with the project directory as the working directory. */
    private static final Path MODULE_ROOT = Path.of("src", "main", "java", "com", "dbx");

    @Test
    void everyModuleCarriesAReadmeWithinTheLimit() throws IOException {
        for (String module : BackendModules.MODULES) {
            Path readme = MODULE_ROOT.resolve(module).resolve("README.md");

            assertTrue(
                    Files.isRegularFile(readme),
                    "ADR-0018 §Module context: module " + module + " needs a README at " + readme.toAbsolutePath());

            List<String> lines = Files.readAllLines(readme);
            assertTrue(
                    lines.size() <= MAX_LINES,
                    "ADR-0018 §Module context caps a module README at " + MAX_LINES + " lines; " + readme
                            + " has " + lines.size()
                            + ". A README is navigation: link to the sub-spec, the ADRs and CONTEXT.md "
                            + "instead of restating them.");
        }
    }

    /** {@code release} is a sub-spec, not a package (ADR-0036), so it owns no module directory. */
    @Test
    void releaseHasNoModuleDirectory() {
        assertTrue(
                Files.notExists(MODULE_ROOT.resolve("release")),
                "ADR-0036: release has no Java package and no ArchUnit rule");
    }
}
