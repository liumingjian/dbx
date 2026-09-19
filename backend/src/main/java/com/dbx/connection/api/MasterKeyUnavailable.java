package com.dbx.connection.api;

import java.nio.file.Path;

/**
 * Obligation 9: the mounted {@code secrets/master.key} is not there, so every entry point fails with
 * this — and {@code connection} does not generate one. Generating the key is the release script's job
 * (ADR-0035 §Master key, "First install"), and a module that quietly made its own would encrypt the
 * next credential version under a key no backup and no other node knows.
 */
public final class MasterKeyUnavailable extends MasterKeyFailure {

    private final transient Path masterKeyFile;

    public MasterKeyUnavailable(Path masterKeyFile) {
        super("the master key file " + masterKeyFile + " is not readable, so connection can do nothing: "
                + "docs/spec/connection.md obligation 9 forbids generating one, and ADR-0035 §Master key makes "
                + "the release script mount it. Restore secrets/ from the copy kept off this machine.");
        this.masterKeyFile = masterKeyFile;
    }

    /** The path that was looked at — not secret, and the one thing a DBA needs to act. */
    public Path masterKeyFile() {
        return masterKeyFile;
    }
}
