package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.dialect.pair.MySql80ToPostgres15;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code pair.mapIdentifier} and central quoting, driven through {@code dialect.api} with values (TP §7.1;
 * {@code docs/spec/dialect.md} obligations 5 and 14). Pinned {@code hash12} values were computed
 * independently in Python from TP §7.1's definition, not from the implementation.
 */
class IdentifierMappingContractTest {

    /** Reached directly until slice 2's {@code catalog.select} hands it out. */
    private static final DatabasePair PAIR = MySql80ToPostgres15.INSTANCE;

    private static final Pattern RENAMED_SHAPE = Pattern.compile("(.+)_([0-9a-f]{12})", Pattern.DOTALL);

    private static IdentifierMapping map(SourceCoordinate coordinate) {
        return PAIR.mapIdentifier(coordinate, Optional.empty());
    }

    private static int utf8Bytes(String name) {
        return name.getBytes(StandardCharsets.UTF_8).length;
    }

    private static IdentifierMapping.Renamed renamed(SourceCoordinate coordinate) {
        return assertInstanceOf(IdentifierMapping.Renamed.class, map(coordinate),
                "TP §7.1: an overlong schema or table name is renamed: " + coordinate);
    }

    // --- Exact -----------------------------------------------------------------------------------

    @Test
    void aNameWithinTheLimitIsExactAndKeptCharacterForCharacter() {
        List<String> names = List.of("orders", "订单明细", "Mixed Case", "select", "a\"b", "back\\slash",
                "new\nline", " padded ", "emoji😀", "x".repeat(63), "订".repeat(21));
        for (String name : names) {
            List<SourceCoordinate> coordinates = List.of(
                    new SchemaCoordinate(name), new TableCoordinate("shop", name), new ColumnCoordinate("shop", "t", name));
            for (SourceCoordinate coordinate : coordinates) {
                IdentifierMapping.Exact exact = assertInstanceOf(IdentifierMapping.Exact.class, map(coordinate),
                        "TP §7.1: a name of at most 63 UTF-8 bytes is kept exactly: " + coordinate);
                assertEquals(name, exact.target().name(), "TP §7.1: names are preserved character for character");
                assertEquals(coordinate, exact.source(), "the result carries the coordinate it decided");
            }
        }
    }

    // --- Bytes, not characters -------------------------------------------------------------------

    @Test
    void theLimitIsCountedInUtf8BytesNotCharacters() {
        String fits = "订".repeat(21);
        String overflows = "订".repeat(22);
        assertEquals(63, utf8Bytes(fits));
        assertEquals(22, overflows.length(), "22 characters: far under 63 if characters were counted");

        assertInstanceOf(IdentifierMapping.Exact.class, map(new TableCoordinate("shop", fits)),
                "TP §7.1: 63 bytes is within PostgreSQL's limit");
        IdentifierMapping overlong = map(new TableCoordinate("shop", overflows));
        assertInstanceOf(IdentifierMapping.Renamed.class, overlong,
                "TP §7.1: PostgreSQL's 63-byte identifier limit is in UTF-8 bytes; a 22-character Chinese table name "
                        + "is 66 bytes and must be renamed, was " + overlong);
        assertInstanceOf(Unsupported.class, map(new ColumnCoordinate("shop", "t", overflows)),
                "TP §7.1: a 22-character Chinese column name is 66 bytes and overlong");
    }

    // --- The rename ------------------------------------------------------------------------------

    @Test
    void anOverlongTableNameBecomesPrefixUnderscoreHash12() {
        TableCoordinate source = new TableCoordinate("shop", "订单明细".repeat(6));
        IdentifierMapping.Renamed renamed = renamed(source);

        assertEquals("订单明细订单明细订单明细订单明细_62835ddc9e03", renamed.target().name(),
                "TP §7.1: <utf8-prefix>_<hash12>, hash12 = first 12 lowercase hex of SHA-256 over the "
                        + "length-prefixed UTF-8 database and table names");
        assertEquals(source, renamed.source(), "TP §7.1: a Renamed carries the full source coordinate");
        assertEquals(new RenameAlgorithmVersion(1), renamed.algorithmVersion(),
                "TP §7.1: a Renamed carries the algorithm version that produced it");
    }

    @Test
    void anOverlongSchemaNameIsRenamedFromTheDatabaseAlone() {
        IdentifierMapping.Renamed renamed = renamed(new SchemaCoordinate("库".repeat(22)));
        assertEquals("库库库库库库库库库库库库库库库库_279d70ebe3e1", renamed.target().name(),
                "TP §7.1: a schema's hash12 covers the length-prefixed UTF-8 database name");
    }

