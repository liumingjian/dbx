package com.dbx.connection.masterkey;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.dbx.connection.api.BackupId;
import com.dbx.connection.api.Ciphertext;
import com.dbx.connection.api.ConnectionCrypto;
import com.dbx.connection.api.CorruptCiphertext;
import com.dbx.connection.api.DataEncryptionKey;
import com.dbx.connection.api.Decryption;
import com.dbx.connection.api.ErasureInstruction;
import com.dbx.connection.api.IssuedBackupKey;
import com.dbx.connection.api.KeyFingerprint;
import com.dbx.connection.api.MasterKeyFailure;
import com.dbx.connection.api.MasterKeyMalformed;
import com.dbx.connection.api.MasterKeyUnavailable;
import com.dbx.connection.api.MasterKeyWrongOrMissing;
import com.dbx.connection.api.SecretMaterial;
import com.dbx.connection.api.Unwrapping;
import com.dbx.connection.api.WrappedFormCorruptOrErased;
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
 * <p>Slice 2 implemented the key-file loader, {@code encrypt}, {@code decrypt} and
 * {@code fingerprint}; slice 3 adds the per-backup keys of {@code docs/spec/connection.md} §Slices,
 * so all six entry points of §Interface now do their work and nothing throws
 * {@link com.dbx.connection.NotImplementedInSlice} any more.
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
 *   <li><b>The purpose label is domain separation.</b> A credential ciphertext and a wrapped DEK are
 *       both AES-256-GCM under the same master key; labelling the associated data keeps one from
 *       opening as the other, which is what makes obligation 19a's "no path through the api returns
 *       it" a property of the crypto rather than of the callers.
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
     * The same envelope, sealed for a different purpose: a wrapped data-encryption key. Because the
     * label is authenticated, a credential ciphertext never opens as a wrapped key and a wrapped key
     * never opens as credential material — which is how obligation 19a's "no path through the api
     * returns it" holds against {@code decrypt} as well as against {@code unwrap}.
     */
    private static final byte[] WRAPPED_KEY_PURPOSE = "dbx:wrapped-dek:v1".getBytes(US_ASCII);

    /** The DEK size obligation 16's keys are issued at: AES-256, the size ADR-0006 encrypts at. */
    private static final int DEK_BYTES = 32;

    /**
     * What {@link #fingerprint} computes over. HMAC-SHA-256 of a fixed label under the master key is
     * a one-way function of the key that is fully determined by it: it reveals nothing about the key
     * (ADR-0035 §Master key) and is the same in every release, which is what lets upgrade read a
     * fingerprint stored by an older DBX and conclude that the key is unchanged rather than swapped.
     */
    private static final byte[] FINGERPRINT_PURPOSE = "dbx:master-key-fingerprint:v1".getBytes(US_ASCII);

    /**
     * Nonces and data-encryption keys, and nothing else. The platform seeds it; obligation 16's
     * "differs from every DEK issued before it" is a property of asking it for fresh bytes per call
     * rather than of remembering what it answered, which obligation 15 forbids.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

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
            return new Ciphertext(seal(key, CREDENTIAL_PURPOSE, plaintext));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public Decryption decrypt(Ciphertext ciphertext) {
        Objects.requireNonNull(ciphertext, "ciphertext");
        byte[] key = readMasterKey();
        try {
            // The two failures of obligation 14 are the two ways an envelope can refuse, read through
            // this union: a foreign key sends the DBA to secrets/, a broken value to a new password.
            return switch (open(key, CREDENTIAL_PURPOSE, ciphertext.bytes())) {
                case Opened.Value value -> new SecretMaterial(value.bytes());
                case Opened.SealedByAnotherKey ignored -> new WrongOrLostKey();
                case Opened.NotThisEnvelope ignored -> new CorruptCiphertext();
            };
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * A fresh 256-bit key from {@link #RANDOM}, wrapped in the same envelope under
     * {@link #WRAPPED_KEY_PURPOSE} (obligation 16). Distinctness comes from the generator, not from a
     * record of what was issued: obligation 15 forbids the record, and a record would also be a list of
     * the keys erasure exists to destroy.
     *
     * <p>The backup id is not sealed into the envelope. {@link #unwrap} is given a wrapped form and
     * nothing else, so an id bound in the associated data could never be checked; it would be a field
     * this module writes and no reader verifies. The pairing of backup to wrapped form belongs to
     * {@code workflow}, which stores the form beside the artifact — never in the append-only ledger,
     * where erased bytes could never be taken back (ADR-0006 as amended by #97).
     */
    @Override
    public IssuedBackupKey wrap(BackupId backup) {
        Objects.requireNonNull(backup, "backup");
        byte[] key = readMasterKey();
        byte[] dek = new byte[DEK_BYTES];
        try {
            RANDOM.nextBytes(dek);
            WrappedKey wrappedForm = new WrappedKey(seal(key, WRAPPED_KEY_PURPOSE, dek));
            return new IssuedBackupKey(backup, new DataEncryptionKey(dek), wrappedForm);
        } finally {
            Arrays.fill(dek, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * The DEK back, or one of obligation 18's two failures, which stay distinct because they send the
     * DBA to opposite places: restore {@code secrets/} from the copy kept off the machine, versus
     * abandon this backup and choose another.
     *
     * <p>Both halves of "master key wrong or missing" are that outcome here, including the
     * {@link MasterKeyFailure} the other entry points throw. Restoring a backup is the recovery path,
     * and a caller reading a backup cannot act differently on "no key is mounted" than on "the mounted
     * key did not wrap this"; telling them apart would also let a reader probe which key a wrapped form
     * belongs to. Erasure and corruption are the other outcome and never this one: a form that no
     * longer opens is not evidence about the key.
     */
    @Override
    public Unwrapping unwrap(WrappedKey wrapped) {
        Objects.requireNonNull(wrapped, "wrapped");
        byte[] key;
        try {
            key = readMasterKey();
        } catch (MasterKeyFailure missingOrMalformed) {
            return new MasterKeyWrongOrMissing();
        }
        try {
            return switch (open(key, WRAPPED_KEY_PURPOSE, wrapped.bytes())) {
                case Opened.Value value -> theKey(value);
                case Opened.SealedByAnotherKey ignored -> new MasterKeyWrongOrMissing();
                case Opened.NotThisEnvelope ignored -> new WrappedFormCorruptOrErased();
            };
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * The instruction, and no erasure (obligation 19): {@code connection} owns no bytes, so
     * {@code workflow} shreds the wrapped form beside the artifact and appends the tombstone.
     *
     * <p>What is validated is that the present master key is mounted and well formed — which is the
     * whole of "the wrapped form belongs to the present master key" that this module can act on. A form
     * that does not open under it is not refused: obligation 19b says producing an instruction for an
     * already-erased DEK succeeds, because cleanup is retried, and the wanted end state of an unopenable
     * form is the same as of an intact one — these bytes gone. Refusing it would leave a retried cleanup
     * stuck on the one input it is most likely to see.
     */
    @Override
    public ErasureInstruction erase(WrappedKey wrapped) {
        Objects.requireNonNull(wrapped, "wrapped");
        // The key is read and dropped: no instruction exists without the mounted key, and obligation 9
        // holds for this entry point like for every other.
        Arrays.fill(readMasterKey(), (byte) 0);
        return new ErasureInstruction(wrapped);
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
     * What opening an envelope found, before either union names it. {@link #decrypt} and
     * {@link #unwrap} report the same three answers to different callers in different words
     * (obligations 14 and 18), so the crypto answers once and each entry point translates: one place
     * decides what "wrong key" and "corrupt" mean, and neither union's members leak into the other.
     */
    private sealed interface Opened {

        /** The envelope opened: the bytes it was sealed over, and nothing else. */
        record Value(byte[] bytes) implements Opened {
        }

        /** The envelope names a master key that is not the mounted one, by its authenticated key id. */
        record SealedByAnotherKey() implements Opened {
        }

        /**
         * Nothing this key sealed for this purpose: altered, truncated, shredded, of an unknown layout, or
         * sealed for the other purpose. GCM cannot tell those apart, and no caller needs them apart.
         */
        record NotThisEnvelope() implements Opened {
        }
    }

    /** One envelope: a fresh nonce, the header it travels in, and the sealed bytes after it. */
    private static byte[] seal(byte[] key, byte[] purpose, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] header = header(keyId(key), nonce);
        try {
            byte[] sealed = gcm(Cipher.ENCRYPT_MODE, key, nonce, header, purpose, plaintext);
            byte[] envelope = Arrays.copyOf(header, header.length + sealed.length);
            System.arraycopy(sealed, 0, envelope, header.length, sealed.length);
            return envelope;
        } catch (GeneralSecurityException impossible) {
            // A 256-bit AES key and a 96-bit nonce are valid by construction, so this is a broken JVM
            // rather than a caller's problem. The cause carries no material: GCM refuses before output.
            throw new IllegalStateException(
                    "AES-256-GCM is unavailable in this JVM, so connection cannot encrypt anything", impossible);
        }
    }

    /** The inverse of {@link #seal}, and the one place the three answers of {@link Opened} are decided. */
    private static Opened open(byte[] key, byte[] purpose, byte[] envelope) {
        if (envelope.length < HEADER_BYTES + TAG_BITS / 8 || envelope[0] != ENVELOPE_VERSION) {
            // Too short to hold a sealed value, or a layout this release cannot read: either way the
            // stored value is not one this key wrote, and guessing at it is how wrong plaintext appears.
            return new Opened.NotThisEnvelope();
        }
        byte[] header = Arrays.copyOf(envelope, HEADER_BYTES);
        byte[] keyId = Arrays.copyOfRange(header, 1, 1 + KEY_ID_BYTES);
        if (!MessageDigest.isEqual(keyId, keyId(key))) {
            return new Opened.SealedByAnotherKey();
        }
        byte[] nonce = Arrays.copyOfRange(header, 1 + KEY_ID_BYTES, HEADER_BYTES);
        byte[] sealed = Arrays.copyOfRange(envelope, HEADER_BYTES, envelope.length);
        try {
            return new Opened.Value(gcm(Cipher.DECRYPT_MODE, key, nonce, header, purpose, sealed));
        } catch (GeneralSecurityException refused) {
            // The key is this envelope's own key, so what failed is the value: GCM authenticated the
            // header, the purpose and the ciphertext together and one of them is not what was sealed.
            // No partial plaintext exists to return, and the exception is dropped (obligation 11).
            return new Opened.NotThisEnvelope();
        }
    }

    /** The unwrapped DEK, with the opened bytes zeroed: the copy inside the value is the only one left. */
    private static DataEncryptionKey theKey(Opened.Value value) {
        byte[] dek = value.bytes();
        try {
            return new DataEncryptionKey(dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * One AES-256-GCM pass. The header and the purpose label are the associated data, so a value only
     * opens as what it was sealed as, under the key it names, with the nonce it travelled with.
     */
    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] header, byte[] purpose, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(header);
        cipher.updateAAD(purpose);
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
