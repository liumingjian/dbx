package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.ALL_OPTIONS;
import static com.dbx.dialect.api.MappingCase.PAIR;
import static com.dbx.dialect.api.MappingCase.SWITCHES_OFF;
import static com.dbx.dialect.api.MappingCase.supported;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Obligation 30 and obligation 23a of {@code docs/spec/dialect.md} §Verification: the ADR-0009 platform
 * policies are inexpressibly fixed, and byte-length comparability is decided per column (ADR-0040).
 * Everything is driven through {@code DialectCatalog.compileTime().select(...)} with values.
 */
class PairContractTest {

    private static final String UTF8MB4 = "utf8mb4";

    // --- Obligation 30: the policies are constants of the type ------------------------------------

    /**
     * The precedents are {@code ConnectionSemantics} and {@code SinkSettings}: a policy that is not state
     * cannot be weakened by an argument. So {@link ExecutionRequirements} may carry only the two facts the
     * pair actually decides, and every ADR-0009 policy must be a {@code public static final} of the type.
     */
    @Test
    void noConstructorParameterCanChangeAnAdr0009Policy() {
        List<String> components = Arrays.stream(ExecutionRequirements.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("boundedRead", "largeRecordEnvelopePendingPreflight"), components,
                "obligation 30: the only state of ExecutionRequirements is the bounded read it carries through "
                        + "and the large-record flag the mapping decisions settle; a policy carried as state "
                        + "could be handed a different value by a caller");

        for (Constructor<?> constructor : ExecutionRequirements.class.getDeclaredConstructors()) {
            assertEquals(2, constructor.getParameterCount(),
                    "obligation 30: no constructor of ExecutionRequirements takes a policy as a parameter");
        }

        for (Field policy : policyFields()) {
            assertTrue(Modifier.isStatic(policy.getModifiers()) && Modifier.isFinal(policy.getModifiers()),
                    "ADR-0009: " + policy.getName() + " is a platform policy, so it must be a constant of the "
                            + "type, never per-instance state");
        }
    }

    /** The policy values themselves, pinned. ADR-0003 fixes the two byte numbers exactly. */
    @Test
    void everyAdr0009PolicyIsPresentAndPinned() {
        assertEquals(20_971_520L, ExecutionRequirements.SOURCE_SUPPORT_BOUNDARY_BYTES,
                "ADR-0003: the source support boundary is 20 MiB per value and per row");
        assertEquals(26_214_400L, ExecutionRequirements.TRANSPORT_ENVELOPE_BYTES,
                "ADR-0003: Kafka's transport envelope is a separate 25 MiB, never the same number as the boundary");
        assertFalse(ExecutionRequirements.AUTO_CREATE,
                "ADR-0009 §Ownership: Connect never creates the target table");
        assertFalse(ExecutionRequirements.AUTO_EVOLVE,
                "ADR-0009 §Ownership: Connect never evolves the target table");
        assertTrue(ExecutionRequirements.LARGE_RECORD_ISOLATION,
                "ADR-0003: a large record table (大记录表) receives an isolated box");
        assertTrue(ExecutionRequirements.RUN_ISOLATED_NAMING,
                "ADR-0009 §Ownership: connector and topic names carry run and box identity");
        assertFalse(ExecutionRequirements.SKIP_RECORD_ALLOWED,
                "ADR-0009 §Failure and evidence: a record is never silently skipped");
        assertFalse(ExecutionRequirements.DEAD_LETTER_QUEUE_ALLOWED,
                "ADR-0009 §Failure and evidence: v1 uses no dead-letter queue as a success path");
        assertFalse(ExecutionRequirements.SECOND_DATA_PATH_ALLOWED,
                "ADR-0009: Kafka Connect is the sole data plane; DBX never becomes a second copy engine");
        assertEquals(9, policyFields().size(),
                "obligation 30: every policy this test pins is the whole list; a new one must be pinned here too");
    }

    /**
     * The other half of obligation 30: no reachable instance says otherwise. Neither switch of
     * {@code MappingOptions}, nor the mapping decisions, nor the pair itself exposes a policy as instance
     * state, so no public path can produce an {@code ExecutionRequirements} that weakens one.
     */
    @Test
    void noPublicPathBuildsExecutionRequirementsThatSayOtherwise() {
        for (MappingOptions options : ALL_OPTIONS) {
            ExecutionRequirements requirements = PAIR.executionRequirements(
                    List.of(supported(SourceColumns.column("longblob", "longblob").characterLength(255, 255), options)));
            for (Field policy : policyFields()) {
                assertTrue(accessorsOf(requirements).stream().noneMatch(name -> name.equals(policy.getName())),
                        "obligation 30: " + policy.getName() + " must not be readable per instance under "
                                + options + ", or two instances could disagree about a platform policy");
            }
        }
        assertEquals(2, MappingOptions.class.getRecordComponents().length,
                "obligation 13: MappingOptions holds exactly the two switches, so no switch can reach a policy");
    }

