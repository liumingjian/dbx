package com.dbx.dialect.api;

import static com.dbx.dialect.api.SourceColumns.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * TP §6.3 through {@code pair.map}: the character, binary and special rows, the binary character set
 * decided from the source, {@code ENUM}/{@code SET}, {@code JSON} without a byte-fidelity claim, and
 * the widening property of alternatives (TP §6.1, §15.1). Golden set 1 pins the full rows.
 */
class CharacterBinarySpecialMappingContractTest {

    private static final DatabasePair PAIR = (DatabasePair) DialectCatalog.compileTime()
            .select(new ProductVersion("MySQL", "8.0.36"), new ProductVersion("PostgreSQL", "15.4"));
    private static final MappingOptions OFF = MappingOptions.DEFAULTS;
    private static final List<MappingOptions> ALL_OPTIONS = List.of(
            new MappingOptions(false, false), new MappingOptions(true, false),
            new MappingOptions(false, true), new MappingOptions(true, true));

    private static final String UTF8MB4 = "utf8mb4";
    private static final String UTF8MB4_CI = "utf8mb4_0900_ai_ci";
    private static final long SEED = 0x5EED_0116L;

    @Test
    void charAndVarcharKeepTheCharacterLengthAndNeedNoPreflight() {
        Supported fixed = supported(column("char", "char(10)").characterLength(10, 40).charset(UTF8MB4, UTF8MB4_CI));
        assertEquals(new TargetType(TargetTypeName.CHAR, List.of(10)), fixed.targetType(), "TP §6.3: CHAR(M) → char(M)");
        assertEquals(ValueSemantics.TRAILING_SPACE_PADDED, fixed.valueSemantics(),
                "TP §6.3: CHAR sampling comparison removes trailing U+0020 only");

        Supported varying = supported(column("varchar", "varchar(255)").characterLength(255, 1020)
                .charset(UTF8MB4, UTF8MB4_CI));
        assertEquals(new TargetType(TargetTypeName.VARCHAR, List.of(255)), varying.targetType(),
                "TP §6.3: VARCHAR(M) → varchar(M), M in characters, not the 1020 octets");
        for (Supported decision : List.of(fixed, varying)) {
            assertEquals(new ConnectRepresentation(SchemaType.STRING, Optional.empty()),
                    decision.connectRepresentation(), "TP §6.3: STRING");
            assertEquals(List.of(), decision.requiredPreflights(), "TP §6.3: no width preflight is needed");
        }
    }

    @Test
    void largeTextAndBlobTypesRequireThe20MiBValueAndRowPreflight() {
        record Row(String type, TargetTypeName target, SchemaType schema, SourceColumns column) {
        }
        List<Row> rows = new ArrayList<>();
        for (String text : List.of("tinytext", "text", "mediumtext", "longtext")) {
            rows.add(new Row(text, TargetTypeName.TEXT, SchemaType.STRING,
                    column(text, text).characterLength(255, 255).charset(UTF8MB4, UTF8MB4_CI)));
        }
        for (String blob : List.of("tinyblob", "blob", "mediumblob", "longblob")) {
            rows.add(new Row(blob, TargetTypeName.BYTEA, SchemaType.BYTES, column(blob, blob).characterLength(255, 255)));
        }
        for (Row row : rows) {
            Supported decision = supported(row.column());
            assertEquals(new TargetType(row.target(), List.of()), decision.targetType(), "TP §6.3: " + row.type());
            assertEquals(row.schema(), decision.connectRepresentation().schemaType(), "TP §6.3: " + row.type());
            assertTrue(decision.requiredPreflights().contains(RequiredPreflight.LARGE_RECORD_ENVELOPE),
                    "TP §6.3/§6.6 check 1, ADR-0003: " + row.type() + " must carry the 20 MiB value and row "
                            + "preflight on its decision, so the large-record envelope reaches the operator through "
                            + "the mapping instead of being remembered by preflight");
        }
    }

    @Test
    void binaryAndVarbinaryAreBytea() {
        for (SourceColumns bytes : List.of(
                column("binary", "binary(16)").characterLength(16, 16),
                column("varbinary", "varbinary(255)").characterLength(255, 255))) {
            Supported decision = supported(bytes);
            assertEquals(new TargetType(TargetTypeName.BYTEA, List.of()), decision.targetType(), "TP §6.3");
            assertEquals(new ConnectRepresentation(SchemaType.BYTES, Optional.empty()),
                    decision.connectRepresentation(), "TP §6.3: BYTES");
            assertEquals(ValueSemantics.EXACT_BYTES, decision.valueSemantics(), "byte strings compare byte for byte");
        }
    }

