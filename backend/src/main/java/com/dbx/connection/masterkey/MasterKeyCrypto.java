package com.dbx.connection.masterkey;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.dbx.connection.NotImplementedInSlice;
import com.dbx.connection.api.BackupId;
import com.dbx.connection.api.Ciphertext;
import com.dbx.connection.api.ConnectionCrypto;
import com.dbx.connection.api.CorruptCiphertext;
import com.dbx.connection.api.Decryption;
import com.dbx.connection.api.ErasureInstruction;
import com.dbx.connection.api.IssuedBackupKey;
import com.dbx.connection.api.KeyFingerprint;
import com.dbx.connection.api.MasterKeyMalformed;
import com.dbx.connection.api.MasterKeyUnavailable;
import com.dbx.connection.api.SecretMaterial;
import com.dbx.connection.api.Unwrapping;
import com.dbx.connection.api.WrappedKey;
import com.dbx.connection.api.WrongOrLostKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one {@link ConnectionCrypto}: AES-256-GCM under the master key mounted at
 * {@code <secrets>/master.key} (obligation 7; ADR-0035 §Master key).
 *
 * <p>Slice 2 implements the key-file loader, {@code encrypt}, {@code decrypt} and {@code fingerprint};
 * the per-backup keys of {@code docs/spec/connection.md} §Slices 3 still fail with
 * {@link NotImplementedInSlice}, and they read the key first so obligation 9 holds for every entry
 * point rather than for the three this slice landed.
 *
 * <p>The key is read on every call and zeroed before the call returns. Nothing about it is cached:
 * obligation 15 says the module keeps no state between calls, and a cached key would also let a
 * running DBX keep working with a key the DBA has already replaced — the one situation the
 * fingerprint exists to reveal.
 *
 * <p>The directory is held, not read: construction performs no I/O, so a caller never holds a module
 * that decided the key was fine before the key was needed.
 *
 * <h2>The envelope (§Implementer decides)</h2>
 *
 * <p>{@code version(1) ‖ keyId(8) ‖ nonce(12) ‖ AES-256-GCM(ciphertext ‖ tag(16))}, and the whole
 * header is the associated data together with a purpose label. Four decisions live in that line.
 *
 * <ul>
 *   <li><b>A fresh random 96-bit nonce per call.</b> A nonce repeated under one key loses GCM's
 *       authentication and leaks the xor of two credentials, so it must never happen; a counter would
 *       be state this module is forbidden to keep (obligation 15), and a stored counter could also be
 *       rolled back with the H2 file. 96 random bits is the size GCM is defined over, and a DBX
 *       installation writes credential versions in the thousands, nowhere near the birthday bound.
 *   <li><b>The key id tells obligation 14's two failures apart.</b> GCM gives one answer — the tag
 *       does not verify — whether the key is wrong or the bytes were altered, and those send the DBA
 *       to opposite places (restore {@code secrets/} versus re-enter the password). The first eight
 *       bytes of the fingerprint identify the key that sealed the envelope, so a foreign master key is
 *       recognised before the tag is even checked, and it reveals nothing the fingerprint does not
 *       already publish. Altering the key id is corruption of the envelope, which is why the header is
 *       authenticated too.
 *   <li><b>The purpose label is domain separation.</b> A credential ciphertext and slice 3's wrapped
 *       key are both AES-256-GCM under the same master key; labelling the associated data keeps one
 *       from opening as the other, which is what makes obligation 19a's "no path through the api
 *       returns it" a property of the crypto rather than of the callers.
 *   <li><b>The version byte is the way this envelope can change</b> without a stored ciphertext
 *       becoming undecryptable: a reader that does not know a version refuses it as corrupt instead of
 *       guessing at its layout.
 * </ul>
 */
public final class MasterKeyCrypto implements ConnectionCrypto {

    /** The only name and the only place obligation 7 allows the key to come from. */
    private static final String MASTER_KEY_FILE = "master.key";

    private static final int MASTER_KEY_BYTES = MasterKeyMalformed.REQUIRED_BITS / 8;

    /** Which envelope layout a ciphertext uses; see the class comment. */
    private static final byte ENVELOPE_VERSION = 1;

    private static final int KEY_ID_BYTES = 8;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = 1 + KEY_ID_BYTES + NONCE_BYTES;

    /**
     * What this ciphertext is for, mixed into the associated data. Changing either label would make
     * every value written under the old one unreadable, so they are versioned with the envelope.
     */
    private static final byte[] CREDENTIAL_PURPOSE = "dbx:credential:v1".getBytes(US_ASCII);

    /**
     * What {@link #fingerprint} computes over. HMAC-SHA-256 of a fixed label under the master key is
     * a one-way function of the key that is fully determined by it: it reveals nothing about the key
     * (ADR-0035 §Master key) and is the same in every release, which is what lets upgrade read a
     * fingerprint stored by an older DBX and conclude that the key is unchanged rather than swapped.
     */
    private static final byte[] FINGERPRINT_PURPOSE = "dbx:master-key-fingerprint:v1".getBytes(US_ASCII);

    /** Nonces only. Seeded by the platform, and asked for nothing else. */
    private static final SecureRandom NONCES = new SecureRandom();

    private final Path secretsDirectory;

    public MasterKeyCrypto(Path secretsDirectory) {
        this.secretsDirectory = Objects.requireNonNull(secretsDirectory, "secretsDirectory");
    }

    /** Where the key is read from, and the path the failures of obligations 8 and 9 name. */
    public Path masterKeyFile() {
        return secretsDirectory.resolve(MASTER_KEY_FILE);
    }

