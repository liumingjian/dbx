package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code source.baselinePlan} (obligation 19; ticket #136): the source baseline (源基线) of one table as
 * one bounded aggregate query — the exact {@code COUNT(*)} and, when the table has a keyset column
 * (键集列), that column's terminal values (ADR-0037 §The keyset column; TP §4 step 8). Reached only
 * through {@code DialectCatalog.compileTime().select(...)}.
 *
 * <p>Not provable at L1 and deliberately not approximated here: that the rendered SQL runs, and that the
 * count it reads is a boundary — the latter needs the write freeze, which is {@code workflow}'s, and the
 * former is {@code docs/spec/dialect.md} §Verification's L2 ({@code contract} slice 8 through
 * {@code gateway}). Whether the minimum is 0 or more and the maximum at most 2^63-1 is
 * {@code validation}'s evaluation (validation obligation 22) and is asserted there, not here.
 */
class BaselinePlanContractTest {

    private static final SourceDialect SOURCE = MappingCase.PAIR.source();
    private static final TableCoordinate ORDERS = new TableCoordinate("shop", "订单");

    // --- Fixture: a table as information_schema reports it ----------------------------------------

    private record Col(String name, String columnType, Nullability nullability) {
    }

    private static Col notNull(String name, String columnType) {
        return new Col(name, columnType, Nullability.NOT_NULL);
    }

    private static Col nullable(String name, String columnType) {
        return new Col(name, columnType, Nullability.NULLABLE);
    }

    private static ColumnCoordinate at(TableCoordinate table, String column) {
        return new ColumnCoordinate(table.database(), table.table(), column);
    }

    private static ColumnCoordinate at(String column) {
        return at(ORDERS, column);
    }

    private static SourceIndex unique(TableCoordinate table, String name, String column) {
        return new SourceIndex(name, true, true, SourceIndex.IndexType.BTREE, List.of(new SourceIndex.KeyPart(
                1, new SourceIndex.Subject.Column(at(table, column)), OptionalLong.empty(),
                SourceIndex.Direction.ASCENDING)));
    }

