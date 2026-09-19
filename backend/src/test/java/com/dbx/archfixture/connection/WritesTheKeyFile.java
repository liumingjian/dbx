package com.dbx.archfixture.connection;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A {@code connection} that writes: obligations 10 and 19's violation.
 *
 * <p>Generating the master key when it is missing is the tempting version of this, and ADR-0035 §Master
 * key forbids exactly it — the release script owns the key, and a module that writes {@code secrets/}
 * would also be the module that quietly re-keys an installation.
 *
 * <p>Four ways of writing the same file, because the rule is a list of types and verbs and each way is
 * one entry on it: {@code Files.writeString}, a {@code RandomAccessFile} opened {@code "rw"}, a
 * {@code FileChannel} from {@code Files.newByteChannel}, and {@code java.io.File.delete}. The last three
 * name no writer class and — except for the channel — no {@code Files} method, so a rule listing only
 * {@code FileOutputStream}, {@code FileWriter} and {@code PrintWriter} passed over them while reading as
 * "connection writes nothing" (#148 review).
 */
public final class WritesTheKeyFile {

    public void generateWhatIsMissing(Path secretsDirectory) throws IOException {
        Files.writeString(secretsDirectory.resolve("master.key"), "not connection's job");
    }

    public void rewriteInPlace(Path secretsDirectory) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(secretsDirectory.resolve("master.key").toFile(), "rw")) {
            file.write(new byte[32]);
        }
    }

    public void rewriteThroughAChannel(Path secretsDirectory) throws IOException {
        try (FileChannel channel = (FileChannel) Files.newByteChannel(
                secretsDirectory.resolve("master.key"), StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.allocate(32));
        }
    }

    public void removeTheKeyAltogether(Path secretsDirectory) {
        File key = secretsDirectory.resolve("master.key").toFile();
        key.delete();
    }
}