    @Test
    void aCharacterColumnOfTheBinaryCharacterSetIsByteaDecidedFromSourceMetadata() {
        for (SourceColumns character : binaryCharacterColumns()) {
            for (MappingOptions options : ALL_OPTIONS) {
                Supported decision = supported(character, options);
                String label = "TP §6.3: " + character.build().columnType() + " CHARACTER SET binary holds bytes, "
                        + "so it is bytea over BYTES, decided from its own charset/collation facts";
                assertEquals(new TargetType(TargetTypeName.BYTEA, List.of()), decision.targetType(), label);
                assertEquals(SchemaType.BYTES, decision.connectRepresentation().schemaType(), label);
                assertEquals(JdbcBinder.BYTES, decision.jdbcBinder(), label);
                assertEquals(ExtractionIntent.BINARY_CHARACTER_AS_BYTES, decision.extractionIntent(), label);
            }
        }
    }

    @Test
    void theTargetPreferenceCannotInfluenceTheBinaryDecision() throws NoSuchMethodException {
        Method map = DatabasePair.class.getMethod("map", SourceColumn.class, MappingOptions.class);
        assertEquals(1, Arrays.stream(DatabasePair.class.getMethods()).filter(m -> m.getName().equals("map")).count(),
                "TP §6.1: pair.map has exactly one signature");
        assertEquals(List.of(SourceColumn.class, MappingOptions.class), List.of(map.getParameterTypes()),
                "TP §6.3: nothing from the target side reaches the decision; its only inputs are source facts and "
                        + "the two task switches");
        assertEquals(List.of(boolean.class, boolean.class),
                Arrays.stream(MappingOptions.class.getRecordComponents()).map(RecordComponent::getType).toList(),
                "TP §6.1: the switches are two booleans, neither a target type preference");

        for (SourceColumns binary : binaryCharacterColumns()) {
            SourceColumn facts = binary.build();
            SourceColumn asText = new SourceColumn(facts.coordinate(), facts.dataType(), facts.columnType(),
                    facts.unsigned(), facts.characterMaximumLength(), facts.characterOctetLength(),
                    facts.numericPrecision(), facts.numericScale(), facts.datetimePrecision(), Optional.of(UTF8MB4),
                    Optional.of("utf8mb4_bin"), facts.nullability(), facts.columnDefault(), facts.extra(),
                    facts.ordinalPosition());
            MappingDecision bytes = PAIR.map(facts, OFF);
            assertEquals(TargetTypeName.BYTEA, assertInstanceOf(Supported.class, bytes).targetType().name(),
                    "TP §6.3: " + facts.columnType() + " is bytea because its source charset is binary, whatever "
                            + "target type its declared type would otherwise prefer");
            for (MappingOptions options : ALL_OPTIONS) {
                assertEquals(bytes, PAIR.map(facts, options),
                        "TP §6.3: no task switch moves " + facts.columnType() + " off bytea");
                Supported text = assertInstanceOf(Supported.class, PAIR.map(asText, options));
                assertNotEquals(TargetTypeName.BYTEA, text.targetType().name(),
                        "TP §6.3: the same declared type with a text charset and a *_bin collation holds text; "
                                + "only the source's binary character set makes it bytea: " + facts.columnType());
                assertEquals(SchemaType.STRING, text.connectRepresentation().schemaType());
            }
        }
    }

    @Test
    void enumIsTextWithACheckAndARequiredExactMembershipPreflight() {
        for (SourceColumns enumeration : List.of(
                column("enum", "enum('small','medium','large')").characterLength(6, 24).charset(UTF8MB4, UTF8MB4_CI),
                column("enum", "enum('it''s','')").characterLength(4, 16).charset(UTF8MB4, UTF8MB4_CI).notNull())) {
            Supported decision = supported(enumeration);
            assertEquals(new TargetType(TargetTypeName.TEXT, List.of()), decision.targetType(), "TP §6.3: ENUM → text");
            assertEquals(List.of(ContractEffect.ENUM_CHECK_CONSTRAINT), decision.contractEffects(),
                    "TP §6.3: ENUM → text + CHECK");
            assertTrue(decision.requiredPreflights().contains(RequiredPreflight.ENUM_VALUE_DECLARED),
                    "TP §6.3/§6.6 check 5: MySQL can hold a value outside the declared set, so every value must "
                            + "preflight into the set and not be the illegal sentinel");
        }
    }

