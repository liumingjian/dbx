package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code source.samplingPlan}: TP §9.3's manual deterministic sampling rendered as plans, driven through
 * {@code dialect.api} with values ({@code docs/spec/dialect.md} obligation 23; ADR-0008 §Plans; ADR-0028).
 *
 * <p>What L1 cannot prove: that either statement runs on a real MySQL 8.0, and that the seek order matches
 * the server's collation — {@code dialect.md} §Verification puts both at L2 in {@code contract} slice 8
 * through {@code gateway}. What this module never does: compute a threshold, require the primary key,
 * deduplicate keys or report the actual sample count — all {@code validation}'s (obligation 26).
 */
class SamplingContractTest {

    /** Hostile source names: quoting, comment and statement delimiters, placeholders, limits. */
    private static final List<String> HOSTILE = List.of(
            "order`s", "``", "`", "a\"b", "'single'", "back\\slash\\", "$$body$$", "x; DROP TABLE t; --",
            "a -- b", "a /* b */", "# hash", "new\nline", "cr\rlf", "?", "t FOR UPDATE", "LOCK TABLES t",
            "adjacent￿", "订单明细", "emoji😀", " padded ",
            "a".repeat(63), "a".repeat(64), "订".repeat(64), "é".repeat(31) + "a", "é".repeat(32));

    /** A backtick-quoted MySQL identifier: a doubled backtick stays inside, so the span ends at a lone one. */
    private static final Pattern QUOTED = Pattern.compile("`(?:[^`]|``)*?`(?!`)");

    private static final String SEEK_SQL =
            "SELECT `id` FROM `shop`.`订单` WHERE `id` >= ? ORDER BY `id` ASC LIMIT 1";

    private static final String ASCENDING_SQL = "SELECT `客户`, `line no` FROM `shop`.`order``s` "
            + "ORDER BY `客户` ASC, `line no` ASC LIMIT ?";

    private static final String DESCENDING_SQL = "SELECT `客户`, `line no` FROM `shop`.`order``s` "
            + "ORDER BY `客户` DESC, `line no` DESC LIMIT ?";

    private static SamplingKey.KeyColumn keyColumn(String database, String table, String column, String databaseType) {
        return new SamplingKey.KeyColumn(new ColumnCoordinate(database, table, column), databaseType);
    }

    /** The numeric single key of TP §9.3, with the thresholds {@code validation} already computed. */
    private static SamplingKey seekKey(String database, String table, String column, String databaseType,
            SqlValue... thresholds) {
        return new SamplingKey(new TableCoordinate(database, table),
                List.of(keyColumn(database, table, column, databaseType)), Optional.of(List.of(thresholds)));
    }

    /** Every other key of TP §9.3: the first and last rows in typed source key order. */
    private static SamplingKey windowKey(String database, String table, SamplingKey.KeyColumn... columns) {
        return new SamplingKey(new TableCoordinate(database, table), List.of(columns), Optional.empty());
    }

    private static SamplingKey representativeWindowKey() {
        return windowKey("shop", "order`s",
                keyColumn("shop", "order`s", "客户", "varchar"),
                keyColumn("shop", "order`s", "line no", "int"));
    }

    private static String sql(SqlPlan plan) {
        assertEquals(1, plan.statements().size(),
                "spec #134 correction 4: one statement per plan, so every plan keeps one honest cardinality");
        return plan.statements().get(0).sql();
    }

    private static List<SqlValue> parameters(SqlPlan plan) {
        return plan.statements().get(0).parameters();
    }

    /** The statement with every quoted identifier replaced, so only its SQL structure is left. */
    private static String skeleton(String sql) {
        return QUOTED.matcher(sql).replaceAll(Matcher.quoteReplacement("<id>"));
    }

    // ---- plan shape --------------------------------------------------------------------------------

