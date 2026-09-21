package com.dbx.connection.api;

import java.nio.file.Path;

/**
 * Obligation 8: the key file exists but is not exactly 256 bits, so it is rejected — never padded,
 * hashed or truncated into shape.
 *
 * <p>Padding a short file would encrypt v1 credentials under a key with less entropy than ADR-0035
 * §Master key promises, and truncating a long one would silently accept a file the release script never
 * wrote. Distinct from {@link MasterKeyUnavailable} because the DBA's action differs: a wrong file is
 * mounted, rather than none.
 *
 * <p>It reports the length it found and nothing else: a length is not material (obligation 11), and it
 * is what tells the DBA which file they mounted.
 */
public final class MasterKeyMalformed extends MasterKeyFailure {

    /** ADR-0035 §Master key: "a random 256-bit key". AES-256-GCM admits no other length. */
    public static final int REQUIRED_BITS = 256;

    private final transient Path masterKeyFile;
    private final int actualBits;

    public MasterKeyMalformed(Path masterKeyFile, int actualBits) {
        super("the master key file " + masterKeyFile + " holds " + actualBits + " bits, but ADR-0035 §Master key "
                + "fixes it at " + REQUIRED_BITS + ": docs/spec/connection.md obligation 8 rejects it rather than "
                + "padding or truncating it. Mount the key the release script generated.");
        this.masterKeyFile = masterKeyFile;
        this.actualBits = actualBits;
    }

    public Path masterKeyFile() {
        return masterKeyFile;
    }

    public int actualBits() {
        return actualBits;
    }
}
