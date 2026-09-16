package com.dbx.golden;

import java.nio.file.Path;

/**
 * The L1 entry point of the trailer gate: it resolves the commit range under test, runs
 * {@link GoldenTrailerGate}, and turns the verdict into an exit code.
 *
 * <p>The range comes from {@code -Pgolden.range=<from>..<to>} or the {@code GOLDEN_RANGE}
 * environment variable CI sets. With neither, it falls back to {@link #DEFAULT_RANGE}, so an agent
 * running L1 on a branch gets the same verdict CI will give it rather than a skip: #105 asks that a
 * golden change with no trailer fails <em>at L1</em>, and a gate that only bites in CI is found by
 * the pull request instead of by the session that wrote the change.
 *
 * <p>The fallback is a guess about where the branch started, so it is only ever a convenience: when
 * it cannot resolve — a shallow checkout, no {@code origin}, a repository that does not use this
 * layout — {@link GoldenTrailerGate} skips with the reason rather than failing. A rung that is red
 * by default teaches agents to ignore it.
 */
public final class GoldenTrailerGateMain {

    /**
     * Three-dot, so the range is the branch's own commits measured from where it left the trunk. A
     * two-dot range would also list everything that landed on {@code main} since, and blame this
     * branch for another branch's golden change.
     */
    static final String DEFAULT_RANGE = "origin/main...HEAD";

    private GoldenTrailerGateMain() {
    }

    public static void main(String[] args) {
        Path repository = Path.of(argument(args, "--repo", "."));
        String range = argument(args, "--range", System.getenv("GOLDEN_RANGE"));
        if (range == null || range.isBlank()) {
            range = DEFAULT_RANGE;
        }

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
