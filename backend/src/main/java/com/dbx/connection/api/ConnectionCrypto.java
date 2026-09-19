package com.dbx.connection.api;

import com.dbx.connection.masterkey.MasterKeyCrypto;
import java.nio.file.Path;

/**
 * The six entry points of {@code docs/spec/connection.md} §Interface, and the whole surface other
 * modules may name (ADR-0018 §Enforcement).
 *
 * <p>Six is the count, not a starting point: an ArchUnit check in {@code ConnectionBoundaryTest}
 * fails when {@code connection.api} declares a seventh, because the interface is being filled slice
 * by slice and a widening that slips in unnoticed cannot be reviewed as a decision. TLS material has
 * no entry point of its own — it travels through {@link #encrypt} and {@link #decrypt} (obligation
 * 20).
 *
 * <p>The failures split along how a caller must handle them. A master key that is absent or the wrong
 * length is not a per-value outcome — nothing this module does can succeed until the DBA restores
 * {@code secrets/} — so it is thrown as a {@link MasterKeyFailure}. A value that does not open is a
 * result the caller branches on, so {@link #decrypt} and {@link #unwrap} return closed unions
 * ({@link Decryption}, {@link Unwrapping}) whose members are distinct types rather than one type
 * carrying a reason string (obligations 14 and 18).
 *
 * <p>Every method is effectful, because each reads the master-key file; reading that file is this
 * module's only side effect and it never writes one (obligations 10, 19).
 */
public interface ConnectionCrypto {

    /**
     * The one implementation v1 has, over the mounted {@code secrets/} directory whose
     * {@code master.key} it reads on every call (obligation 7; ADR-0035 §Master key).
     *
     * <p>The directory is an argument rather than a constant so a contract test can point it at a
     * temporary directory — the side effect L1 covers without a container ({@code
     * docs/spec/connection.md} §Verification). Construction reads nothing: a missing or malformed key
     * surfaces from the entry point that needed it, so no caller can hold a half-built module.
     */
    static ConnectionCrypto overSecretsDirectory(Path secretsDirectory) {
        return new MasterKeyCrypto(secretsDirectory);
    }

    /**
     * Encrypts credential material under the master key with AES-256-GCM (obligation 12). The result
     * is self-contained, so {@code workflow} stores it as one immutable credential version
     * (凭据版本) and {@code connection} keeps no state between calls (obligation 15).
     */
    Ciphertext encrypt(SecretMaterial material);

    /**
     * The inverse of {@link #encrypt}: the material, or the one typed failure that says why it did
     * not open (obligations 13, 14). Altered, truncated or foreign ciphertext never yields plaintext.
     */
    Decryption decrypt(Ciphertext ciphertext);

    /**
     * Issues a data-encryption key for one backup and wraps it under the master key (obligation 16).
     * Each call issues a key distinct from every key issued before it, and the wrapped form is
     * returned for {@code workflow} to store beside the backup artifact — never in the append-only
     * ledger, which would keep an erased key recoverable forever (ADR-0006 as amended by #97).
     */
    IssuedBackupKey wrap(BackupId backup);

    /**
     * The inverse of {@link #wrap}, the only way back into an encrypted backup (obligation 17). Its
     * two failures stay distinct because they send the DBA to different places: restore
     * {@code secrets/} from the copy kept off the machine, versus abandon this backup and choose
     * another (obligation 18).
     */
    Unwrapping unwrap(WrappedKey wrapped);

    /**
     * Validates that a wrapped key belongs to the present master key and returns the instruction that
     * makes it unrecoverable (obligation 19). It erases nothing itself: {@code connection} persists
     * nothing, so {@code workflow} carries the instruction out. Producing one for an already-erased
     * key succeeds, because cleanup is retried (obligation 19b).
     */
    ErasureInstruction erase(WrappedKey wrapped);

    /**
     * The present master key's fingerprint (ADR-0035 §Master key). It reveals nothing about the key
     * and stays stable across releases, because {@code workflow} stores it and upgrade compares it;
     * the key bytes themselves never leave this module.
     */
    KeyFingerprint fingerprint();
}