    @Test
    void setIsTextWithNoCombinatorialCheck() {
        Supported decision = supported(column("set", "set('read','write','admin')").characterLength(16, 64)
                .charset(UTF8MB4, UTF8MB4_CI));
        assertEquals(new TargetType(TargetTypeName.TEXT, List.of()), decision.targetType(), "TP §6.3: SET → text");
        assertEquals(List.of(), decision.contractEffects(),
                "TP §6.3: no combinatorial CHECK; enumerating the power set is a denial of service on the target");
        assertEquals(List.of(MappingNotice.SET_WITHOUT_CHECK_CONSTRAINT), decision.notices(),
                "the missing CHECK is stated, not silent");
    }

    @Test
    void jsonIsJsonWithJsonbAndTextOnlyAsAlternativesAndNoByteFidelityClaim() {
        for (MappingOptions options : ALL_OPTIONS) {
            Supported decision = supported(column("json", "json"), options);
            assertEquals(new TargetType(TargetTypeName.JSON, List.of()), decision.targetType(), "TP §6.3: JSON → json");
            assertTrue(decision.notices().contains(MappingNotice.JSON_BYTE_FIDELITY_NOT_CLAIMED),
                    "TP §6.3: byte fidelity of JSON text belongs to release certification, so the decision says so");
            assertFalse(List.of(ValueSemantics.EXACT_TEXT, ValueSemantics.EXACT_BYTES).contains(decision.valueSemantics()),
                    "TP §6.3: no decision asserts byte fidelity for JSON");
            assertFalse(decision.alternatives().isEmpty(), "TP §6.3: JSON offers a structured alternative");
            for (MappingAlternative alternative : decision.alternatives()) {
                assertTrue(List.of(TargetTypeName.JSONB, TargetTypeName.TEXT).contains(alternative.targetType().name()),
                        "TP §6.3: only jsonb or text may be offered for JSON, got " + alternative);
                assertEquals(decision.valueSemantics(), alternative.valueSemantics(),
                        "TP §6.3: a JSON alternative must have matching validation semantics");
            }
        }
    }

    @Test
    void geometryVectorAndNonWhitelistedTypesAreUnsupportedWithStableReasons() {
        for (String geometry : List.of("geometry", "point", "linestring", "polygon", "multipoint", "multilinestring",
                "multipolygon", "geomcollection")) {
            assertRefused(column(geometry, geometry), MappingUnsupportedReason.GEOMETRY,
                    "TP §6.3: geometry and its subtypes are Unsupported");
        }
        assertRefused(column("vector", "vector(2048)"), MappingUnsupportedReason.VECTOR,
                "TP §6.3: VECTOR is Unsupported (MySQL 9.0+; a MySQL 8.0 server never reports it)");
        for (String outside : List.of("uuid", "nchar", "long varchar", "geometrycollection")) {
            assertRefused(column(outside, outside), MappingUnsupportedReason.NOT_WHITELISTED,
                    "TP §6.3: every type outside the whitelist is Unsupported");
        }
    }

    @Test
    void contradictoryOrMissingFactsAreRefusedNotGuessed() {
        for (SourceColumns contradictory : List.of(
                column("varchar", "varchar(16)").characterLength(16, 64),
                column("varchar", "varchar(16)").characterLength(32, 128).charset(UTF8MB4, UTF8MB4_CI),
                column("varchar", "varchar(16)").characterLength(16, 16).charset("binary", "utf8mb4_bin"),
                column("varchar", "varchar(16)").characterLength(16, 16).charset(UTF8MB4, "binary"),
                column("char", "char(10) unsigned").characterLength(10, 40).charset(UTF8MB4, UTF8MB4_CI),
                column("text", "text(10,2)").precision(10, 2).charset(UTF8MB4, UTF8MB4_CI),
                column("varbinary", "varbinary(255)").characterLength(255, 255).charset(UTF8MB4, UTF8MB4_CI),
                column("enum", "enum").charset(UTF8MB4, UTF8MB4_CI),
                column("enum", "enum('a''").charset(UTF8MB4, UTF8MB4_CI),
                column("set", "set('a' 'b')").charset(UTF8MB4, UTF8MB4_CI),
                column("json", "json unsigned"))) {
            assertRefused(contradictory, MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT,
                    "TP §6.1: the decision is a function of the facts; contradictory or missing facts are refused");
        }
        assertRefused(column("char", "char(0)").characterLength(0, 0).charset(UTF8MB4, UTF8MB4_CI),
                MappingUnsupportedReason.CHARACTER_LENGTH_ZERO,
                "TP §6.3 keeps M exactly and PostgreSQL has no char(0)");
    }