    @Test
    void aRenamedNameUsesTheWholeByteBudgetAndNeverExceedsIt() {
        IdentifierMapping.Renamed renamed = renamed(new TableCoordinate("shop", "x".repeat(64)));
        assertEquals("x".repeat(50) + "_f29180b60d5c", renamed.target().name(),
                "TP §7.1: the prefix is as long as fits, so the whole name is exactly 63 bytes for ASCII");
        for (String name : List.of("x".repeat(64), "订".repeat(200), "é".repeat(40), "😀".repeat(30),
                "a" + "订".repeat(21), "ab" + "😀".repeat(20), "\"".repeat(64))) {
            String target = renamed(new TableCoordinate("shop", name)).target().name();
            assertTrue(utf8Bytes(target) <= 63, "TP §7.1: a renamed name is at most 63 bytes: " + target);
            assertTrue(RENAMED_SHAPE.matcher(target).matches(), "TP §7.1: <utf8-prefix>_<hash12>: " + target);
        }
    }

    @Test
    void truncationNeverSplitsACodePoint() {
        // 'a' + 21 × '订' is 64 bytes. The 50-byte prefix budget ends at byte 50, which is inside the
        // 17th character (bytes 49..51), so the cut must fall back to the 16th.
        String name = "a" + "订".repeat(21);
        String target = renamed(new TableCoordinate("shop", name)).target().name();
        assertEquals("a" + "订".repeat(16) + "_136d81d73c7b", target,
                "TP §7.1: the prefix is truncated only at a UTF-8 code-point boundary");

        for (String hostile : List.of(name, "ab" + "😀".repeat(20), "😀".repeat(16), "é".repeat(40))) {
            String renamedName = renamed(new TableCoordinate("shop", hostile)).target().name();
            var matcher = RENAMED_SHAPE.matcher(renamedName);
            assertTrue(matcher.matches(), renamedName);
            String prefix = matcher.group(1);
            assertTrue(hostile.startsWith(prefix), "TP §7.1: the prefix is a whole-code-point prefix of the source "
                    + "name, never a split character: " + renamedName);
            assertFalse(prefix.contains("�"), "a split character decodes to U+FFFD: " + renamedName);
            assertFalse(Character.isHighSurrogate(prefix.charAt(prefix.length() - 1)),
                    "a supplementary character is never cut between its surrogates: " + renamedName);
        }
    }

    // --- Determinism -----------------------------------------------------------------------------

