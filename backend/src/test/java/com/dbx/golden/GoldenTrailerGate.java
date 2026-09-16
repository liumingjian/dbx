package com.dbx.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-0022 §Explicit updates: "L1 goes red on a commit range where a golden file changed without
 * its trailer." This is that check, as a function of (repository, commit range) so it can be
 * watched to fail against synthetic ranges rather than against the repository's own history.
 *
 * <p>The trailer is written exactly as the ADR writes it, an em dash and a reason:
 * {@code Golden-Update: <name> — <why this output is expected to change>}. Surrounding
 * whitespace is tolerated; the separator is not, because a trailer without a stated reason is the
 * convention an agent can quietly break.
 */
public final class GoldenTrailerGate {

    /** {@code Golden-Update: <name> — <reason>}, tolerant of spacing, strict about the em dash. */
    private static final Pattern TRAILER =
            Pattern.compile("^\\s*Golden-Update\\s*:\\s*(\\S+)\\s*—\\s*(\\S.*?)\\s*$");

    /** git's "no such commit" sentinel, which a first push to a branch reports as the range start. */
    private static final Pattern ZERO_SHA = Pattern.compile("0{7,40}");

    /** Commit messages are joined by this, which cannot occur in one. */
    private static final String RECORD_SEPARATOR = "";

    private GoldenTrailerGate() {
    }

    public enum Status {
        /** No golden file changed, or every changed set is covered by a trailer. */
        PASS,
        /** A golden file changed with no trailer covering its set. */
        FAIL,
        /** No usable commit range, so there is nothing to judge. See {@link Verdict#detail()}. */
        SKIP
    }

    public record Verdict(Status status, String detail) {
    }

    public static Verdict inspect(Path repository, String range) {
        if (range == null || range.isBlank()) {
            return new Verdict(Status.SKIP, "no commit range was given");
        }
        String[] endpoints = range.strip().split("\\.\\.\\.?", -1);
        if (endpoints.length != 2 || endpoints[0].isBlank() || endpoints[1].isBlank()) {
            return new Verdict(Status.SKIP, "'" + range + "' is not a <from>..<to> range");
        }
        for (String endpoint : endpoints) {
            if (ZERO_SHA.matcher(endpoint).matches()) {
                return new Verdict(Status.SKIP, "'" + range + "' starts before the branch existed");
            }
            if (!resolves(repository, endpoint)) {
                // Shallow clones and fresh local branches routinely lack one end. Failing here
                // would be noise an agent learns to ignore; CI supplies a real range (§Where it runs).
                return new Verdict(Status.SKIP, "'" + endpoint + "' is not a commit in this checkout");
            }
        }

        Set<String> changedSets = changedGoldenSets(repository, range);
        if (changedSets.isEmpty()) {
            return new Verdict(Status.PASS, "no golden file changed in " + range);
        }
        Set<String> explained = trailerNames(repository, range);
        Set<String> unexplained = new TreeSet<>(changedSets);
        unexplained.removeAll(explained);
        if (unexplained.isEmpty()) {
            return new Verdict(Status.PASS,
                    "golden sets " + changedSets + " changed, each with its trailer");
        }
        return new Verdict(Status.FAIL,
                "golden set(s) " + unexplained + " changed in " + range + " with no matching trailer"
                        + (explained.isEmpty() ? "" : " (the range only explains " + explained + ")")
                        + ". ADR-0022 requires one 'Golden-Update: <name> — <why this output is "
                        + "expected to change>' trailer per changed set, so every regeneration "
                        + "carries a stated reason.");
    }

    /** The registered-or-not set names whose files changed in the range. */
    static Set<String> changedGoldenSets(Path repository, String range) {
        Set<String> sets = new TreeSet<>();
        String prefix = GoldenSets.GOLDEN_ROOT + "/";
        for (String path : git(repository, "diff", "--name-only", range).split("\n")) {
            String trimmed = path.strip();
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            String rest = trimmed.substring(prefix.length());
            int slash = rest.indexOf('/');
            sets.add(slash < 0 ? rest : rest.substring(0, slash));
        }
        return sets;
    }

    /** Set names named by a well-formed trailer on any commit in the range. */
    static Set<String> trailerNames(Path repository, String range) {
        Set<String> names = new LinkedHashSet<>();
        String log = git(repository, "log", "--format=%B" + RECORD_SEPARATOR, range);
        for (String line : log.split("\\R")) {
            Matcher matcher = TRAILER.matcher(line.replace(RECORD_SEPARATOR, ""));
            if (matcher.matches()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static boolean resolves(Path repository, String revision) {
        try {
            return run(repository, List.of("git", "rev-parse", "--verify", "--quiet", revision + "^{commit}"))
                    .exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String git(Path repository, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Result result = run(repository, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", arguments) + " failed: " + result.output());
        }
        return result.output();
    }

    private record Result(int exitCode, String output) {
    }

    private static Result run(Path repository, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