    @Test
    void everySamplingPlanCarriesTheSamplingKindTimeoutAndPrivilege() {
        List<SqlPlan> plans = new ArrayList<>(PAIR.source().samplingPlan(
                seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1), new SqlValue.Int64(500_001)), 2));
        plans.addAll(PAIR.source().samplingPlan(representativeWindowKey(), 1000));

        for (SqlPlan plan : plans) {
            assertEquals(OperationKind.SOURCE_SAMPLING, plan.operationKind(),
                    "docs/spec/dialect.md obligation 23: sampling is its own operation kind");
            assertEquals(TimeoutClass.EXACT_SCAN, plan.timeoutClass(),
                    "TP §9.3: a deterministic sample is an exact read, not a catalog read or a probe");
            assertEquals(Set.of(RequiredPrivilege.SOURCE_SELECT), plan.requiredPrivileges(),
                    "ADR-0006: sampling needs SELECT on the source and nothing more");
        }
    }

    @Test
    void everyReturnedPlanPersistsNoRowValueAsEvidence() {
        List<SqlPlan> plans = new ArrayList<>(PAIR.source().samplingPlan(
                seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1), new SqlValue.Int64(2)), 2));
        plans.addAll(PAIR.source().samplingPlan(representativeWindowKey(), 1000));
        plans.addAll(PAIR.source().samplingPlan(representativeWindowKey(), 1));

        for (SqlPlan plan : plans) {
            assertEquals(EvidencePolicy.STATEMENT_ONLY, plan.evidencePolicy(),
                    "ADR-0028 §No data values: a sample reads customer rows, so no row value and no "
                            + "primary-key value may be persisted as this plan's evidence — " + sql(plan));
        }
    }

    @Test
    void theSeekResultDeclaresItsKeyColumnAndAtMostOneRow() {
        SqlPlan plan = PAIR.source()
                .samplingPlan(seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1)), 1).get(0);

        assertEquals(new ResultSchema(List.of(new ResultSchema.Column("id", "bigint", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.AT_MOST_ONE_ROW), plan.resultSchema(),
                "TP §9.3: a seek lands on at most one row, and a threshold past the last key finds none");
    }

    @Test
    void theWindowResultDeclaresEveryKeyColumnInKeyOrderAndAnyNumberOfRows() {
        for (SqlPlan plan : PAIR.source().samplingPlan(representativeWindowKey(), 1000)) {
            assertEquals(new ResultSchema(List.of(
                            new ResultSchema.Column("客户", "varchar", Nullability.NOT_NULL),
                            new ResultSchema.Column("line no", "int", Nullability.NOT_NULL)),
                            ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS), plan.resultSchema(),
                    "TP §9.3: the first and last rows come back in typed source key order, however many there are");
        }
    }

    // ---- the two strategies ------------------------------------------------------------------------

    @Test
    void aThresholdListProducesOneSeekPlanPerThresholdInThresholdOrder() {
        List<SqlValue> thresholds = List.of(new SqlValue.Int64(1), new SqlValue.Int64(500_001),
                new SqlValue.Int64(1_000_001));
        List<SqlPlan> plans = PAIR.source()
                .samplingPlan(seekKey("shop", "订单", "id", "bigint", thresholds.toArray(new SqlValue[0])), 3);

        assertEquals(3, plans.size(), "TP §9.3: one seek per evenly spaced threshold");
        for (int i = 0; i < plans.size(); i++) {
            assertEquals(SEEK_SQL, sql(plans.get(i)), "TP §9.3: every seek is the same statement, bound differently");
            assertEquals(List.of(thresholds.get(i)), parameters(plans.get(i)),
                    "TP §9.3: the seeks come back in threshold order, so plan " + i + " binds threshold " + i);
        }
    }

    @Test
    void noThresholdListProducesTheFirstHalfAscendingThenTheLastHalfDescending() {
        List<SqlPlan> plans = PAIR.source().samplingPlan(representativeWindowKey(), 1000);

        assertEquals(2, plans.size(), "TP §9.3: a non-numeric or composite key samples its first and last rows");
        assertEquals(ASCENDING_SQL, sql(plans.get(0)), "TP §9.3: the first rows in typed source key order come first");
        assertEquals(DESCENDING_SQL, sql(plans.get(1)), "TP §9.3: the last rows are the same order reversed");
        assertEquals(List.of(new SqlValue.Int64(500)), parameters(plans.get(0)), "TP §9.3: the first ⌈n/2⌉ rows");
        assertEquals(List.of(new SqlValue.Int64(500)), parameters(plans.get(1)), "TP §9.3: the last ⌊n/2⌋ rows");
    }

    @Test
    void theHalvesAreTheCeilingThenTheFloorForEveryN() {
        Map<Integer, List<Long>> expected = new TreeMap<>(Map.of(
                1, List.of(1L, 0L), 2, List.of(1L, 1L), 3, List.of(2L, 1L), 7, List.of(4L, 3L),
                1000, List.of(500L, 500L), 1001, List.of(501L, 500L), Integer.MAX_VALUE,
                List.of(1_073_741_824L, 1_073_741_823L)));

        for (Map.Entry<Integer, List<Long>> entry : expected.entrySet()) {
            List<SqlPlan> plans = PAIR.source().samplingPlan(representativeWindowKey(), entry.getKey());

            assertEquals(2, plans.size(), "TP §9.3: the list has two plans whatever n is, so its shape never "
                    + "depends on the row count; n = " + entry.getKey());
            assertEquals(List.of(new SqlValue.Int64(entry.getValue().get(0))), parameters(plans.get(0)),
                    "TP §9.3: the first ⌈n/2⌉ rows ascending, n = " + entry.getKey());
            assertEquals(List.of(new SqlValue.Int64(entry.getValue().get(1))), parameters(plans.get(1)),
                    "TP §9.3: the last ⌊n/2⌋ rows descending, n = " + entry.getKey());
        }
    }

    // ---- dialect computes no threshold and does no arithmetic on data ------------------------------

    @Test
    void everySeekBindsTheGivenThresholdUnchangedWhateverItsType() {
        List<SqlValue> thresholds = List.of(
                new SqlValue.Int64(Long.MIN_VALUE),
                new SqlValue.Decimal(new BigDecimal("1.50")),
                new SqlValue.Decimal(new BigDecimal("1.5")),
                new SqlValue.Text("2024-01-01 00:00:00.000000"),
                new SqlValue.Bytes(new byte[] {0x00, (byte) 0xFF, 0x7F}),
                new SqlValue.Bool(true));
        SamplingKey key = seekKey("shop", "订单", "id", "bigint", thresholds.toArray(new SqlValue[0]));

        List<SqlPlan> plans = PAIR.source().samplingPlan(key, thresholds.size());

        for (int i = 0; i < thresholds.size(); i++) {
            assertEquals(List.of(thresholds.get(i)), parameters(plans.get(i)),
                    "TP §9.3: the arbitrary-precision threshold arithmetic is validation's, so dialect binds the "
                            + "value it was handed and neither rounds, widens nor re-types it — threshold " + i);
        }
        assertEquals(List.of(new SqlValue.Decimal(new BigDecimal("1.50"))), parameters(plans.get(1)),
                "TP §9.3: decimal scale is significant, so 1.50 must not arrive as 1.5");
        assertTrue(parameters(plans.get(3)).get(0) instanceof SqlValue.Text,
                "TP §9.3: SqlValue has no temporal variant, so validation decides how a date threshold binds and "
                        + "dialect does not interpret it");
    }

    @Test
    void aWindowPlanBindsNothingButItsRowCount() {
        for (SqlPlan plan : PAIR.source().samplingPlan(representativeWindowKey(), 1000)) {
            assertEquals(1, parameters(plan).size(), "TP §9.3: with no thresholds there is no data value to bind, "
                    + "so the only bound value is the row count dialect was given");
            assertTrue(parameters(plan).get(0) instanceof SqlValue.Int64,
                    "ADR-0008 §Plans: the row count is bound like every other caller-supplied value");
        }
    }

    // ---- refusals ----------------------------------------------------------------------------------

    @Test
    void aNonPositiveNThrows() {
        SamplingKey key = representativeWindowKey();
        for (int n : new int[] {0, -1, -1000, Integer.MIN_VALUE}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> PAIR.source().samplingPlan(key, n),
                    "TP §9.3: a sample reads n rows, so n = " + n + " is not a sample");
            assertTrue(failure.getMessage().contains("positive"), "the refusal says why: " + failure.getMessage());
        }
    }

    @Test
    void aThresholdListThatIsNotOnePerSampledRowThrows() {
        SamplingKey key = seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1), new SqlValue.Int64(2));

        assertThrows(IllegalArgumentException.class, () -> PAIR.source().samplingPlan(key, 3),
                "TP §9.3: evenly spaced thresholds are one per sampled row, so three rows need three thresholds");
        assertThrows(IllegalArgumentException.class, () -> PAIR.source().samplingPlan(key, 1),
                "TP §9.3: a sample of one row cannot carry two thresholds");
        assertEquals(2, PAIR.source().samplingPlan(key, 2).size(), "TP §9.3: n thresholds sample n rows");
    }

    @Test
    void seekThresholdsBesideACompositeKeyThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingKey(new TableCoordinate("shop", "orders"),
                        List.of(keyColumn("shop", "orders", "id", "bigint"),
                                keyColumn("shop", "orders", "line", "int")),
                        Optional.of(List.of(new SqlValue.Int64(1)))),
                "TP §9.3: seek thresholds belong to a numeric single key; a composite key samples first and last");
    }

    @Test
    void anEmptyThresholdListAndAKeyColumnFromAnotherTableAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingKey(new TableCoordinate("shop", "orders"),
                        List.of(keyColumn("shop", "orders", "id", "bigint")), Optional.of(List.<SqlValue>of())),
                "TP §9.3: the seek strategy with no threshold would sample nothing");
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingKey(new TableCoordinate("shop", "orders"),
                        List.of(keyColumn("shop", "items", "id", "bigint")), Optional.empty()),
                "TP §9.3: a sampling key orders one table");
    }

    // ---- hostile names -----------------------------------------------------------------------------

    @Test
    void hostileNamesNeverChangeTheStatementStructure() {
        for (String hostile : HOSTILE) {
            SamplingKey seek = seekKey(hostile, hostile, hostile, "bigint", new SqlValue.Int64(1));
            SqlPlan seekPlan = PAIR.source().samplingPlan(seek, 1).get(0);
            assertEquals("SELECT <id> FROM <id>.<id> WHERE <id> >= ? ORDER BY <id> ASC LIMIT 1",
                    skeleton(sql(seekPlan)),
                    "TP §7.1: a name is quoted, never structural — " + hostile);

            SamplingKey window = windowKey(hostile, hostile,
                    keyColumn(hostile, hostile, hostile, "varchar"),
                    keyColumn(hostile, hostile, hostile + "2", "int"));
            List<SqlPlan> windowPlans = PAIR.source().samplingPlan(window, 4);
            assertEquals("SELECT <id>, <id> FROM <id>.<id> ORDER BY <id> ASC, <id> ASC LIMIT ?",
                    skeleton(sql(windowPlans.get(0))), "TP §7.1: a name is quoted, never structural — " + hostile);
            assertEquals("SELECT <id>, <id> FROM <id>.<id> ORDER BY <id> DESC, <id> DESC LIMIT ?",
                    skeleton(sql(windowPlans.get(1))), "TP §7.1: a name is quoted, never structural — " + hostile);

            for (SqlPlan plan : List.of(seekPlan, windowPlans.get(0), windowPlans.get(1))) {
                assertEquals(parameters(plan).size(),
                        skeleton(sql(plan)).chars().filter(c -> c == '?').count(),
                        "ADR-0008 §Plans: one bound parameter per placeholder, and a name holding `?` binds "
                                + "nothing — " + hostile);
                assertFalse(sql(plan).contains(" "), "TP §7.1: no NUL reaches a statement — " + hostile);
            }
        }
    }

    @Test
    void aNameHoldingNulCannotHaveComeFromTheSourceCatalogAndIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PAIR.source().samplingPlan(
                        windowKey("shop", "orders", keyColumn("shop", "orders", "i d", "bigint")), 2),
                "TP §7.1: a MySQL identifier cannot contain U+0000, so this name did not come from the catalog");
    }

    @Test
    void hostileThresholdValuesAreBoundAndNeverRendered() {
        List<SqlValue> hostileValues = List.of(
                new SqlValue.Text("'); DROP TABLE t; --"),
                new SqlValue.Text("back\\slash\\"),
                new SqlValue.Text("$$body$$"),
                new SqlValue.Text("new\nline"),
                new SqlValue.Text("?"),
                new SqlValue.Text("订单明细😀"));
        SamplingKey key = seekKey("shop", "orders", "id", "varchar", hostileValues.toArray(new SqlValue[0]));

        for (SqlPlan plan : PAIR.source().samplingPlan(key, hostileValues.size())) {
            assertEquals(SEEK_SQL.replace("`订单`", "`orders`"), sql(plan),
                    "ADR-0008 §Plans: every value is bound, so no threshold text can reach the statement");
        }
    }

    // ---- fingerprints ------------------------------------------------------------------------------

    @Test
    void anEqualSampleIsAnEqualPlanAndEveryVariedFieldChangesTheFingerprint() {
        SqlPlan base = PAIR.source()
                .samplingPlan(seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1)), 1).get(0);

        assertEquals(base.fingerprint(), PAIR.source()
                        .samplingPlan(seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1)), 1).get(0)
                        .fingerprint(),
                "ADR-0008 §Plans: an equal sampling key is an equal plan");

        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "database", seekPlan(seekKey("shop2", "订单", "id", "bigint", new SqlValue.Int64(1))),
                "table", seekPlan(seekKey("shop", "订单2", "id", "bigint", new SqlValue.Int64(1))),
                "key column", seekPlan(seekKey("shop", "订单", "id2", "bigint", new SqlValue.Int64(1))),
                "declared type", seekPlan(seekKey("shop", "订单", "id", "int", new SqlValue.Int64(1))),
                "threshold value", seekPlan(seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(2))),
                "threshold type", seekPlan(seekKey("shop", "订单", "id", "bigint",
                        new SqlValue.Decimal(new BigDecimal("1")))),
                "strategy", PAIR.source().samplingPlan(
                        windowKey("shop", "订单", keyColumn("shop", "订单", "id", "bigint")), 1).get(0)));

        assertAllDistinct(base, variants);
    }

    @Test
    void theWindowStrategyVariesWithEveryFieldItRenders() {
        SqlPlan base = PAIR.source().samplingPlan(representativeWindowKey(), 1000).get(0);

        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "direction", PAIR.source().samplingPlan(representativeWindowKey(), 1000).get(1),
                "n", PAIR.source().samplingPlan(representativeWindowKey(), 1001).get(0),
                "key column order", PAIR.source().samplingPlan(windowKey("shop", "order`s",
                        keyColumn("shop", "order`s", "line no", "int"),
                        keyColumn("shop", "order`s", "客户", "varchar")), 1000).get(0),
                "a key column removed", PAIR.source().samplingPlan(windowKey("shop", "order`s",
                        keyColumn("shop", "order`s", "客户", "varchar")), 1000).get(0),
                "a key column added", PAIR.source().samplingPlan(windowKey("shop", "order`s",
                        keyColumn("shop", "order`s", "客户", "varchar"),
                        keyColumn("shop", "order`s", "line no", "int"),
                        keyColumn("shop", "order`s", "seq", "bigint")), 1000).get(0),
                "declared type", PAIR.source().samplingPlan(windowKey("shop", "order`s",
                        keyColumn("shop", "order`s", "客户", "text"),
                        keyColumn("shop", "order`s", "line no", "int")), 1000).get(0)));

        assertAllDistinct(base, variants);
    }

    private static SqlPlan seekPlan(SamplingKey key) {
        return PAIR.source().samplingPlan(key, 1).get(0);
    }

    private static void assertAllDistinct(SqlPlan base, Map<String, SqlPlan> variants) {
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only the " + variant.getKey() + " must change the fingerprint");
        }
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256) over {@link #SEEK_SQL} and the declared result schema, in a separate Python script
     * quoted on ticket #138 — never copied out of a failing assertion.
     */
    @Test
    void theRepresentativeSeekFingerprintIsPinned() {
        assertEquals("e20d9bef9d5a35b5d2d93bc077d7aae305d6e5eee12025b204e61ba2bd3166c7",
                PAIR.source().samplingPlan(seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1)), 1)
                        .get(0).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a seek plan's fingerprint must not depend on the JVM, the machine or the run");
    }

    /** Pinned the same independent way, over {@link #ASCENDING_SQL} and {@link #DESCENDING_SQL}. */
    @Test
    void theRepresentativeWindowFingerprintsArePinned() {
        List<SqlPlan> plans = PAIR.source().samplingPlan(representativeWindowKey(), 1000);

        assertEquals("cf412d04240d78e39e7ff72edb8faf5417c24559fab1605badbfb4c2bc04425a",
                plans.get(0).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the ascending window's fingerprint must not depend on the JVM, machine or run");
        assertEquals("7c8c0c68edba8a7c4f093bb0ec86ada7a1f104c01de16986698a239cbafbeda4",
                plans.get(1).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the descending window's fingerprint must not depend on the JVM, machine or run");
    }

    @Test
    void theRepresentativeStatementsAreUtf8AndCarryNoTrailingSemicolon() {
        List<SqlPlan> plans = new ArrayList<>(PAIR.source()
                .samplingPlan(seekKey("shop", "订单", "id", "bigint", new SqlValue.Int64(1)), 1));
        plans.addAll(PAIR.source().samplingPlan(representativeWindowKey(), 1000));

        for (SqlPlan plan : plans) {
            String statement = sql(plan);
            assertEquals(statement, new String(statement.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                    "TP §7.1: a statement is UTF-8 text");
            assertFalse(statement.endsWith(";"),
                    "ADR-0008 §Plans: a plan carries one statement, not a script — " + statement);
        }
    }
}
