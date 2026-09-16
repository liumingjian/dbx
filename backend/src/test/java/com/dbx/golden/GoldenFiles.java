package com.dbx.golden;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The reader tests use. It reads golden files and never writes them (ADR-0022 §Explicit updates).
 *
 * <p>There is no flag, property or overload here that writes, and there cannot be one by accident:
 * the writer lives in the separate {@code goldenUpdate} source set, which is not on the test
 * classpath. A failing assertion therefore has no way to repair itself.
 */
public final class GoldenFiles {

    private GoldenFiles() {
    }

    /** The recorded content of one file of a golden set, read from the classpath. */
    public static String read(String set, String file) {
        String resource = "/golden/" + set + "/" + file;
        try (InputStream stream = GoldenFiles.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError(
                        "Missing golden file " + resource + ". Record it with "
                                + updateCommand(set) + " and commit it with the "
                                + "Golden-Update trailer ADR-0022 requires.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Fails when {@code actual} differs from the recorded output. The message names the one command
     * that may change the file, because the alternative an agent reaches for — editing the golden
     * by hand until the test passes — is the quiet failure ADR-0022 was written against.
     */
    public static void assertMatches(String set, String file, String actual) {
        String expected = read(set, file);
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "Golden mismatch in " + set + "/" + file + ".\n--- recorded ---\n" + expected
                            + "--- produced ---\n" + actual
                            + "----------------\nIf the new output is correct, run "
                            + updateCommand(set) + " and commit with "
                            + "'Golden-Update: " + set + " — <why this output is expected to change>'.");
        }
    }

    private static String updateCommand(String set) {
        return "./gradlew goldenUpdate -Pgolden.update=" + set;
    }
}
