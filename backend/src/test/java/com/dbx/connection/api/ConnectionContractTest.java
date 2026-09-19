package com.dbx.connection.api;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.dbx.connection.NotImplementedInSlice;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The primary documentation of {@code connection.api} (ADR-0018 §Module context). Each test defends one
 * ruling of {@code docs/spec/connection.md} and cites it in its failure message; everything is driven
 * through the api with values.
 *
 * <p>Slice 1 declared the shape and encrypted nothing, so the cases of groups B, C, D and E were
 * written here and {@code @Disabled} with the slice that enables them named in the reason — written
 * rather than deferred so that each slice enables a test somebody already argued about, instead of
 * inventing one that happens to pass against whatever it built ({@code docs/spec/connection.md}
 * §Slices; #149). Slice 2 enabled groups B and C as they stand (#150), slice 3 group D (#151) and
 * slice 4 group E (#152), so nothing here is switched off any more and the module is finished.
 *
 * <p>Nothing here is deferred to a higher rung: {@code connection} has no L2, and its one side effect —
 * reading the master-key file — is covered by a temporary {@code secrets/} directory (§Verification).
 */
class ConnectionContractTest {

    /** Material that is recognisable in output, so a leak is visible rather than inferred. */
    private static final byte[] PASSWORD = "hunter2-do-not-print".getBytes(UTF_8);

    // --- Stubs fail, they do not return empty (ADR-0008 §Ownership) ---------------------------------

    private record Stub(String capability, int slice, Supplier<?> call) {
    }

    /**
     * Every entry point of {@code docs/spec/connection.md} §Interface that is still a stub, with the
     * slice that owns it. A slice that implements one deletes its row and adds the name to
     * {@link #IMPLEMENTED}. Slice 3 deleted the last three rows, and the ledger stays empty rather than
     * being deleted: it is what makes a capability added later account for itself.
     */
    private static final List<Stub> STUBS = List.of();

    /**
     * Entry points a landed slice implements, one per line. Slice 3 lands the last three, so the ledger
     * above is empty and the factory below asserts that instead of a refusal — the row stays because a
     * later slice adding a capability must account for it here before it can be reviewed.
     */
    private static final Set<String> IMPLEMENTED =
            Set.of("encrypt", "decrypt", "wrap", "unwrap", "erase", "fingerprint");

    @TestFactory
    Stream<DynamicTest> anUnimplementedEntryPointFailsNamingItsSlice() {
        if (STUBS.isEmpty()) {
            return Stream.of(dynamicTest(
                    "no entry point is stubbed", this::everyInterfaceEntryPointIsEitherStubbedOrImplemented));
        }
        return STUBS.stream().map(stub -> dynamicTest(stub.capability(), () -> {
            NotImplementedInSlice failure = assertThrows(
                    NotImplementedInSlice.class,
                    stub.call()::get,
                    "ADR-0008 §Ownership bans default-success stubs: " + stub.capability() + " must fail, not "
                            + "return a value — in a crypto module an empty ciphertext reads like a working one");
            assertEquals(
                    stub.capability(),
                    failure.capability(),
                    "the failure names the capability of docs/spec/connection.md §Interface");
            assertEquals(
                    stub.slice(),
                    failure.slice(),
                    "the failure names the slice of docs/spec/connection.md §Slices that owns " + stub.capability());
            assertTrue(
                    failure.getMessage().contains(stub.capability())
                            && failure.getMessage().contains("slice " + stub.slice()),
                    "the message a caller sees names both: " + failure.getMessage());
        }));
    }

    /**
     * The ledger above and the interface cannot drift apart: a seventh entry point, or one quietly
     * dropped, fails here as well as in {@code ConnectionBoundaryTest}'s ArchUnit check.
     */
    @Test
    void everyInterfaceEntryPointIsEitherStubbedOrImplemented() {
        Set<String> declared = entryPoints(ConnectionCrypto.class);

        Set<String> accounted = new TreeSet<>(IMPLEMENTED);
        STUBS.forEach(stub -> accounted.add(stub.capability()));

        assertEquals(
                new TreeSet<>(ConnectionBoundaryRules.ENTRY_POINTS),
                declared,
                "docs/spec/connection.md §Interface fixes the six entry points; ConnectionCrypto declares "
                        + declared);
        assertEquals(
                declared,
                accounted,
                "every entry point is either stubbed with its slice or listed as implemented, so no capability "
                        + "can go missing between slices");
    }

    /**
     * Obligation 1 and ADR-0018 rule 1, at the one place they are easiest to break: the not-implemented
     * marker. {@code dialect}'s lives outside {@code dialect.api}, so importing it would make this module
     * name another module — the leaf violation slice 1 exists to catch.
     *
     * <p>Slice 3 landed the last capability, so no entry point throws the marker any longer and the case
     * asks the type directly instead of through a call. The ruling was never about a particular stub: it
     * is about which package the marker this module names lives in, and the marker is still here for the
     * next module-local capability that has to refuse before it is written (#151).
     */
    @Test
    void theNotImplementedFailureIsConnectionsOwnTypeAndNotDialects() {
        NotImplementedInSlice failure = new NotImplementedInSlice("a capability no slice has landed", 3);

        assertEquals(
                "com.dbx.connection",
                failure.getClass().getPackageName(),
                "docs/spec/connection.md obligation 1: connection declares its own NotImplementedInSlice; "
                        + "com.dbx.dialect.NotImplementedInSlice is outside dialect.api and naming it would break "
                        + "ADR-0018 rule 1");
        assertTrue(
                failure.getMessage().contains("docs/spec/connection.md §Slices"),
                "the message points at this module's slice table, not dialect's: " + failure.getMessage());
    }

    // --- Typed failures (obligations 8, 9, 13, 14, 18) ---------------------------------------------

    /**
     * The six failures of obligations 8, 9, 13, 14 and 18 are six types, because a caller branches on
     * them to tell the DBA what to do: restore {@code secrets/}, mount the right key file, re-enter the
     * password, or abandon this backup. One type carrying a reason string would compile with the branch
     * missing.
     */
    @Test
    void theTypedFailuresAreSixDistinctTypes() {
        List<Class<?>> failures = List.of(
                MasterKeyUnavailable.class,
                MasterKeyMalformed.class,
                WrongOrLostKey.class,
                CorruptCiphertext.class,
                MasterKeyWrongOrMissing.class,
                WrappedFormCorruptOrErased.class);

        assertEquals(
                6,
                Set.copyOf(failures).size(),
                "docs/spec/connection.md obligations 8, 9, 13, 14 and 18 name six failures: " + failures);
        for (Class<?> one : failures) {
            for (Class<?> other : failures) {
                assertTrue(
                        one == other || !one.isAssignableFrom(other),
                        "no failure stands in for another: " + other.getSimpleName() + " is a "
                                + one.getSimpleName());
            }
        }
        assertNotEquals(
                WrongOrLostKey.class,
                MasterKeyWrongOrMissing.class,
                "decrypt's key failure and unwrap's stay separate: obligation 18's two failures lead the DBA to "
                        + "different places than obligation 14's");
    }

    /**
     * Obligation 11 structurally: no failure type can carry the material, so no message, log line or
     * diagnostic package (ADR-0028) can print it even by accident. A {@code String} field is refused for
     * the same reason obligation 14 gives — it is how six types collapse into one with a reason.
     */
    @Test
    void noFailureTypeCarriesMaterialOrAReasonString() {
        List<Class<?>> failures = List.of(
                MasterKeyUnavailable.class,
                MasterKeyMalformed.class,
                WrongOrLostKey.class,
                CorruptCiphertext.class,
                MasterKeyWrongOrMissing.class,
                WrappedFormCorruptOrErased.class);

        for (Class<?> failure : failures) {
            for (Field field : failure.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                assertFalse(
                        type == byte[].class || type == char[].class || CharSequence.class.isAssignableFrom(type),
                        "docs/spec/connection.md obligation 11 and 14: " + failure.getSimpleName() + "."
                                + field.getName() + " is a " + type.getSimpleName()
                                + ", so it can carry material or a reason string");
            }
        }
    }

    /** Obligations 8 and 9: the two master-key failures say which file and what was wrong with it. */
    @Test
    void aMasterKeyFailureNamesTheFileAndTheRequiredLength() {
        Path keyFile = Path.of("secrets", "master.key");

        MasterKeyUnavailable unavailable = new MasterKeyUnavailable(keyFile);
        assertEquals(keyFile, unavailable.masterKeyFile(), "obligation 9's failure names the file it looked for");
        assertTrue(
                unavailable.getMessage().contains("secrets"),
                "the message sends the DBA to secrets/: " + unavailable.getMessage());

        MasterKeyMalformed malformed = new MasterKeyMalformed(keyFile, 128);
        assertEquals(128, malformed.actualBits(), "obligation 8's failure reports the length it found");
        assertTrue(
                malformed.getMessage().contains("256"),
                "the message names the one length ADR-0035 §Master key allows: " + malformed.getMessage());
        assertInstanceOf(
                MasterKeyFailure.class,
                malformed,
                "both master-key failures are thrown as MasterKeyFailure, because until secrets/ is fixed no "
                        + "entry point can do anything");
    }

    // --- Structural redaction (obligation 11; ADR-0028, ADR-0005) ----------------------------------

    /**
     * Redaction is a property of the types, not of a log configuration: a value that cannot print itself
     * cannot leak through a message somebody adds later (ADR-0028).
     */
    @Test
    void noTypeHoldingMaterialCanPrintIt() {
        SecretMaterial material = new SecretMaterial(PASSWORD);
        DataEncryptionKey key = new DataEncryptionKey(PASSWORD);
        IssuedBackupKey issued =
                new IssuedBackupKey(new BackupId("backup-1"), key, new WrappedKey(new byte[] {1, 2, 3}));

        String secret = new String(PASSWORD, UTF_8);
        for (Object holder : List.of(material, key, issued)) {
            assertFalse(
                    holder.toString().contains(secret),
                    "docs/spec/connection.md obligation 11: " + holder.getClass().getSimpleName()
                            + " prints material: " + holder);
        }
        assertEquals("SecretMaterial[redacted]", material.toString(), "the type names itself and nothing else");
        assertEquals("DataEncryptionKey[redacted]", key.toString(), "the type names itself and nothing else");
    }

    /** A value handed to this module cannot be changed afterwards through the array it came from. */
    @Test
    void materialCannotBeChangedThroughTheArrayItWasBuiltFrom() {
        byte[] bytes = PASSWORD.clone();
        SecretMaterial material = new SecretMaterial(bytes);

        Arrays.fill(bytes, (byte) 0);
        assertEquals(
                new SecretMaterial(PASSWORD),
                material,
                "obligation 15: a credential version is immutable once made, so it copies on the way in");

        byte[] handedOut = material.bytes();
        Arrays.fill(handedOut, (byte) 0);
        assertEquals(
                new SecretMaterial(PASSWORD),
                material,
                "clearing the copy a caller received — which a careful caller does — cannot blind the next "
                        + "reader");
    }

    // --- Master key (B7–B11; ADR-0035 §Master key) -------------------------------------------------

    @Test
    void theMasterKeyIsReadOnlyFromTheMountedFile(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));

        KeyFingerprint fingerprint = ConnectionCrypto.overSecretsDirectory(secrets).fingerprint();

        Files.delete(secrets.resolve("master.key"));
        MasterKeyUnavailable failure = assertThrows(
                MasterKeyUnavailable.class,
                () -> ConnectionCrypto.overSecretsDirectory(secrets).fingerprint(),
                "obligation 7: the mounted file is the only source — no environment variable, no .env, no H2 "
                        + "fallback keeps the module working once it is gone");
        assertEquals(secrets.resolve("master.key"), failure.masterKeyFile(), "it names the file it was denied");
        assertFalse(fingerprint.value().isBlank(), "the fingerprint came from the file while it existed");
    }

    @Test
    void aKeyFileThatIsNot256BitsIsRejectedRatherThanPaddedOrTruncated(@TempDir Path secrets) throws IOException {
        // A file holds whole bytes, so 248 and 264 are the nearest lengths on either side of 256 that a
        // DBA can actually mount; 255 and 257 bits cannot be written at all (#150).
        for (int bits : new int[] {128, 248, 264, 512}) {
            writeMasterKey(secrets, key(bits));

            MasterKeyMalformed failure = assertThrows(
                    MasterKeyMalformed.class,
                    () -> ConnectionCrypto.overSecretsDirectory(secrets).fingerprint(),
                    "obligation 8: a key of " + bits + " bits is refused, never padded or truncated into shape");
            assertEquals(bits, failure.actualBits(), "the failure reports the length it found");
        }
    }

    @Test
    void aMissingKeyFileFailsEveryEntryPointAndGeneratesNothing(@TempDir Path secrets) {
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);

        for (Runnable call : List.<Runnable>of(
                () -> crypto.encrypt(new SecretMaterial(PASSWORD)),
                () -> crypto.decrypt(new Ciphertext(new byte[] {1, 2, 3})),
                () -> crypto.wrap(new BackupId("backup-1")),
                crypto::fingerprint)) {
            assertThrows(
                    MasterKeyUnavailable.class,
                    call::run,
                    "obligation 9: every entry point fails with the typed failure when secrets/master.key is "
                            + "absent");
        }
        assertFalse(
                Files.exists(secrets.resolve("master.key")),
                "obligation 9: connection never generates a key — the release script owns it (ADR-0035)");
    }

    @Test
    void noEnvironmentVariableCanStandInForTheKeyFile() {
        JavaClasses connection = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.dbx.connection");

        List<String> environmentReads = new ArrayList<>();
        for (JavaClass type : connection) {
            for (JavaMethodCall call : type.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getFullName();
                String name = call.getTarget().getName();
                if (owner.equals("java.lang.System") && (name.equals("getenv") || name.equals("getProperty"))) {
                    environmentReads.add(call.getDescription());
                }
            }
        }

        assertTrue(
                environmentReads.isEmpty(),
                "obligation 7: an environment variable named like the key is ignored because nothing here reads "
                        + "the environment at all — a test cannot set one, so the proof is the absent call: "
                        + environmentReads);
    }

    @Test
    void theSecretsDirectoryIsUnchangedAfterEveryCall(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        List<String> before = listing(secrets);
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);

        crypto.encrypt(new SecretMaterial(PASSWORD));
        crypto.fingerprint();

        assertEquals(
                before,
                listing(secrets),
                "obligation 10: connection never writes to secrets/ — ADR-0035 §Master key forbids it even to "
                        + "upgrade and rollback");
    }

    @Test
    void keyBytesNeverAppearInAnExceptionOrAToString(@TempDir Path secrets) throws IOException {
        byte[] keyBytes = key(256);
        writeMasterKey(secrets, keyBytes);
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);

        String key = new String(keyBytes, UTF_8);
        assertFalse(crypto.toString().contains(key), "obligation 11: the module does not print its key");
        assertFalse(
                crypto.fingerprint().value().contains(key),
                "ADR-0035 §Master key: the fingerprint reveals nothing about the key");
    }

    // --- Credential encryption (C12–C15; ADR-0006 §Connection and credential model) ----------------

    @Test
    void decryptOfEncryptIsTheMaterialItStartedFrom(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        SecretMaterial material = new SecretMaterial(PASSWORD);

        Decryption decryption = crypto.decrypt(crypto.encrypt(material));

        assertEquals(
                material,
                assertInstanceOf(SecretMaterial.class, decryption, "obligation 12: the round trip returns the "
                        + "material, not a failure"),
                "obligation 12: decrypt(encrypt(x)) = x for any credential material");
    }

    @Test
    void anAlteredOrTruncatedCiphertextGivesCorruptCiphertextAndNeverPlaintext(@TempDir Path secrets)
            throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        byte[] bytes = crypto.encrypt(new SecretMaterial(PASSWORD)).bytes();

        byte[] flipped = bytes.clone();
        flipped[flipped.length - 1] ^= 0x01;
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);

        for (byte[] broken : List.of(flipped, truncated)) {
            assertInstanceOf(
                    CorruptCiphertext.class,
                    crypto.decrypt(new Ciphertext(broken)),
                    "obligation 13: GCM authentication refuses altered and truncated ciphertext, and the result "
                            + "is the typed failure rather than wrong plaintext");
        }
    }

    @Test
    void aForeignMasterKeyGivesWrongOrLostKeyAndTheTwoFailuresStayDistinct(@TempDir Path mine, @TempDir Path theirs)
            throws IOException {
        writeMasterKey(mine, key(256));
        writeMasterKey(theirs, other(256));
        Ciphertext ciphertext = ConnectionCrypto.overSecretsDirectory(mine).encrypt(new SecretMaterial(PASSWORD));

        Decryption underForeignKey = ConnectionCrypto.overSecretsDirectory(theirs).decrypt(ciphertext);

        assertInstanceOf(
                WrongOrLostKey.class,
                underForeignKey,
                "obligation 14: a ciphertext from another master key is a key problem, which sends the DBA to "
                        + "re-enter the password — not a corruption problem");
        assertFalse(
                underForeignKey instanceof CorruptCiphertext,
                "obligation 14: the two failures never collapse into one");
    }

    @Test
    void eachCiphertextIsSelfContainedAndTheModuleKeepsNoState(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        SecretMaterial material = new SecretMaterial(PASSWORD);

        Ciphertext first = ConnectionCrypto.overSecretsDirectory(secrets).encrypt(material);
        Ciphertext second = ConnectionCrypto.overSecretsDirectory(secrets).encrypt(material);

        assertNotEquals(first, second, "a fresh GCM nonce per call: a nonce is never reused under one key");
        for (Ciphertext ciphertext : List.of(first, second)) {
            assertEquals(
                    material,
                    assertInstanceOf(
                            SecretMaterial.class,
                            ConnectionCrypto.overSecretsDirectory(secrets).decrypt(ciphertext),
                            "obligation 15: each output is self-contained, so a new instance reads it"),
                    "obligation 15: connection keeps no state between calls");
        }
    }

    // --- Per-backup DEKs (D16–D19b; ADR-0006 as amended by #97) ------------------------------------

    @Test
    void eachWrapIssuesAKeyDistinctFromEveryKeyBefore(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);

        Set<DataEncryptionKey> issued = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            assertTrue(
                    issued.add(crypto.wrap(new BackupId("backup-" + i)).key()),
                    "obligation 16: each wrap issues a DEK that differs from every DEK issued before it");
        }
    }

    @Test
    void unwrapOfWrapIsTheSameKeyUnderTheSameMasterKey(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        IssuedBackupKey issued = crypto.wrap(new BackupId("backup-1"));

        Unwrapping unwrapped = crypto.unwrap(issued.wrappedForm());

        assertEquals(
                issued.key(),
                assertInstanceOf(DataEncryptionKey.class, unwrapped, "obligation 17: the round trip returns the "
                        + "key, which is the only way back into an encrypted backup"),
                "obligation 17: unwrap(wrap(k)) = k under the same master key");
    }

    @Test
    void unwrapsTwoFailuresStayDistinctAndAnErasedKeyIsNeverAKeyProblem(@TempDir Path mine, @TempDir Path theirs)
            throws IOException {
        writeMasterKey(mine, key(256));
        writeMasterKey(theirs, other(256));
        IssuedBackupKey issued = ConnectionCrypto.overSecretsDirectory(mine).wrap(new BackupId("backup-1"));
        byte[] corrupted = issued.wrappedForm().bytes();
        corrupted[corrupted.length - 1] ^= 0x01;

        Unwrapping underForeignKey =
                ConnectionCrypto.overSecretsDirectory(theirs).unwrap(issued.wrappedForm());
        Unwrapping corruptedForm =
                ConnectionCrypto.overSecretsDirectory(mine).unwrap(new WrappedKey(corrupted));

        assertInstanceOf(
                MasterKeyWrongOrMissing.class,
                underForeignKey,
                "obligation 18: a wrapped form from another master key sends the DBA to restore secrets/");
        assertInstanceOf(
                WrappedFormCorruptOrErased.class,
                corruptedForm,
                "obligation 18: a broken or erased wrapped form sends the DBA to another backup, and never "
                        + "surfaces as a key problem");
    }

    @Test
    void eraseReturnsAnInstructionAndWritesNothing(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        List<String> before = listing(secrets);
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        IssuedBackupKey issued = crypto.wrap(new BackupId("backup-1"));

        ErasureInstruction instruction = crypto.erase(issued.wrappedForm());

        assertEquals(
                issued.wrappedForm(),
                instruction.wrappedForm(),
                "obligation 19: the instruction names the wrapped form workflow must destroy");
        assertEquals(before, listing(secrets), "obligation 19: connection erases nothing itself and writes nothing");
    }

    @Test
    void anErasedKeyIsUnrecoverableThroughEveryPathOfTheApi(@TempDir Path secrets, @TempDir Path backupOfSecrets)
            throws IOException {
        byte[] master = key(256);
        writeMasterKey(secrets, master);
        writeMasterKey(backupOfSecrets, master);
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        IssuedBackupKey issued = crypto.wrap(new BackupId("backup-1"));
        WrappedKey erased = new WrappedKey(new byte[0]);

        // Applying the instruction destroys the wrapped form; what is left is what a reader would find.
        crypto.erase(issued.wrappedForm());

        assertInstanceOf(
                WrappedFormCorruptOrErased.class,
                crypto.unwrap(erased),
                "obligation 19a: with the master key mounted, no path through the api returns the DEK");
        assertInstanceOf(
                WrappedFormCorruptOrErased.class,
                ConnectionCrypto.overSecretsDirectory(backupOfSecrets).unwrap(erased),
                "obligation 19a: nor does it with the separately protected copy of the master key");
        assertInstanceOf(
                CorruptCiphertext.class,
                crypto.decrypt(new Ciphertext(erased.bytes())),
                "obligation 19a: decrypt is not a second way in — a wrapped form is not a credential ciphertext");
    }

    @Test
    void eraseIsIdempotentForAnAlreadyErasedKey(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        WrappedKey wrapped = crypto.wrap(new BackupId("backup-1")).wrappedForm();

        assertEquals(
                crypto.erase(wrapped),
                crypto.erase(wrapped),
                "obligation 19b: cleanup is retried, so producing the instruction twice succeeds and says the "
                        + "same thing (ADR-0006 §Recovery)");
    }

    /**
     * Obligation 19a is a negative claim about the whole interface, so it is checked against the whole
     * interface rather than against one method's failure code: a DEK whose wrapped form has been shredded
     * is unreachable through every one of the six entry points, holding the mounted master key and its
     * separately protected copy.
     *
     * <p>Two of the six can return a DEK at all — {@code wrap} and {@code unwrap} — and those are checked
     * by behaviour. The other four cannot: no result type of {@code encrypt}, {@code decrypt},
     * {@code erase} or {@code fingerprint} can hold a {@link DataEncryptionKey}, which the last assertion
     * establishes over the declared types so that adding such a result later lands here as a failure.
     * {@code decrypt} is checked by behaviour anyway, because a wrapped form is bytes a reader can hand
     * it and the only thing keeping it from opening is the purpose label in the associated data.
     */
    @Test
    void noEntryPointOfTheApiIsASecondWayBackToAnErasedKey(@TempDir Path secrets, @TempDir Path offMachineCopy)
            throws IOException {
        byte[] master = key(256);
        writeMasterKey(secrets, master);
        writeMasterKey(offMachineCopy, master);
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        IssuedBackupKey issued = crypto.wrap(new BackupId("backup-1"));
        DataEncryptionKey erasedKey = issued.key();
        String keyInHex = HexFormat.of().formatHex(erasedKey.bytes());

        // What is left after workflow carries the instruction out: the wrapped bytes are gone, zeroed, or
        // half-overwritten. These are the inputs a reader can still get hold of.
        List<WrappedKey> remains = List.of(
                new WrappedKey(new byte[0]),
                new WrappedKey(new byte[issued.wrappedForm().bytes().length]),
                new WrappedKey(Arrays.copyOf(issued.wrappedForm().bytes(), 12)));

        crypto.erase(issued.wrappedForm());

        for (ConnectionCrypto reader : List.of(crypto, ConnectionCrypto.overSecretsDirectory(offMachineCopy))) {
            for (WrappedKey remain : remains) {
                assertInstanceOf(
                        WrappedFormCorruptOrErased.class,
                        reader.unwrap(remain),
                        "obligation 19a: unwrap never returns the DEK once the wrapped form is shredded — not "
                                + "under the mounted master key and not under its separately protected copy");
                assertEquals(
                        remain,
                        crypto.erase(remain).wrappedForm(),
                        "obligation 19b: erase of an already-erased form succeeds, and its instruction carries the "
                                + "wrapped form only");
                assertInstanceOf(
                        CorruptCiphertext.class,
                        reader.decrypt(new Ciphertext(remain.bytes())),
                        "obligation 19a: decrypt is not a second way in either");
            }
        }
        assertInstanceOf(
                CorruptCiphertext.class,
                crypto.decrypt(new Ciphertext(issued.wrappedForm().bytes())),
                "obligation 19a: even an intact wrapped form does not open through decrypt — a wrapped key and a "
                        + "credential ciphertext are sealed for different purposes, so neither opens as the other");
        for (int i = 0; i < 8; i++) {
            assertNotEquals(
                    erasedKey,
                    crypto.wrap(new BackupId("backup-after-erasure-" + i)).key(),
                    "obligation 19a: wrap issues keys, it never re-issues one (obligation 16 is what makes this "
                            + "true of every wrap, not only of these eight)");
        }
        assertFalse(
                crypto.fingerprint().value().contains(keyInHex),
                "obligation 19a: the fingerprint is a function of the master key and says nothing of any DEK");

        assertEquals(
                new TreeSet<>(Set.of("unwrap", "wrap")),
                Arrays.stream(ConnectionCrypto.class.getDeclaredMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .filter(method -> canHoldAKey(method.getReturnType()))
                        .map(Method::getName)
                        .collect(Collectors.toCollection(TreeSet::new)),
                "obligation 19a: only wrap and unwrap have a result a DEK can travel in at all, so the four "
                        + "checked above by type are the rest of the api — a seventh path would show up here");
    }

    // --- TLS material (E20; ADR-0005 "private keys") -----------------------------------------------

    /**
     * What one TLS mode (TLS 模式) hands DBX. The three modes are {@code CONTEXT.md} §TLS mode's: TLS
     * disabled (不启用 TLS) hands over nothing, Server authenticated (校验服务端证书) the authority that
     * signs the server's certificate, and Mutual (双向证书校验) that plus the client certificate, the
     * client private key and the passphrase that unlocks the key. Obligation 20 is about every row
     * travelling the one road, not only about the two secret rows of the last mode.
     */
    private record TlsMode(String mode, List<SecretMaterial> material) {
    }

    private static final SecretMaterial SERVER_CA =
            new SecretMaterial("-----BEGIN CERTIFICATE-----\nthe-authority".getBytes(UTF_8));

    private static final SecretMaterial CLIENT_CERTIFICATE =
            new SecretMaterial("-----BEGIN CERTIFICATE-----\nthe-client".getBytes(UTF_8));

    private static final SecretMaterial CLIENT_PRIVATE_KEY =
            new SecretMaterial("-----BEGIN PRIVATE KEY-----\nthe-most-dangerous-value-in-the-install".getBytes(UTF_8));

    private static final SecretMaterial CLIENT_KEY_PASSPHRASE =
            new SecretMaterial("passphrase-that-unlocks-the-client-key".getBytes(UTF_8));

    private static final List<TlsMode> TLS_MODES = List.of(
            new TlsMode("TLS disabled (不启用 TLS)", List.of()),
            new TlsMode("Server authenticated (校验服务端证书)", List.of(SERVER_CA)),
            new TlsMode(
                    "Mutual (双向证书校验)",
                    List.of(SERVER_CA, CLIENT_CERTIFICATE, CLIENT_PRIVATE_KEY, CLIENT_KEY_PASSPHRASE)));

    /**
     * Every purpose label this module seals under, and the whole list: a credential (slice 2), a wrapped
     * DEK (slice 3), and the fingerprint's fixed label. TLS material adds none — it travels under the
     * credential label, which is "no seventh entry point" one layer further down.
     */
    private static final Set<String> PURPOSE_LABELS =
            Set.of("dbx:credential:v1", "dbx:wrapped-dek:v1", "dbx:master-key-fingerprint:v1");

    /**
     * How much of an envelope two values sealed under one master key share: the version byte and the key
     * id. The nonce after it is fresh per call, so a comparison stops there.
     */
    private static final int ENVELOPE_PREFIX_BYTES = 1 + 8;

    @Test
    void tlsClientKeyAndPassphraseTravelThroughEncryptLikeAnyCredential(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);
        SecretMaterial privateKey = new SecretMaterial("-----BEGIN PRIVATE KEY-----\nmutual".getBytes(UTF_8));
        SecretMaterial passphrase = new SecretMaterial("passphrase-of-the-client-key".getBytes(UTF_8));

        for (SecretMaterial material : List.of(privateKey, passphrase)) {
            Decryption decryption = crypto.decrypt(crypto.encrypt(material));

            assertEquals(
                    material,
                    assertInstanceOf(SecretMaterial.class, decryption, "obligation 20: TLS material enters only "
                            + "through encrypt/decrypt and round-trips like credential material"),
                    "obligation 20: the mutual-TLS client private key and its passphrase are secret material "
                            + "(ADR-0005)");
            assertFalse(
                    material.toString().contains("PRIVATE KEY") || material.toString().contains("passphrase"),
                    "obligation 11: neither the private key nor its passphrase can print itself");
        }
    }

    /**
     * Obligation 20 for all three TLS modes, by behaviour: what a mode hands over round-trips through
     * {@code encrypt}/{@code decrypt}, and its ciphertext is the same kind of value as a password's.
     *
     * <p>A round trip alone would also pass for an implementation that recognised a PEM header and gave
     * it its own treatment, so the envelopes are compared as well: a private key of n bytes seals to the
     * same length, the same envelope version and the same key id as a password of n bytes. An extra flag
     * byte, a second cipher, a note about what the value is, or a clear copy riding along all land here.
     */
    @Test
    void everyTlsModesMaterialSealsIntoTheSameEnvelopeAsAPassword(@TempDir Path secrets) throws IOException {
        writeMasterKey(secrets, key(256));
        ConnectionCrypto crypto = ConnectionCrypto.overSecretsDirectory(secrets);

        for (TlsMode mode : TLS_MODES) {
            for (SecretMaterial material : mode.material()) {
                byte[] plaintext = material.bytes();
                byte[] sealed = crypto.encrypt(material).bytes();
                byte[] asAPassword = crypto.encrypt(new SecretMaterial(passwordOfLength(plaintext.length))).bytes();

                assertEquals(
                        material,
                        assertInstanceOf(
                                SecretMaterial.class,
                                crypto.decrypt(new Ciphertext(sealed)),
                                "obligation 20: " + mode.mode() + " material enters only through encrypt/decrypt "
                                        + "and comes back out of them (ADR-0005 \"private keys\")"),
                        "obligation 20: it round-trips like credential material, because it is credential material");
                assertFalse(
                        containsBytes(sealed, plaintext),
                        "obligations 20, 11: no clear copy of the material travels in the ciphertext — the failure "
                                + "this forecloses is the most sensitive value in the install being the one thing "
                                + "left in the clear: " + mode.mode());
                assertEquals(
                        asAPassword.length,
                        sealed.length,
                        "obligation 20: nothing new happens for " + mode.mode() + " — the same envelope as a "
                                + "password of the same length, so no second cipher and no metadata about what "
                                + "the value is");
                assertArrayEquals(
                        Arrays.copyOf(asAPassword, ENVELOPE_PREFIX_BYTES),
                        Arrays.copyOf(sealed, ENVELOPE_PREFIX_BYTES),
                        "obligation 20: the envelope version and the master key it names do not depend on what the "
                                + "material is: " + mode.mode());

                String inTheClear = new String(plaintext, UTF_8);
                byte[] shredded = Arrays.copyOf(sealed, ENVELOPE_PREFIX_BYTES + 12 + 16);
                for (Object printed :
                        List.of(new Ciphertext(sealed), material, crypto.decrypt(new Ciphertext(shredded)))) {
                    assertFalse(
                            printed.toString().contains(inTheClear),
                            "obligation 11: neither the material, its ciphertext nor the failure it produces prints "
                                    + "a TLS private key or its passphrase: " + printed);
                }
            }
        }

        MasterKeyFailure noKey = assertThrows(
                MasterKeyUnavailable.class,
                () -> ConnectionCrypto.overSecretsDirectory(secrets.resolve("gone")).encrypt(CLIENT_PRIVATE_KEY),
                "obligation 9 holds for TLS material like for anything else: a missing key is the typed failure");
        assertFalse(
                String.valueOf(noKey.getMessage()).contains("PRIVATE KEY"),
                "obligation 11: the exception a DBA reads names the file to fix and nothing of the material: "
                        + noKey.getMessage());
    }

    /**
     * Obligation 20's negative half, which no round trip can observe: there is no second door and no
     * second cipher for TLS material to arrive through.
     *
     * <p>Two claims that pass vacuously otherwise. {@code ConnectionBoundaryTest}'s E20 check counts entry
     * point <em>names</em>, so an {@code encrypt(SecretMaterial, ...)} overload beside the real one — a
     * TLS-shaped door in everything but name — leaves it at six; here the signatures are compared, so it
     * cannot. And a TLS-specific purpose label read by a decrypt that tries both labels round-trips exactly
     * as this module does, so the labels it seals under are read out of its bytecode and compared against
     * the whole list: a third label for TLS is the separate cipher path, one layer below the api.
     */
    @Test
    void tlsMaterialHasNoEntryPointAndNoPurposeLabelOfItsOwn() throws IOException {
        Set<String> signatures = Arrays.stream(ConnectionCrypto.class.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(ConnectionContractTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(
                new TreeSet<>(Set.of(
                        "decrypt(Ciphertext)",
                        "encrypt(SecretMaterial)",
                        "erase(WrappedKey)",
                        "fingerprint()",
                        "unwrap(WrappedKey)",
                        "wrap(BackupId)")),
                signatures,
                "obligation 20: encrypt takes the material and decrypt the ciphertext, with nothing beside them and "
                        + "no overload — an overloaded entry point keeps E20's name count at six");
        for (String signature : signatures) {
            String lowercase = signature.toLowerCase(Locale.ROOT);
            assertFalse(
                    Stream.of("tls", "cert", "passphrase", "privatekey").anyMatch(lowercase::contains),
                    "obligation 20: no entry point is named for TLS material, because none is about it: " + signature);
        }

        assertEquals(
                new TreeSet<>(PURPOSE_LABELS),
                new TreeSet<>(purposeLabelsSealedUnder("com.dbx.connection")),
                "obligation 20: TLS material travels under the credential label — a label of its own would be a "
                        + "separate cipher path under an api that shows none");
    }

    // --- Fixtures ----------------------------------------------------------------------------------

    /**
     * Whether a {@link DataEncryptionKey} can travel inside a value of this type: the type itself, any
     * member of a sealed union, or any component of a record. It is reachability through the declared
     * types, which is what obligation 19a's "no path through the api" means for the four entry points
     * whose results cannot carry a key.
     */
    private static boolean canHoldAKey(Class<?> type) {
        if (type.equals(DataEncryptionKey.class)) {
            return true;
        }
        if (type.isSealed()) {
            return Arrays.stream(type.getPermittedSubclasses()).anyMatch(ConnectionContractTest::canHoldAKey);
        }
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents())
                    .anyMatch(component -> canHoldAKey(component.getType()));
        }
        return false;
    }

    /** A password of a given length, so a TLS envelope can be compared with a credential envelope. */
    private static byte[] passwordOfLength(int length) {
        byte[] password = new byte[length];
        Arrays.fill(password, (byte) 'p');
        return password;
    }

    /** Whether {@code needle} appears in {@code haystack} verbatim — a clear copy of material, if it does. */
    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        for (int at = 0; at + needle.length <= haystack.length; at++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, at, at + needle.length), needle)) {
                return true;
            }
        }
        return false;
    }

    private static String signature(Method method) {
        return method.getName()
                + Arrays.stream(method.getParameterTypes())
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * Every {@code dbx:}-prefixed label in the compiled bytecode of a package, which is where this module's
     * purpose labels live. Reading the class files is deliberate: what a value is sealed under is invisible
     * from the api — a second label with a reader that tries both round-trips exactly like one label does —
     * so the only place obligation 20's "no separate cipher" can be checked is the code that seals.
     */
    private static Set<String> purposeLabelsSealedUnder(String packageName) throws IOException {
        Set<String> labels = new TreeSet<>();
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(packageName);
        Pattern label = Pattern.compile("dbx:[a-z0-9:.\\-]+");
        for (JavaClass type : classes) {
            String resource = type.getName().replace('.', '/') + ".class";
            try (InputStream bytecode = ConnectionContractTest.class.getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(bytecode, "the compiled class of " + type.getName() + " is on the test classpath");
                Matcher found = label.matcher(new String(bytecode.readAllBytes(), ISO_8859_1));
                while (found.find()) {
                    labels.add(found.group());
                }
            }
        }
        return labels;
    }

    private static Set<String> entryPoints(Class<?> api) {
        return Arrays.stream(api.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** A key of a given bit length, deterministic so a failure is reproducible. */
    private static byte[] key(int bits) {
        byte[] bytes = new byte[(bits + 7) / 8];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 7 + 1);
        }
        return bytes;
    }

    /** A different key of the same length, for the foreign-key cases of obligations 14 and 18. */
    private static byte[] other(int bits) {
        byte[] bytes = key(bits);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= (byte) 0xff;
        }
        return bytes;
    }

    private static void writeMasterKey(Path secrets, byte[] bytes) throws IOException {
        Files.write(secrets.resolve("master.key"), bytes);
    }

    private static List<String> listing(Path directory) throws IOException {
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.map(Path::toString).sorted().toList();
        }
    }
}