    /**
     * TP §6.1, §15.1: an alternative may only widen the default's value domain. Over generated columns,
     * every decision that offers alternatives is checked against generated values: a value the default
     * target accepts must be accepted by each alternative. The domains are modelled here, independently
     * of the implementation, from PostgreSQL 15's input rules.
     */
    @Test
    void everyAlternativeWidensTheDefaultValueDomain() {
        Random random = new Random(SEED);
        List<Value> values = values(random);
        int subjects = 0;
        for (SourceColumn column : generatedColumns(random)) {
            for (MappingOptions options : ALL_OPTIONS) {
                if (!(PAIR.map(column, options) instanceof Supported decision) || decision.alternatives().isEmpty()) {
                    continue;
                }
                subjects++;
                List<Value> defaultDomain = values.stream().filter(v -> accepts(decision.targetType(), v)).toList();
                assertFalse(defaultDomain.isEmpty(), "the generated values must exercise the default " + decision);
                for (MappingAlternative alternative : decision.alternatives()) {
                    for (Value value : defaultDomain) {
                        assertTrue(accepts(alternative.targetType(), value),
                                "TP §6.1: an alternative may only widen the default's value domain, never narrow it. "
                                        + column.columnType() + " defaults to " + decision.targetType()
                                        + ", which accepts " + value + ", but the alternative "
                                        + alternative.targetType() + " rejects it");
                    }
                }
            }
        }
        assertTrue(subjects > 0, "the widening property needs subjects: JSON offers alternatives");
    }

    /** The domain model is not vacuous: it sees the narrowings a careless alternative would introduce. */
    @Test
    void theValueDomainModelDetectsANarrowing() {
        List<Value> values = values(new Random(SEED));
        TargetType json = new TargetType(TargetTypeName.JSON, List.of());
        TargetType jsonb = new TargetType(TargetTypeName.JSONB, List.of());
        TargetType text = new TargetType(TargetTypeName.TEXT, List.of());
        TargetType varchar1 = new TargetType(TargetTypeName.VARCHAR, List.of(1));
        assertTrue(values.stream().anyMatch(v -> accepts(json, v) && !accepts(jsonb, v)),
                "jsonb rejects a U+0000 escape that json accepts, so jsonb is narrower than json");
        assertTrue(values.stream().anyMatch(v -> accepts(text, v) && !accepts(varchar1, v)),
                "varchar(1) is narrower than text");
    }

    // PostgreSQL 15 input domains, for the value classes the generator produces.
    private static boolean accepts(TargetType type, Value value) {
        return switch (type.name()) {
            case TEXT -> value.text().indexOf(0) < 0;
            case CHAR, VARCHAR -> value.text().indexOf(0) < 0
                    && value.text().codePointCount(0, value.text().length()) <= type.modifiers().get(0);
            case JSON -> value.json();
            case JSONB -> value.json() && !value.nulEscape();
            case BYTEA -> true;
            default -> throw new AssertionError("no value-domain model for " + type
                    + "; model its PostgreSQL input domain here before a mapping offers alternatives with it");
        };
    }

    /** A source value as text, whether it is a JSON document, and whether one of its strings holds U+0000. */
    private record Value(String text, boolean json, boolean nulEscape) {
    }