    // --- Obligation 30: what the mapping decisions do decide ---------------------------------------

    @Test
    void theBoundedReadIsCarriedThroughUnchanged() {
        assertEquals(PAIR.source().boundedRead(), PAIR.executionRequirements(List.of(anIntegerDecision())).boundedRead(),
                "ADR-0033: the pair carries the source dialect's own bounded read through; it does not restate it");
    }

    @Test
    void theLargeRecordFlagFollowsTheEnvelopePreflightAndNothingElse() {
        Supported envelope = supported(
                SourceColumns.column("longblob", "longblob").characterLength(255, 255), SWITCHES_OFF);
        assertTrue(envelope.requiredPreflights().contains(RequiredPreflight.LARGE_RECORD_ENVELOPE),
                "TP §6.3: the fixture must be a decision that actually requires the envelope preflight");

        Supported other = supported(SourceColumns.column("time", "time").datetimePrecision(0), SWITCHES_OFF);
        assertFalse(other.requiredPreflights().contains(RequiredPreflight.LARGE_RECORD_ENVELOPE),
                "TP §6.4: the fixture must require a preflight that is not the envelope");
        assertFalse(other.requiredPreflights().isEmpty(),
                "TP §6.4: a time column carries TIME_WITHIN_DAY, so this proves the flag follows one constant only");

        assertTrue(PAIR.executionRequirements(List.of(anIntegerDecision(), envelope))
                        .largeRecordEnvelopePendingPreflight(),
                "ADR-0003: one column requiring LARGE_RECORD_ENVELOPE puts large-record isolation in force "
                        + "pending preflight for the whole table");
        assertFalse(PAIR.executionRequirements(List.of(anIntegerDecision(), other))
                        .largeRecordEnvelopePendingPreflight(),
                "ADR-0003: only RequiredPreflight.LARGE_RECORD_ENVELOPE raises the flag; another preflight "
                        + "obligation is not a large record table (大记录表)");
    }

