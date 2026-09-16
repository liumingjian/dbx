package com.dbx.golden.update;

import com.dbx.golden.GoldenSets;
import com.dbx.golden.GoldenSource;
import com.dbx.golden.GoldenUpdateRejected;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The only writer of golden files, behind {@code ./gradlew goldenUpdate -Pgolden.update=<name>}.
 *
 * <p>It lives in its own source set rather than beside the tests so that "tests read golden files
 * and never write them" (ADR-0022) is a fact about the test classpath instead of a rule an agent
 * has to keep. It rewrites exactly the named set's directory and touches nothing else.
 */
public final class GoldenUpdateMain {

    private GoldenUpdateMain() {
    }

    public static void main(String[] args) throws IOException {
        String rawName = argument(args, "--set");
        Path root = Path.of(argument(args, "--root"));

        GoldenSource source;
        try {
            source = GoldenSets.require(rawName);
        } catch (GoldenUpdateRejected rejected) {
            System.err.println(rejected.getMessage());
            System.exit(1);
            return;
        }

        Path setDirectory = root.resolve(source.name());
        Map<String, String> rendered = source.render();
        Files.createDirectories(setDirectory);

        // Stale files are removed so a set that loses a case does not keep asserting the old one.
        // The sweep is confined to this one directory: no flag here can reach a second set.
        List<Path> existing;
        try (Stream<Path> walk = Files.list(setDirectory)) {
            existing = walk.filter(Files::isRegularFile).toList();
        }
        for (Path stale : existing) {
            if (!rendered.containsKey(stale.getFileName().toString())) {
                Files.delete(stale);
                System.out.println("removed " + stale);
            }
        }
        for (Map.Entry<String, String> file : rendered.entrySet()) {
            Path target = setDirectory.resolve(file.getKey());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            System.out.println("wrote " + target);
        }
        System.out.println("Rewrote golden set '" + source.name() + "'. Commit it with "
                + "'Golden-Update: " + source.name() + " — <why this output is expected to change>'.");
    }

    private static String argument(String[] args, String name) {
        for (String arg : args) {
            if (arg.startsWith(name + "=")) {
                return arg.substring(name.length() + 1);
            }
        }
        return "";
    }
}