    private static List<Value> values(Random random) {
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            boolean[] nul = new boolean[1];
            values.add(new Value(jsonDocument(random, 0, nul), true, nul[0]));
        }
        String[] pieces = {"a", " ", "\"", "{", "中文", new String(Character.toChars(0x1F600)), String.valueOf((char) 0)};
        for (int i = 0; i < 500; i++) {
            StringBuilder text = new StringBuilder();
            for (int n = random.nextInt(6); n > 0; n--) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            values.add(new Value(text.toString(), false, false));
        }
        return values;
    }

    private static String jsonDocument(Random random, int depth, boolean[] nul) {
        int kind = random.nextInt(depth >= 3 ? 3 : 5);
        return switch (kind) {
            case 0 -> jsonString(random, nul);
            case 1 -> List.of("0", "-1.5e3", "18446744073709551615", "3.141592653589793").get(random.nextInt(4));
            case 2 -> List.of("true", "false", "null").get(random.nextInt(3));
            case 3 -> {
                StringBuilder array = new StringBuilder("[");
                for (int n = random.nextInt(4); n > 0; n--) {
                    array.append(jsonDocument(random, depth + 1, nul)).append(n > 1 ? "," : "");
                }
                yield array.append(']').toString();
            }
            default -> {
                StringBuilder object = new StringBuilder("{");
                for (int n = random.nextInt(4); n > 0; n--) {
                    object.append(jsonString(random, nul)).append(':').append(jsonDocument(random, depth + 1, nul))
                            .append(n > 1 ? "," : "");
                }
                yield object.append('}').toString();
            }
        };
    }

    private static String jsonString(Random random, boolean[] nul) {
        String nulEscape = "\\" + "u" + "0000";
        String[] pieces = {"k", "中文", new String(Character.toChars(0x1F600)), "\\\"", "\\n", nulEscape};
        StringBuilder string = new StringBuilder("\"");
        for (int n = random.nextInt(4); n > 0; n--) {
            String piece = pieces[random.nextInt(pieces.length)];
            nul[0] |= piece.equals(nulEscape);
            string.append(piece);
        }
        return string.append('"').toString();
    }

    private static List<SourceColumn> generatedColumns(Random random) {
        List<String> types = List.of("char", "varchar", "binary", "varbinary", "tinytext", "text", "mediumtext",
                "longtext", "tinyblob", "blob", "mediumblob", "longblob", "enum", "set", "json");
        List<String> suffixes = List.of("", "(1)", "(16)", "(255)", "('a','b')", " unsigned");
        List<Optional<String>> charsets = List.of(Optional.empty(), Optional.of(UTF8MB4), Optional.of("binary"));
        List<Optional<String>> collations = List.of(Optional.empty(), Optional.of("utf8mb4_bin"), Optional.of("binary"));
        List<SourceColumn> columns = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            String type = types.get(random.nextInt(types.size()));
            String columnType = type + suffixes.get(random.nextInt(suffixes.size()));
            long length = List.of(1L, 16L, 255L).get(random.nextInt(3));
            SourceColumn built = column(type, columnType).characterLength(length, length * 4).build();
            columns.add(new SourceColumn(built.coordinate(), built.dataType(), built.columnType(), built.unsigned(),
                    built.characterMaximumLength(), built.characterOctetLength(), built.numericPrecision(),
                    built.numericScale(), built.datetimePrecision(), charsets.get(random.nextInt(charsets.size())),
                    collations.get(random.nextInt(collations.size())), built.nullability(), built.columnDefault(),
                    built.extra(), built.ordinalPosition()));
        }
        return columns;
    }

    private static List<SourceColumns> binaryCharacterColumns() {
        return List.of(
                column("char", "char(4)").characterLength(4, 4).charset("binary", "binary"),
                column("varchar", "varchar(16)").characterLength(16, 16).charset("binary", "binary"),
                column("tinytext", "tinytext").characterLength(255, 255).charset("binary", "binary"),
                column("longtext", "longtext").characterLength(4294967295L, 4294967295L).charset("binary", "binary"),
                column("enum", "enum('a','b')").characterLength(1, 1).charset("binary", "binary"),
                column("set", "set('a','b')").characterLength(3, 3).charset("binary", "binary"));
    }

    private static void assertRefused(SourceColumns column, MappingUnsupportedReason reason, String message) {
        SourceColumn facts = column.build();
        Unsupported refused = assertInstanceOf(Unsupported.class, PAIR.map(facts, OFF), message + ": " + facts.columnType());
        assertSame(reason, refused.reason(), message + ": " + facts.columnType());
        assertEquals(facts, refused.evidence(), "the refusal carries the facts it was decided from");
    }

    private static Supported supported(SourceColumns column) {
        return supported(column, OFF);
    }

    private static Supported supported(SourceColumns column, MappingOptions options) {
        return assertInstanceOf(Supported.class, PAIR.map(column.build(), options),
                "expected a supported decision for " + column.build().columnType());
    }
}