    private static SourceTableMetadata table(TableCoordinate coordinate, List<Col> cols, SourceIndex... indexes) {
        List<SourceColumn> columns = new ArrayList<>();
        List<ColumnComment> comments = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Col col = cols.get(i);
            String dataType = col.columnType().split("[( ]")[0];
            boolean integer = dataType.matches("(tiny|small|medium|big)?int");
            columns.add(new SourceColumn(at(coordinate, col.name()), dataType, col.columnType(),
                    col.columnType().contains("unsigned"), OptionalLong.empty(), OptionalLong.empty(),
                    integer ? OptionalInt.of(20) : OptionalInt.empty(), integer ? OptionalInt.of(0) : OptionalInt.empty(),
                    OptionalInt.empty(), Optional.empty(), Optional.empty(), col.nullability(), Optional.empty(), "",
                    i + 1));
            comments.add(new ColumnComment(at(coordinate, col.name()), ""));
        }
        return new SourceTableMetadata(coordinate, columns, comments, List.of(indexes), List.of(), "",
                Optional.empty(), new TableStatistics(OptionalLong.of(1000), OptionalLong.of(128),
                        OptionalLong.of(16384), OptionalLong.of(2048)));
    }

    /** The representative table: a keyset candidate, a second unique integer column, and a payload. */
    private static SourceTableMetadata orders() {
        return table(ORDERS, List.of(
                notNull("id", "bigint unsigned"),
                notNull("code", "int"),
                nullable("payload", "longblob")),
                unique(ORDERS, SourceIndex.PRIMARY, "id"), unique(ORDERS, "u_code", "code"));
    }

    private static SqlPlan keyed() {
        return SOURCE.baselinePlan(orders(), Optional.of(new KeysetColumn(at("id"))));
    }

    private static SqlPlan unkeyed() {
        return SOURCE.baselinePlan(orders(), Optional.empty());
    }

    private static String sql(SqlPlan plan) {
        assertEquals(1, plan.statements().size(),
                "obligation 19: a table's baseline is one bounded query, never one query per fact");
        return plan.statements().get(0).sql();
    }

    private static List<String> labels(SqlPlan plan) {
        return plan.resultSchema().columns().stream().map(ResultSchema.Column::label).toList();
    }

    // --- Plan shape (ADR-0008 §Plans; ADR-0037) ----------------------------------------------------

    @Test
    void theBaselineIsOneExactScanNeedingOnlySelect() {
        for (SqlPlan plan : List.of(keyed(), unkeyed())) {
            assertEquals(OperationKind.SOURCE_BASELINE_READ, plan.operationKind(),
                    "obligation 19: the baseline is a source baseline read");
            assertEquals(TimeoutClass.EXACT_SCAN, plan.timeoutClass(),
                    "ADR-0037; TP §4 step 8: the baseline count is exact, never estimated from statistics");
            assertEquals(Set.of(RequiredPrivilege.SOURCE_SELECT), plan.requiredPrivileges(),
                    "ADR-0006: counting rows needs SELECT and nothing more");
            assertEquals(EvidencePolicy.STATEMENT_AND_AGGREGATES, plan.evidencePolicy(),
                    "ADR-0028: aggregates are evidence, row values never are");
            assertEquals(ResultSchema.Cardinality.EXACTLY_ONE_ROW, plan.resultSchema().cardinality(),
                    "obligation 19: one aggregate query over one table returns exactly one row");
            assertEquals(List.of(), plan.statements().get(0).parameters(),
                    "ADR-0008 §Plans: the baseline binds no value — it reads facts, it compares against none");
        }
    }

    /** The typed result the keyed baseline returns, column by column, in plan order. */
    private static final List<ResultSchema.Column> EXPECTED_COLUMNS = List.of(
            new ResultSchema.Column("row_count", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("keyset_min", "decimal(20,0)", Nullability.NULLABLE),
            new ResultSchema.Column("keyset_max", "decimal(20,0)", Nullability.NULLABLE));

    @Test
    void theResultIsTypedColumnByColumnWithStableLabels() {
        assertEquals(new ResultSchema(EXPECTED_COLUMNS, ResultSchema.Cardinality.EXACTLY_ONE_ROW),
                keyed().resultSchema(),
                "ADR-0008 §Plans: the result is typed column by column, and every label comes from a fixed "
                        + "scheme, never from a column name");
        for (ResultSchema.Column column : keyed().resultSchema().columns()) {
            assertTrue(column.label().matches("[a-z_]+"),
                    "obligation 19: a label is ASCII letters and underscores, so it is legal everywhere a "
                            + "result column is addressed: " + column.label());
        }
    }

    @Test
    void theCountIsExactAndAnswersOnAnEmptyTableWhileTheExtremaDoNot() {
        Map<String, ResultSchema.Column> byLabel = new TreeMap<>();
        keyed().resultSchema().columns().forEach(column -> byLabel.put(column.label(), column));

        assertEquals(Nullability.NOT_NULL, byLabel.get("row_count").nullability(),
                "TP §4 step 8: COUNT(*) answers on an empty table too, so the count is never NULL");
        assertEquals("bigint", byLabel.get("row_count").databaseType(),
                "TP §4 step 8: a MySQL COUNT(*) is a bigint");
        for (String label : List.of("keyset_min", "keyset_max")) {
            assertEquals(Nullability.NULLABLE, byLabel.get(label).nullability(),
                    "ADR-0037: an empty table has no terminal value, so " + label + " is honestly NULLABLE");
        }
    }

    @Test
    void theKeysetExtremaAreDeclaredWideEnoughNotToWrap() {
        Map<String, String> byLabel = new TreeMap<>();
        keyed().resultSchema().columns().forEach(column -> byLabel.put(column.label(), column.databaseType()));

        for (String label : List.of("keyset_min", "keyset_max")) {
            assertEquals("decimal(20,0)", byLabel.get(label),
                    "ADR-0037 §The keyset column: a BIGINT UNSIGNED terminal value read as a signed 64-bit "
                            + "integer wraps 2^64-1 to -1, so " + label + " is declared decimal(20,0)");
        }
    }

    // --- Statement text (obligation 19) ------------------------------------------------------------

    /** Written by hand from ticket #136, not copied from the implementation's output. */
    private static final String EXPECTED_KEYED = "SELECT COUNT(*) AS `row_count`, "
            + "MIN(`id`) AS `keyset_min`, MAX(`id`) AS `keyset_max` FROM `shop`.`订单`";

    /** Written by hand from ticket #136: without a keyset column the count is the whole baseline. */
    private static final String EXPECTED_UNKEYED = "SELECT COUNT(*) AS `row_count` FROM `shop`.`订单`";

    @Test
    void theStatementTextIsPinnedWithAndWithoutAKeysetColumn() {
        assertEquals(EXPECTED_KEYED, sql(keyed()),
                "obligation 19: one query carries the exact count and both terminal values of the keyset column");
        assertEquals(EXPECTED_UNKEYED, sql(unkeyed()),
                "validation obligation 23: a table without a keyset column has a baseline of the count alone");
    }

    @Test
    void aTableWithoutAKeysetColumnDeclaresExactlyOneResultColumn() {
        SqlPlan plan = unkeyed();

        assertEquals(1, plan.resultSchema().columns().size(),
                "validation obligation 23: drift compares only the count for such a table, because its baseline "
                        + "holds only the count, so the plan must not invent a pair of always-NULL columns");
        assertEquals(List.of("row_count"), labels(plan),
                "ADR-0037: no keyset column means no terminal values, not NULL ones");
        assertFalse(sql(plan).contains("MIN(") || sql(plan).contains("MAX("),
                "validation obligation 23: no extremum is planned for a table that has no keyset column: "
                        + sql(plan));
    }

    /**
     * Obligation 19 and obligation 17 read the same range, so they must render it identically: ticket #135's
     * keyset extrema are called, never re-derived. Compared through the two plans' own statement text, so the
     * test reaches no package-private helper.
     */
    @Test
    void theExtremaRenderingIsTheOneObligation17Uses() {
        SourceTableMetadata orders = orders();
        String scan = SOURCE.preflightScanPlan(orders,
                List.of(new ApprovedColumn(at("id"), new TargetIdentifier("id"))), List.of())
                .statements().get(0).sql();

        assertEquals(List.of(at("id"), at("code")),
                SOURCE.keysetCandidates(orders).stream().map(KeysetCandidate::column).toList(),
                "the fixture must make `id` the first keyset candidate, so keyset_min_1 is its minimum");
        assertEquals(expressionBefore(scan, "`keyset_min_1`"), expressionBefore(sql(keyed()), "`keyset_min`"),
                "obligation 19: the baseline renders the keyset minimum exactly as obligation 17's scan does, "
                        + "so a baseline and a preflight of the same column cannot disagree");
        assertEquals(expressionBefore(scan, "`keyset_max_1`"), expressionBefore(sql(keyed()), "`keyset_max`"),
                "obligation 19: the same for the maximum");
    }

    /** The select item's expression: everything between the previous {@code ", "} and {@code " AS <alias>"}. */
    private static String expressionBefore(String sql, String alias) {
        int aliasAt = sql.indexOf(" AS " + alias);
        assertTrue(aliasAt > 0, "the statement must carry a select item aliased " + alias + ": " + sql);
        int previous = sql.lastIndexOf(", ", aliasAt);
        return sql.substring(previous < 0 ? "SELECT ".length() : previous + 2, aliasAt);
    }

    @Test
    void theBaselineGradesNothingAndKnowsNoOccasion() {
        String statement = sql(keyed());

        assertFalse(statement.contains("WHERE") || statement.contains(">=") || statement.contains("<="),
                "validation obligation 22: whether the minimum is 0 or more and the maximum at most 2^63-1 is "
                        + "validation's evaluation, so the baseline filters and compares nothing: " + statement);
        assertFalse(statement.contains("9223372036854775807"),
                "validation obligation 22: the signed 64-bit boundary is validation's constant, never a "
                        + "literal in this plan: " + statement);
        assertEquals(keyed().fingerprint(), keyed().fingerprint(),
                "validation obligation 23: the same shape serves driftPlan, so dialect knows nothing of the "
                        + "运行前 and 收口 occasions — one plan, read twice");
        assertEquals(keyed(), SOURCE.baselinePlan(orders(), Optional.of(new KeysetColumn(at("id")))),
                "ADR-0008 §Plans: equal inputs are an equal plan, whichever occasion asks for it");
    }

    // --- Refusals (obligation 19) ------------------------------------------------------------------

    @Test
    void aKeysetColumnThatIsNotInTheTableIsRefusedNamingTheCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.baselinePlan(orders(), Optional.of(new KeysetColumn(at("ghost")))),
                "ADR-0037 §The keyset column: a keyset column the table does not have is a caller error");
        assertTrue(failure.getMessage().contains("ghost"),
                "the refusal names the coordinate the caller got wrong: " + failure.getMessage());
    }

    @Test
    void aKeysetColumnOfAnotherTableIsRefusedNamingTheCoordinate() {
        ColumnCoordinate foreign = at(new TableCoordinate("shop", "customers"), "id");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.baselinePlan(orders(), Optional.of(new KeysetColumn(foreign))),
                "ADR-0037 §The keyset column: a column of another table cannot be this table's keyset column");
        assertTrue(failure.getMessage().contains("customers"),
                "the refusal names the coordinate the caller got wrong: " + failure.getMessage());
    }

    @Test
    void aNonIntegerOrNullableKeysetColumnIsStillPlannedBecauseGradingIsNotHere() {
        SqlPlan plan = SOURCE.baselinePlan(orders(), Optional.of(new KeysetColumn(at("payload"))));

        assertEquals("SELECT COUNT(*) AS `row_count`, MIN(`payload`) AS `keyset_min`, "
                        + "MAX(`payload`) AS `keyset_max` FROM `shop`.`订单`", sql(plan),
                "ADR-0037 §The keyset column: which column qualifies is preflight's and validation's finding "
                        + "(obligations 18 and 22); this plan measures the column the caller approved");
    }

    // --- Fingerprints (ADR-0008 §Plans) ------------------------------------------------------------

    @Test
    void distinctInputsGiveDistinctFingerprints() {
        TableCoordinate renamed = new TableCoordinate("shop", "orders");
        TableCoordinate elsewhere = new TableCoordinate("shop2", "订单");
        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "the database", SOURCE.baselinePlan(
                        table(elsewhere, List.of(notNull("id", "bigint unsigned"))),
                        Optional.of(new KeysetColumn(at(elsewhere, "id")))),
                "the table name", SOURCE.baselinePlan(
                        table(renamed, List.of(notNull("id", "bigint unsigned"))),
                        Optional.of(new KeysetColumn(at(renamed, "id")))),
                "the keyset column", SOURCE.baselinePlan(orders(), Optional.of(new KeysetColumn(at("code")))),
                "the keyset column removed", unkeyed()));

        assertEquals(keyed().fingerprint(), keyed().fingerprint(),
                "ADR-0008 §Plans: equal inputs are an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(keyed().fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only " + variant.getKey() + " must change the fingerprint");
        }
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256), with the statement text taken from {@link #EXPECTED_KEYED} and {@link #EXPECTED_UNKEYED}
     * and the schema from {@link #EXPECTED_COLUMNS}, in a separate Python script quoted on ticket #136 —
     * never copied out of a failing assertion.
     */
    @Test
    void bothRepresentativeFingerprintsArePinnedFromTheIndependentModel() {
        assertEquals("2d8674602c0577c5e8c7ff47750c65fc2c169efffcba9ac934a2c77bca7a075a",
                keyed().fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the baseline's fingerprint must not depend on the JVM, machine or run");
        assertEquals("d85b41fa7df24bf485962c4eaf83665646c1479f121648564d56030e52f60f7a",
                unkeyed().fingerprint().sha256Hex(),
                "ADR-0008 §Plans: and neither must the count-only baseline's");
    }

    // --- Hostile names (obligation 5; TP §7.1) -----------------------------------------------------

    private static final List<String> HOSTILE = List.of(
            "order`s", "``", "`", "a\"b", "'single'", "back\\slash\\", "$$body$$", "x; DROP TABLE t; --",
            "a -- b", "a /* b */", "# hash", "new\nline", "cr\rlf", "?", "t FOR UPDATE", "FLUSH",
            "adjacent￿", "订单明细", "emoji😀", " padded ", "0000-00-00", "COUNT(*)",
            "a".repeat(63), "a".repeat(64), "订".repeat(64), "é".repeat(31) + "a", "é".repeat(32));

    @Test
    void hostileNamesNeverChangeStatementStructureAndRoundTripExactly() {
        String benign = sql(hostilePlan("shop", "orders", "id"));
        for (String name : HOSTILE) {
            for (SqlPlan plan : List.of(hostilePlan(name, "orders", "id"), hostilePlan("shop", name, "id"),
                    hostilePlan("shop", "orders", name))) {
                ParameterizedStatement statement = plan.statements().get(0);

                assertEquals(skeleton(benign), skeleton(statement.sql()),
                        "ADR-0008 §Plans: a hostile name must not change the statement's structure: " + name);
                assertEquals(0, statement.parameters().size(),
                        "ADR-0008 §Plans: no placeholder, no parameter — a quoted ? is not a binding: " + name);
                assertTrue(identifiers(statement.sql()).contains(name),
                        "TP §7.1: backtick quoting round-trips the exact source name: " + name);
            }
        }
    }

    @Test
    void quotingBoundariesArePinnedLiterally() {
        assertEquals("SELECT COUNT(*) AS `row_count`, MIN(`a``b`) AS `keyset_min`, "
                        + "MAX(`a``b`) AS `keyset_max` FROM `s`.`a\"b\\c;--/*\nd`",
                sql(hostilePlan("s", "a\"b\\c;--/*\nd", "a`b")),
                "TP §7.1: MySQL quoting is not PostgreSQL's — a doubled backtick, and a double quote, backslash, "
                        + "semicolon, comment opener and newline kept literal");
        assertEquals(64, "é".repeat(32).getBytes(StandardCharsets.UTF_8).length,
                "TP §7.1: the fixture name is 64 bytes, MySQL's limit in characters and more than PostgreSQL's");
        for (String nul : List.of("nul\0name", "\0leading", "trailing\0")) {
            assertThrows(IllegalArgumentException.class, () -> hostilePlan("s", "t", nul),
                    "TP §7.1: MySQL permits no U+0000 anywhere in an identifier, so such a name is refused "
                            + "rather than quoted");
        }
    }

    /** One table with one column, used as its own keyset column, so only the name varies. */
    private static SqlPlan hostilePlan(String database, String tableName, String column) {
        TableCoordinate coordinate = new TableCoordinate(database, tableName);
        return SOURCE.baselinePlan(table(coordinate, List.of(notNull(column, "bigint"))),
                Optional.of(new KeysetColumn(at(coordinate, column))));
    }

    private static String skeleton(String sql) {
        StringBuilder outside = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '`') {
                i = endOfQuoted(sql, i);
                outside.append("<id>");
            } else {
                outside.append(c);
            }
        }
        return outside.toString();
    }

    private static List<String> identifiers(String sql) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '`') {
                int end = endOfQuoted(sql, i);
                names.add(sql.substring(i + 1, end).replace("``", "`"));
                i = end;
            }
        }
        return names;
    }

    /** Index of the closing backtick of the identifier opened at {@code open}; a doubled backtick stays inside. */
    private static int endOfQuoted(String sql, int open) {
        for (int i = open + 1; i < sql.length(); i++) {
            if (sql.charAt(i) == '`') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        throw new AssertionError("unterminated identifier in " + sql);
    }
}