    @Override
    public Ciphertext encrypt(SecretMaterial material) {
        Objects.requireNonNull(material, "material");
        byte[] key = readMasterKey();
        byte[] plaintext = material.bytes();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            NONCES.nextBytes(nonce);
            byte[] header = header(keyId(key), nonce);
            byte[] sealed = gcm(Cipher.ENCRYPT_MODE, key, nonce, header, plaintext);
            byte[] envelope = Arrays.copyOf(header, header.length + sealed.length);
            System.arraycopy(sealed, 0, envelope, header.length, sealed.length);
            return new Ciphertext(envelope);
        } catch (GeneralSecurityException impossible) {
            // A 256-bit AES key and a 96-bit nonce are valid by construction, so this is a broken JVM
            // rather than a caller's problem. The cause carries no material: GCM refuses before output.
            throw new IllegalStateException(
                    "AES-256-GCM is unavailable in this JVM, so connection cannot encrypt anything", impossible);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public Decryption decrypt(Ciphertext ciphertext) {
        Objects.requireNonNull(ciphertext, "ciphertext");
        byte[] key = readMasterKey();
        byte[] envelope = ciphertext.bytes();
        try {
            if (envelope.length < HEADER_BYTES + TAG_BITS / 8 || envelope[0] != ENVELOPE_VERSION) {
                // Too short to hold a sealed value, or a layout this release cannot read: either way the
                // stored value is not one this key wrote, and guessing at it is how wrong plaintext appears.
                return new CorruptCiphertext();
            }
            byte[] header = Arrays.copyOf(envelope, HEADER_BYTES);
            byte[] keyId = Arrays.copyOfRange(header, 1, 1 + KEY_ID_BYTES);
            if (!MessageDigest.isEqual(keyId, keyId(key))) {
                return new WrongOrLostKey();
            }
            byte[] nonce = Arrays.copyOfRange(header, 1 + KEY_ID_BYTES, HEADER_BYTES);
            byte[] sealed = Arrays.copyOfRange(envelope, HEADER_BYTES, envelope.length);
            try {
                return new SecretMaterial(gcm(Cipher.DECRYPT_MODE, key, nonce, header, sealed));
            } catch (GeneralSecurityException refused) {
                // The key is this envelope's own key, so what failed is the value: GCM authenticated the
                // header and the ciphertext together and one of them changed. No partial plaintext exists
                // to return, and the exception is dropped rather than reported (obligation 11).
                return new CorruptCiphertext();
            }
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public IssuedBackupKey wrap(BackupId backup) {
        requireTheMasterKey();
        throw new NotImplementedInSlice("wrap", 3);
    }

    @Override
    public Unwrapping unwrap(WrappedKey wrapped) {
        requireTheMasterKey();
        throw new NotImplementedInSlice("unwrap", 3);
    }

    @Override
    public ErasureInstruction erase(WrappedKey wrapped) {
        requireTheMasterKey();
        throw new NotImplementedInSlice("erase", 3);
    }

    @Override
    public KeyFingerprint fingerprint() {
        byte[] key = readMasterKey();
        try {
            return new KeyFingerprint(HexFormat.of().formatHex(fingerprintOf(key)));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Obligation 9 is about every entry point, not about the ones a slice has landed: with
     * {@code secrets/} broken the DBA's next step is the same whether or not slice 3 exists, so the
     * capabilities it owns read the key before refusing. Slice 3 replaces each call with real work.
     */
    private void requireTheMasterKey() {
        Arrays.fill(readMasterKey(), (byte) 0);
    }

    /**
     * The mounted key, or the typed failure that says which file the DBA has to fix. A file of any
     * other length is refused (obligation 8): padding a short one would encrypt v1 credentials under
     * less entropy than ADR-0035 promises and truncating a long one would accept a file the release
     * script never wrote — and both produce a key that works perfectly until the real one is restored.
     *
     * <p>Every unreadable file is the same failure, whether it is absent, a directory or unopenable:
     * what distinguishes obligation 9's failure is that no key arrived, and reporting more would say
     * more about the host than a DBA needs.
     */
    private byte[] readMasterKey() {
        Path file = masterKeyFile();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException notReadable) {
            throw new MasterKeyUnavailable(file);
        }
        if (bytes.length != MASTER_KEY_BYTES) {
            int actualBits = bytes.length * Byte.SIZE;
            Arrays.fill(bytes, (byte) 0);
            throw new MasterKeyMalformed(file, actualBits);
        }
        return bytes;
    }

    private static byte[] header(byte[] keyId, byte[] nonce) {
        byte[] header = new byte[HEADER_BYTES];
        header[0] = ENVELOPE_VERSION;
        System.arraycopy(keyId, 0, header, 1, KEY_ID_BYTES);
        System.arraycopy(nonce, 0, header, 1 + KEY_ID_BYTES, NONCE_BYTES);
        return header;
    }

    /**
     * One AES-256-GCM pass. The header and the purpose label are the associated data, so a value only
     * opens as what it was sealed as, under the key it names, with the nonce it travelled with.
     */
    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] header, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(CREDENTIAL_PURPOSE);
        return cipher.doFinal(input);
    }

    /** Which master key sealed an envelope: the fingerprint's first bytes, and nothing of the key. */
    private static byte[] keyId(byte[] key) {
        return Arrays.copyOf(fingerprintOf(key), KEY_ID_BYTES);
    }

    private static byte[] fingerprintOf(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(FINGERPRINT_PURPOSE);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(
                    "HMAC-SHA-256 is unavailable in this JVM, so connection cannot fingerprint the master key",
                    impossible);
        }
    }
}
