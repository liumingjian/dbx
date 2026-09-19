package com.dbx.archfixture.connection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@code connection} that writes: obligations 10 and 19's violation.
 *
 * <p>Generating the master key when it is missing is the tempting version of this, and ADR-0035 §Master
 * key forbids exactly it — the release script owns the key, and a module that writes {@code secrets/}
 * would also be the module that quietly re-keys an installation.
 */
public final class WritesTheKeyFile {

    public void generateWhatIsMissing(Path secretsDirectory) throws IOException {
        Files.writeString(secretsDirectory.resolve("master.key"), "not connection's job");
    }
}
