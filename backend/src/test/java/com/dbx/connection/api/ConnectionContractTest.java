package com.dbx.connection.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * §Slices; #149). Slice 2 enables groups B and C as they stand (#150); groups D and E wait for slices
 * 3 and 4.
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
     * {@link #IMPLEMENTED}; arguments are null on purpose, because a stub has to fail before it looks at
     * them.
     */
    private static final List<Stub> STUBS = List.of(
            new Stub("wrap", 3, () -> stubCrypto().wrap(null)),
            new Stub("unwrap", 3, () -> stubCrypto().unwrap(null)),
            new Stub("erase", 3, () -> stubCrypto().erase(null)));

    /** Entry points a landed slice implements, one per line. Slice 2 lands three. */
    private static final Set<String> IMPLEMENTED = Set.of("encrypt", "decrypt", "fingerprint");

    /**
     * A mounted key for the stub ledger. Obligation 9 holds for every entry point, so the capabilities
     * slice 3 owns read the key before they refuse: with {@code secrets/} empty they would fail with
     * {@link MasterKeyUnavailable}, which says nothing about whether the capability exists. The ledger
     * gives them a key, so what it observes is the refusal itself (#150).
     */
    @TempDir
    static Path stubSecrets;

    @BeforeAll
    static void mountAKeyForTheStubLedger() throws IOException {
        writeMasterKey(stubSecrets, key(256));
    }

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
     */
    @Test
    void theNotImplementedFailureIsConnectionsOwnTypeAndNotDialects() {
        NotImplementedInSlice failure =
                assertThrows(NotImplementedInSlice.class, () -> stubCrypto().wrap(new BackupId("backup-1")));

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
    @Disabled("docs/spec/connection.md §Slices assigns wrap to slice 3 (#151)")
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
    @Disabled("docs/spec/connection.md §Slices assigns unwrap to slice 3 (#151)")
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
    @Disabled("docs/spec/connection.md §Slices assigns obligation 18 to slice 3 (#151)")
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
    @Disabled("docs/spec/connection.md §Slices assigns erase to slice 3 (#151)")
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
    @Disabled("docs/spec/connection.md §Slices assigns obligation 19a to slice 3 (#151)")
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
    @Disabled("docs/spec/connection.md §Slices assigns obligation 19b to slice 3 (#151)")
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

    // --- TLS material (E20; ADR-0005 "private keys") -----------------------------------------------

    @Test
    @Disabled("docs/spec/connection.md §Slices assigns TLS material to slice 4 (#152)")
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

    // --- Fixtures ----------------------------------------------------------------------------------

    /** A module over the ledger's mounted key, so a stub fails because of its slice and nothing else. */
    private static ConnectionCrypto stubCrypto() {
        return ConnectionCrypto.overSecretsDirectory(stubSecrets);
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
