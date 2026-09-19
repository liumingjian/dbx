package com.dbx.connection.masterkey;

import com.dbx.connection.NotImplementedInSlice;
import com.dbx.connection.api.BackupId;
import com.dbx.connection.api.Ciphertext;
import com.dbx.connection.api.ConnectionCrypto;
import com.dbx.connection.api.Decryption;
import com.dbx.connection.api.ErasureInstruction;
import com.dbx.connection.api.IssuedBackupKey;
import com.dbx.connection.api.KeyFingerprint;
import com.dbx.connection.api.SecretMaterial;
import com.dbx.connection.api.Unwrapping;
import com.dbx.connection.api.WrappedKey;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The one {@link ConnectionCrypto}: AES-256-GCM under the master key mounted at
 * {@code <secrets>/master.key} (obligation 7; ADR-0035 §Master key).
 *
 * <p>In slice 1 it is the shape and nothing else. Every entry point throws
 * {@link NotImplementedInSlice} naming its capability and the slice of
 * {@code docs/spec/connection.md} §Slices that fills it in — slice 2 for the key file, {@code encrypt},
 * {@code decrypt} and {@code fingerprint}, slice 3 for the per-backup keys. No byte is encrypted, no
 * file is read and nothing is written, so the module cannot be mistaken for working.
 *
 * <p>The directory is held, not read: construction performs no I/O, so a caller never holds a module
 * that decided the key was fine before the key was needed.
 */
public final class MasterKeyCrypto implements ConnectionCrypto {

    private final Path secretsDirectory;

    public MasterKeyCrypto(Path secretsDirectory) {
        this.secretsDirectory = Objects.requireNonNull(secretsDirectory, "secretsDirectory");
    }

    /** Where the key will be read from, for the failure messages of slice 2 (obligations 8, 9). */
    public Path masterKeyFile() {
        return secretsDirectory.resolve("master.key");
    }

    @Override
    public Ciphertext encrypt(SecretMaterial material) {
        throw new NotImplementedInSlice("encrypt", 2);
    }

    @Override
    public Decryption decrypt(Ciphertext ciphertext) {
        throw new NotImplementedInSlice("decrypt", 2);
    }

    @Override
    public IssuedBackupKey wrap(BackupId backup) {
        throw new NotImplementedInSlice("wrap", 3);
    }

    @Override
    public Unwrapping unwrap(WrappedKey wrapped) {
        throw new NotImplementedInSlice("unwrap", 3);
    }

    @Override
    public ErasureInstruction erase(WrappedKey wrapped) {
        throw new NotImplementedInSlice("erase", 3);
    }

    @Override
    public KeyFingerprint fingerprint() {
        throw new NotImplementedInSlice("fingerprint", 2);
    }
}