    @Test
    void anEmptyMappingDecisionListThrows() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PAIR.executionRequirements(List.of()),
                "a box with no mapped column is a caller bug, not a table without requirements");
        assertTrue(failure.getMessage().contains("ADR-0009"), failure.getMessage());
    }

    /**
     * {@code conflicts.md} §dialect: {@code batch.max.rows}, N, the routing snapshot and the execution
     * signature are {@code connector.deriveBox}'s, computed from M. Nothing here is.
     */
    @Test
    void itDerivesNoConfigurationAndComputesNothingFromM() {
        List<String> accessors = accessorsOf(PAIR.executionRequirements(List.of(anIntegerDecision())));
        assertEquals(List.of("boundedRead", "largeRecordEnvelopePendingPreflight"), accessors,
                "conflicts.md §dialect: the pair declares requirements; it does not derive batch.max.rows, "
                        + "the keyset chunk size N, the routing snapshot or the execution signature");
    }

    // --- Obligation 23a: byte-length comparability (ADR-0040) --------------------------------------

    @Test
    void theBinaryFamilyIsComparableWhateverElseTheColumnSays() {
        for (String type : List.of("binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob")) {
            assertTrue(comparable(column(type, type, Optional.empty())),
                    "ADR-0040: " + type + " is stored as bytes on both sides, so no character set enters the count");
        }
    }

    @Test
    void theCharacterFamilyIsComparableOnlyInAUtf8OrAsciiCharacterSet() {
        List<String> characterTypes =
                List.of("char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set");
        for (String type : characterTypes) {
            for (String charset : List.of(UTF8MB4, "utf8mb3", "utf8", "ascii")) {
                assertTrue(comparable(column(type, type, Optional.of(charset))),
                        "ADR-0040: MySQL counts " + charset + " bytes and PostgreSQL counts UTF-8 bytes; for "
                                + type + " in " + charset + " those agree");
            }
            for (String charset : List.of("latin1", "gbk", "utf16", "binary", "")) {
                assertFalse(comparable(column(type, type, Optional.of(charset))),
                        "ADR-0040: a " + charset + " " + type + " counts source-charset bytes that PostgreSQL's "
                                + "octet_length does not reproduce, so it is NOT_APPLICABLE / "
                                + "BYTE_LENGTH_NOT_COMPARABLE, never compared anyway");
            }
        }
    }

    /**
     * The asymmetry that is easiest to get wrong later: absence is permission for {@code json} only,
     * because MySQL reports no {@code CHARACTER_SET_NAME} for it yet stores it as utf8mb4. For every other
     * character type an absent character set is refused.
     */
    @Test
    void anAbsentCharacterSetIsPermissionForJsonOnly() {
        assertTrue(comparable(column("json", "json", Optional.empty())),
                "ADR-0040: json is stored as utf8mb4 by construction, so its byte counts agree with PostgreSQL's");
        assertTrue(comparable(column("json", "json", Optional.of(UTF8MB4))),
                "ADR-0040: json is comparable unconditionally; a reported character set decides nothing for it");
        for (String type : List.of("char", "varchar", "text", "longtext", "enum", "set")) {
            assertFalse(comparable(column(type, type, Optional.empty())),
                    "ADR-0040: an absent character set on " + type + " is unknown, not permission");
        }
    }

    @Test
    void everyOtherTypeIsNotProvablyComparable() {
        for (String type : List.of("int", "bigint", "decimal", "double", "tinyint", "datetime", "date", "timestamp",
                "time", "year", "bit", "geometry", "vector")) {
            assertFalse(comparable(column(type, type, Optional.empty())),
                    "ADR-0040: " + type + " has no source-byte length the two engines provably count alike");
        }
        assertFalse(comparable(column("datetime", "datetime", Optional.of(UTF8MB4))),
                "ADR-0040: comparability follows the data type first; a character set on a non-character type "
                        + "cannot make it comparable");
    }

    @Test
    void everyColumnIsAnsweredInInputOrder() {
        List<SourceColumn> columns = List.of(
                column("orders", "notes", "varchar", Optional.of("latin1")),
                column("orders", "payload", "longblob", Optional.empty()),
                column("orders", "labels", "text", Optional.of(UTF8MB4)),
                column("orders", "amount", "decimal", Optional.empty()));

        List<ValidationCapabilities.ByteLengthComparability> answers =
                PAIR.validationCapabilities(columns).byteLengthComparability();

        assertEquals(columns.stream().map(SourceColumn::coordinate).toList(),
                answers.stream().map(ValidationCapabilities.ByteLengthComparability::column).toList(),
                "ADR-0040: every column passed is answered, keyed by its coordinate, in input order");
        assertEquals(List.of(false, true, true, false),
                answers.stream().map(ValidationCapabilities.ByteLengthComparability::provablyComparable).toList(),
                "ADR-0040: latin1 text is not comparable, a blob and utf8mb4 text are, a decimal is not");
        assertEquals(List.of(), PAIR.validationCapabilities(List.of()).byteLengthComparability(),
                "ADR-0040: no column is a legal question with an empty answer; it is not a caller bug");
    }

    @Test
    void aDuplicateColumnThrows() {
        SourceColumn notes = column("orders", "notes", "varchar", Optional.of(UTF8MB4));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PAIR.validationCapabilities(List.of(notes, notes)),
                "ADR-0040: comparability is decided once per column; two answers would let a caller pick one");
        assertTrue(failure.getMessage().contains("ADR-0040") && failure.getMessage().contains("notes"),
                "the failure names the ruling and the column: " + failure.getMessage());
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    private static boolean comparable(SourceColumn column) {
        return PAIR.validationCapabilities(List.of(column)).byteLengthComparability().get(0).provablyComparable();
    }

    private static SourceColumn column(String dataType, String columnType, Optional<String> characterSet) {
        return column("orders", "c", dataType, characterSet);
    }

    /** Only {@code dataType} and {@code characterSetName} may matter, so every other fact stays absent. */
    private static SourceColumn column(String table, String name, String dataType, Optional<String> characterSet) {
        return new SourceColumn(new ColumnCoordinate("shop", table, name), dataType, dataType, false,
                OptionalLong.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.empty(), characterSet, Optional.empty(), Nullability.NULLABLE, Optional.empty(), "", 1);
    }

    private static Supported anIntegerDecision() {
        return supported(SourceColumns.column("int", "int").precision(10, 0), SWITCHES_OFF);
    }

    /** The {@code public static final} fields of {@link ExecutionRequirements}: its platform policies. */
    private static List<Field> policyFields() {
        List<Field> policies = new ArrayList<>();
        for (Field field : ExecutionRequirements.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                policies.add(field);
            }
        }
        return policies;
    }

    private static List<String> accessorsOf(ExecutionRequirements requirements) {
        List<String> accessors = new ArrayList<>();
        for (Method method : requirements.getClass().getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                    && Modifier.isPublic(method.getModifiers())
                    && !List.of("toString", "hashCode").contains(method.getName())) {
                accessors.add(method.getName());
            }
        }
        accessors.sort(String::compareTo);
        return accessors;
    }
}
