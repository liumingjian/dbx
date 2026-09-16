package com.dbx.golden;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The {@code harness} self-test set: it proves the reader reads, that a mismatch fails, and that no
 * property makes a test repair the file it just failed against (ADR-0022 §Explicit updates).
 */
class GoldenHarnessTest {

    /** The recorded set as it sits in the source tree; the test runs with the project as its cwd. */
    private static final Path RECORDED =
            Path.of("src/test/resources/golden", HarnessGoldenSource.SET, HarnessGoldenSource.FILE);

    @Test
    void readsTheRecordedOutputOfTheHarnessSet() {
        GoldenFiles.assertMatches(
                HarnessGoldenSource.SET,
                HarnessGoldenSource.FILE,
                new HarnessGoldenSource().render().get(HarnessGoldenSource.FILE));
    }

    @Test
    void aDifferentOutputFails() {
        AssertionError failure = assertThrows(AssertionError.class, () ->
                GoldenFiles.assertMatches(HarnessGoldenSource.SET, HarnessGoldenSource.FILE, "alpha -> 9\n"));
        assertTrue(failure.getMessage().contains("goldenUpdate -Pgolden.update=harness"),
                "the mismatch must name the one command allowed to change the file");
    }

    @Test
    void aMissingGoldenFileFailsRatherThanBeingRecorded() {
        assertThrows(AssertionError.class,
                () -> GoldenFiles.read(HarnessGoldenSource.SET, "not-recorded.txt"));
        assertTrue(Files.notExists(RECORDED.resolveSibling("not-recorded.txt")),
                "reading must never create a golden file");
    }

    @Test
    void noPropertyTurnsAFailingAssertionIntoARewrite() throws IOException {
        byte[] before = Files.readAllBytes(RECORDED);
        System.setProperty("golden.update", HarnessGoldenSource.SET);
        try {
            assertThrows(AssertionError.class, () -> GoldenFiles.assertMatches(
                    HarnessGoldenSource.SET, HarnessGoldenSource.FILE, "rewritten\n"));
        } finally {
            System.clearProperty("golden.update");
        }
        assertArrayEquals(before, Files.readAllBytes(RECORDED),
                "tests read golden files and never write them, under any flag or property");
    }

    @Test
    void theRegistryAddressesSetsByName() {
        assertEquals(java.util.Set.of(HarnessGoldenSource.SET), GoldenSets.names());
        assertEquals(HarnessGoldenSource.SET, GoldenSets.require(HarnessGoldenSource.SET).name());
    }
}