    @Test
    void theSameCoordinateAlwaysYieldsTheSameName() {
        TableCoordinate source = new TableCoordinate("shop", "订单明细".repeat(6));
        IdentifierMapping first = map(source);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, map(new TableCoordinate("shop", "订单明细".repeat(6))),
                    "TP §7.1: the same source coordinate yields the same target name on every call");
        }
    }

    @Test
    void coordinatesThatShareAPrefixDoNotCollide() {
        String sharedPrefix = "x".repeat(64);
        List<SourceCoordinate> coordinates = List.of(
                new TableCoordinate("shop", sharedPrefix),
                new TableCoordinate("crm", sharedPrefix),
                new TableCoordinate("shop", sharedPrefix + "y"),
                new TableCoordinate("shop.x", sharedPrefix),
                // Without length prefixes these two would hash the same concatenated bytes.
                new TableCoordinate("ab", "c" + sharedPrefix),
                new TableCoordinate("a", "bc" + sharedPrefix),
                new SchemaCoordinate(sharedPrefix));
        List<String> targets = coordinates.stream().map(c -> renamed(c).target().name()).toList();
        assertEquals("x".repeat(50) + "_81665c716661", targets.get(1),
                "TP §7.1: the database takes part in a table's hash12");
        assertEquals(targets.size(), targets.stream().distinct().count(),
                "TP §7.1: different coordinates sharing a prefix get different names: " + targets);
    }

    // --- Columns ---------------------------------------------------------------------------------

    @Test
    void anOverlongColumnNameIsUnsupportedWithAStableReasonAndNeverRenamed() {
        ColumnCoordinate column = new ColumnCoordinate("shop", "t", "x".repeat(64));
        Unsupported unsupported = assertInstanceOf(Unsupported.class, map(column),
                "TP §7.1: the Sink must address the exact Connect field, so an overlong column is never renamed");
        assertEquals(IdentifierUnsupportedReason.COLUMN_NAME_OVER_63_BYTES, unsupported.reason());
        assertEquals("COLUMN_NAME_OVER_63_BYTES", ((Enum<?>) unsupported.reason()).name(),
                "the reason code is stable: diagnosis keys on it");
        assertEquals(column, unsupported.evidence(), "the refusal carries the column it refused");
    }

    // --- Nothing is inferred ---------------------------------------------------------------------

    @Test
    void namesComeOnlyFromTypedCoordinatesAndNothingIsParsedOutOfThem() {
        Method[] mapIdentifier = Arrays.stream(DatabasePair.class.getMethods())
                .filter(m -> m.getName().equals("mapIdentifier")).toArray(Method[]::new);
        assertEquals(1, mapIdentifier.length, "ADR-0008 §Contract: no String overload a topic name could reach");
        assertEquals(List.of(SourceCoordinate.class, Optional.class), List.of(mapIdentifier[0].getParameterTypes()),
                "ADR-0008 §Contract: an identifier is decided from a typed source coordinate only");

        for (String topicOrConnectorLike : List.of("dbx.run-1.shop.orders", "dbx-source-shop-orders",
                "ERROR: relation \"orders\" does not exist")) {
            IdentifierMapping.Exact exact = assertInstanceOf(IdentifierMapping.Exact.class,
                    map(new TableCoordinate("shop", topicOrConnectorLike)));
            assertEquals(topicOrConnectorLike, exact.target().name(),
                    "TP §7.1: a name that looks like a topic, connector or error is a name, never parsed");
        }
    }

    @Test
    void aMappingRuleIsRefusedLoudlyRatherThanIgnored() {
        TableCoordinate source = new TableCoordinate("shop", "orders");
        MappingRule rule = new MappingRule.TableRename(source, new TargetIdentifier("orders_v2"),
                MappingRule.RuleOrigin.USER);
        assertThrows(UnsupportedOperationException.class, () -> PAIR.mapIdentifier(source, Optional.of(rule)),
                "ADR-0008 §Ownership: a rule this slice does not apply must fail, not be silently ignored");
    }

    // --- Quoting ---------------------------------------------------------------------------------

    @Test
    void identifiersAreAlwaysDoubleQuotedAndSurviveHostileCharacters() {
        assertEquals("\"select\"", new TargetIdentifier("select").quoted(),
                "TP §7.1: quoting is mandatory, so a reserved word needs no special path");
        assertEquals("\"orders\"", new TargetIdentifier("orders").quoted(), "TP §7.1: always quoted");
        assertEquals("\"a\"\"b\"", new TargetIdentifier("a\"b").quoted(), "an embedded quote is doubled");
        assertEquals("\"back\\slash\"", new TargetIdentifier("back\\slash").quoted(),
                "a backslash is literal inside a quoted identifier");
        assertEquals("\"new\nline\"", new TargetIdentifier("new\nline").quoted(),
                "a newline is literal inside a quoted identifier");

        for (String name : List.of("a\"b", "a\"\"b", "\"", "x\"; DROP TABLE t; --", "back\\slash\\", "new\nline",
                "?", "`tick`", "'single'", "订单明细", "emoji😀", " padded ")) {
            String quoted = new TargetIdentifier(name).quoted();
            assertEquals(name, unquote(quoted), "TP §7.1: quoting round-trips exactly: " + quoted);
            assertNotEquals(new TargetIdentifier(name + "\"").quoted(), quoted);
        }
    }

    @Test
    void aQuotedHostileIdentifierAndAHostileValueCannotChangeTheStatement() {
        TargetIdentifier table = new TargetIdentifier("t\"; DELETE FROM x WHERE ? --\n");
        TargetIdentifier column = new TargetIdentifier("c?\\");
        SqlValue value = new SqlValue.Text("'); DROP TABLE t; -- ?\"\\\n");
        String sql = "SELECT " + column.quoted() + " FROM " + table.quoted() + " WHERE " + column.quoted() + " = ?";

        ParameterizedStatement statement = new ParameterizedStatement(sql, List.of(value));

        assertEquals(List.of(value), statement.parameters(),
                "ADR-0008 §Plans: the only placeholder is the bound one; quoted identifiers hide theirs");
        assertFalse(statement.sql().contains("DROP TABLE"), "ADR-0008 §Plans: a value is bound, never interpolated");
    }

    /** An independent reader of a PostgreSQL quoted identifier, so the test does not trust {@code quoted()}. */
    private static String unquote(String quoted) {
        assertTrue(quoted.length() >= 2 && quoted.startsWith("\"") && quoted.endsWith("\""), quoted);
        StringBuilder name = new StringBuilder();
        for (int i = 1; i < quoted.length() - 1; i++) {
            char c = quoted.charAt(i);
            if (c == '"') {
                assertTrue(i + 1 < quoted.length() - 1 && quoted.charAt(i + 1) == '"',
                        "a quote inside a quoted identifier must be doubled: " + quoted);
                i++;
            }
            name.append(c);
        }
        return name.toString();
    }
}
