package com.dbx.golden;

import java.nio.file.Path;

/**
 * The L1 entry point of the trailer gate: it resolves the commit range under test, runs
 * {@link GoldenTrailerGate}, and turns the verdict into an exit code.
 *
 * <p>The range comes from {@code -Pgolden.range=<from>..<to>} or the {@code GOLDEN_RANGE}
 * environment variable CI sets. When neither is present — the ordinary local case, where a working
 * copy has no base to compare against — the gate skips and says so. Failing instead would make L1
 * red for every agent who ran it outside CI, and a rung that is red by default teaches agents to
 * ignore it.
 */
public final class GoldenTrailerGateMain {

    private GoldenTrailerGateMain() {
    }

    public static void main(String[] args) {
        Path repository = Path.of(argument(args, "--repo", "."));
        String range = argument(args, "--range", System.getenv("GOLDEN_RANGE"));

        GoldenTrailerGate.Verdict verdict = GoldenTrailerGate.inspect(repository, range);
        String line = "Golden-Update trailer gate (ADR-0022): " + verdict.status() + " — " + verdict.detail();
        if (verdict.status() == GoldenTrailerGate.Status.FAIL) {
            System.err.println(line);
            System.exit(1);
        }
        System.out.println(line);
    }

    private static String argument(String[] args, String name, String fallback) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) {
                return arg.substring(name.length() + 1);
            }
        }
        return fallback;
    }
}
