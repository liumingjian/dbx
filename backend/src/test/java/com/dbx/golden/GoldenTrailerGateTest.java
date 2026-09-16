package com.dbx.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The trailer gate, watched against synthetic commit ranges.
 *
 * <p>Each case builds a throwaway repository in a temp directory. Asserting against this
 * repository's own history would make the gate's tests depend on what the last agent happened to
 * commit — and would leave the failing cases untestable, since a red commit cannot be committed.
 */
class GoldenTrailerGateTest {

    private static final String GOLDEN_FILE =
            GoldenSets.GOLDEN_ROOT + "/" + HarnessGoldenSource.SET + "/" + HarnessGoldenSource.FILE;

    @Test
    void aGoldenChangeWithItsTrailerPasses(@TempDir Path repository) throws IOException {
        String base = synthesise(repository, "recorded\n");
        String head = changeGolden(repository, "regenerated\n",
                "Regenerate the harness set\n\nGolden-Update: harness — the echo rendering gained a column.\n");

        GoldenTrailerGate.Verdict verdict = GoldenTrailerGate.inspect(repository, base + ".." + head);

        assertEquals(GoldenTrailerGate.Status.PASS, verdict.status(), verdict.detail());
    }

    @Test
    void aGoldenChangeWithNoTrailerFails(@TempDir Path repository) throws IOException {
        String base = synthesise(repository, "recorded\n");
        String head = changeGolden(repository, "regenerated\n", "Regenerate the harness set\n");

        GoldenTrailerGate.Verdict verdict = GoldenTrailerGate.inspect(repository, base + ".." + head);

        assertEquals(GoldenTrailerGate.Status.FAIL, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("harness"), verdict.detail());
    }

    @Test
    void aTrailerNamingADifferentSetFails(@TempDir Path repository) throws IOException {
        String base = synthesise(repository, "recorded\n");
        String head = changeGolden(repository, "regenerated\n",
                "Regenerate the harness set\n\nGolden-Update: box-plans — tie-breaking changed.\n");

        GoldenTrailerGate.Verdict verdict = GoldenTrailerGate.inspect(repository, base + ".." + head);

        assertEquals(GoldenTrailerGate.Status.FAIL, verdict.status(), verdict.detail());
        assertTrue(verdict.detail().contains("box-plans"), verdict.detail());
    }

    @Test
    void aRangeWithNoGoldenChangePasses(@TempDir Path repository) throws IOException {
        String base = synthesise(repository, "recorded\n");
        write(repository, "README.md", "unrelated\n");
        String head = commit(repository, "Touch a file that pins nothing\n");

        assertEquals(GoldenTrailerGate.Status.PASS,
                GoldenTrailerGate.inspect(repository, base + ".." + head).status());
    }

    @Test
    void aTrailerWithoutAReasonDoesNotCount(@TempDir Path repository) throws IOException {
        String base = synthesise(repository, "recorded\n");
        String head = changeGolden(repository, "regenerated\n",
                "Regenerate the harness set\n\nGolden-Update: harness\n");

        assertEquals(GoldenTrailerGate.Status.FAIL,
                GoldenTrailerGate.inspect(repository, base + ".." + head).status());
    }

    @Test
    void anUnusableRangeSkipsInsteadOfGoingRed(@TempDir Path repository) throws IOException {
        synthesise(repository, "recorded\n");

        assertEquals(GoldenTrailerGate.Status.SKIP, GoldenTrailerGate.inspect(repository, null).status());
        assertEquals(GoldenTrailerGate.Status.SKIP, GoldenTrailerGate.inspect(repository, "   ").status());
        assertEquals(GoldenTrailerGate.Status.SKIP, GoldenTrailerGate.inspect(repository, "HEAD").status());
        assertEquals(GoldenTrailerGate.Status.SKIP,
                GoldenTrailerGate.inspect(repository, "0000000000000000000000000000000000000000..HEAD").status());
        assertEquals(GoldenTrailerGate.Status.SKIP,
                GoldenTrailerGate.inspect(repository, "no-such-ref..HEAD").status());
    }

    /** A repository holding one recorded golden file. Returns the base commit. */
    private static String synthesise(Path repository, String recorded) throws IOException {
        git(repository, "init", "--quiet", "--initial-branch=main");
        write(repository, GOLDEN_FILE, recorded);
        return commit(repository, "Record the harness set\n\nGolden-Update: harness — first recording.\n");
    }

    private static String changeGolden(Path repository, String content, String message) throws IOException {
        write(repository, GOLDEN_FILE, content);
        return commit(repository, message);
    }

    private static void write(Path repository, String relativePath, String content) throws IOException {
        Path file = repository.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String commit(Path repository, String message) {
        git(repository, "add", "--all");
        git(repository, "commit", "--quiet", "--no-gpg-sign", "--message=" + message);
        return git(repository, "rev-parse", "HEAD").strip();
    }

    /** Identity and signing are forced on the command line: the agent's own git config must not leak in. */
    private static String git(Path repository, String... arguments) {
        List<String> command = new ArrayList<>(List.of(
                "git",
                "-c", "user.name=dbx test",
                "-c", "user.email=test@dbx.invalid",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repository.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
