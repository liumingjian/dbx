package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code source.validationFactPlans}: TP §9.2's automatic checks plus ADR-0040's 非空约束符合性 (null
 * constraint conformance) and 大记录值完整性 (large record value integrity), driven through
 * {@code dialect.api} with values ({@code docs/spec/dialect.md} obligations 23 and 23b).
 *
 * <p>What this module never does: compare an aggregate, choose a reason code, or decide that a table
 * without a key is {@code NOT_APPLICABLE / NO_PRIMARY_KEY} — all {@code validation}'s (obligations 8–20).
 * What L1 cannot prove: that any of these statements runs on a real MySQL 8.0 — {@code dialect.md}
 * §Verification puts that at L2 in {@code contract} slice 8 through {@code gateway}.
 */
class ValidationFactPlansContractTest {

    private static final TableCoordinate SOURCE = new TableCoordinate("shop", "order`s");

    private static final TargetTableCoordinate TARGET =
            new TargetTableCoordinate(new TargetIdentifier("public"), new TargetIdentifier("orders"));

    /** Hostile source names: quoting, comment and statement delimiters, placeholders, limits. */
    private static final List<String> HOSTILE = List.of(
            "order`s", "``", "`", "a\"b", "'single'", "back\\slash\\", "$$body$$", "x; DROP TABLE t; --",
            "a -- b", "a /* b */", "# hash", "new\nline", "cr\rlf", "?", "t FOR UPDATE", "LOCK TABLES t",
            "adjacent￿", "订单明细", "emoji😀", " padded ",
            "a".repeat(63), "a".repeat(64), "订".repeat(64), "é".repeat(31) + "a", "é".repeat(32));

    /** A backtick-quoted MySQL identifier: a doubled backtick stays inside, so the span ends at a lone one. */
    private static final Pattern QUOTED = Pattern.compile("`(?:[^`]|``)*?`(?!`)");

    private static final String TABLE_SQL = "`shop`.`order``s`";

    /**
     * The representative batch. {@code rate} earns no fact and so is in no batch at all, which is why the
     * ordinals of {@code flag} and {@code payload} are 2 and 3 rather than 3 and 4.
     */
    private static final String BATCH_SQL = "SELECT ? AS `batch_index`, "
            + "COUNT(`总额`) AS `count_1`, SUM(`总额`) AS `sum_1`, MIN(`总额`) AS `min_1`, MAX(`总额`) AS `max_1`, "
            + "COUNT(*) - COUNT(`总额`) AS `null_count_1`, COUNT(*) - COUNT(`flag`) AS `null_count_2`, "
            + "COUNT(`payload`) AS `large_value_count_3`, "
            + "SUM(COALESCE(OCTET_LENGTH(CAST(`payload` AS BINARY)), 0)) AS `large_value_bytes_sum_3`, "
            + "MAX(COALESCE(OCTET_LENGTH(CAST(`payload` AS BINARY)), 0)) AS `large_value_bytes_max_3` "
            + "FROM " + TABLE_SQL;

    private static final String KEY_SQL = "SELECT COUNT(`id`) AS `key_not_null_1`, "
            + "MIN(`id`) AS `key_min_1`, MAX(`id`) AS `key_max_1` FROM " + TABLE_SQL;

    private static final String DUPLICATE_SQL = "SELECT COUNT(*) AS `duplicate_key_values` FROM "
            + "(SELECT 1 FROM " + TABLE_SQL + " GROUP BY `id` HAVING COUNT(*) > 1) AS `duplicate_keys`";

    private static final List<ResultSchema.Column> BATCH_COLUMNS = List.of(
            new ResultSchema.Column("batch_index", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("count_1", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("sum_1", "decimal", Nullability.NULLABLE),
            new ResultSchema.Column("min_1", "decimal", Nullability.NULLABLE),
            new ResultSchema.Column("max_1", "decimal", Nullability.NULLABLE),
            new ResultSchema.Column("null_count_1", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("null_count_2", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("large_value_count_3", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("large_value_bytes_sum_3", "decimal", Nullability.NULLABLE),
            new ResultSchema.Column("large_value_bytes_max_3", "bigint", Nullability.NULLABLE));

    // --- Fixture ------------------------------------------------------------------------------------

    private static ColumnCoordinate at(String column) {
        return new ColumnCoordinate(SOURCE.database(), SOURCE.table(), column);
    }

    private static ValidationColumn column(String name, ValidationColumn.Aggregation aggregation,
            Nullability sourceNullability, boolean largeRecordValue) {
        return new ValidationColumn(new ApprovedColumn(at(name), new TargetIdentifier(name)), aggregation,
                sourceNullability, largeRecordValue);
    }

    private static ValidationColumn exactNumeric(String name) {
        return column(name, ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NULLABLE, false);
    }

    private static ValidationKeyComponent key(String name, ValidationKeyComponent.Ordering ordering) {
        return new ValidationKeyComponent(at(name), ordering);
    }

    /**
     * One exact numeric that is also {@code NOT NULL}, one approximate numeric that earns nothing, one
     * Boolean that earns only its null count, one large-record column, and an integer single key.
     */
    private static ValidationItem representative() {
        return new ValidationItem(SOURCE, TARGET, List.of(
                column("总额", ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false),
                column("rate", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NULLABLE, false),
                column("flag", ValidationColumn.Aggregation.BOOLEAN, Nullability.NOT_NULL, false),
                column("payload", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, true)),
                List.of(key("id", ValidationKeyComponent.Ordering.INTEGER)), true);
    }

    private static ValidationItem item(List<ValidationColumn> columns, List<ValidationKeyComponent> keyComponents) {
        boolean large = columns.stream().anyMatch(ValidationColumn::largeRecordValue);
        return new ValidationItem(SOURCE, TARGET, columns, keyComponents, large);
    }

    private static List<SqlPlan> plans(ValidationItem... items) {
        return PAIR.source().validationFactPlans(List.of(items));
    }

    private static String sql(SqlPlan plan) {
        assertEquals(1, plan.statements().size(),
                "TP §9.2: one batch is one bounded scan, so a fact plan carries one statement");
        return plan.statements().get(0).sql();
    }

    private static List<String> labels(SqlPlan plan) {
        return plan.resultSchema().columns().stream().map(ResultSchema.Column::label).toList();
    }

    /** The statement with every quoted identifier replaced, so only its SQL structure is left. */
    private static String skeleton(String statement) {
        return QUOTED.matcher(statement).replaceAll(Matcher.quoteReplacement("<id>"));
    }

    // --- Plan shape (ADR-0008 §Plans) ---------------------------------------------------------------

    @Test
    void everyFactPlanCarriesTheValidationKindTimeoutPrivilegeEvidenceAndCardinality() {
        List<SqlPlan> plans = plans(representative());

        assertEquals(3, plans.size(), "TP §9.2: one batch plan, then the key facts and the duplicate count");
        for (SqlPlan plan : plans) {
            assertEquals(OperationKind.SOURCE_VALIDATION_FACTS, plan.operationKind(),
                    "docs/spec/dialect.md obligation 23: validation facts are their own operation kind");
            assertEquals(TimeoutClass.EXACT_SCAN, plan.timeoutClass(),
                    "TP §9.2: an aggregate over every row is an exact scan, not a catalog read");
            assertEquals(Set.of(RequiredPrivilege.SOURCE_SELECT), plan.requiredPrivileges(),
                    "ADR-0006: reading source facts needs SELECT on the source and nothing more");
            assertEquals(EvidencePolicy.STATEMENT_AND_AGGREGATES, plan.evidencePolicy(),
                    "ADR-0028 §No data values: the aggregates are evidence, the rows behind them are not");
            assertEquals(ResultSchema.Cardinality.EXACTLY_ONE_ROW, plan.resultSchema().cardinality(),
                    "TP §9.2: an ungrouped aggregate answers exactly one row, even for an empty table");
        }
    }

    @Test
    void theRepresentativeStatementsArePinned() {
        List<SqlPlan> plans = plans(representative());

        assertEquals(BATCH_SQL, sql(plans.get(0)),
                "TP §9.2 and ADR-0040: one bounded scan carries the exact-numeric aggregates, the null counts "
                        + "and the byte lengths");
        assertEquals(KEY_SQL, sql(plans.get(1)), "TP §9.2: per-component non-nullness, then the extrema");
        assertEquals(DUPLICATE_SQL, sql(plans.get(2)),
                "TP §9.2: uniqueness evidence is a group-by over all typed components");
    }

    @Test
    void theBatchResultSchemaDeclaresTypesThatLoseNothing() {
        SqlPlan batch = plans(representative()).get(0);

        assertEquals(BATCH_COLUMNS, batch.resultSchema().columns(),
                "validation obligation 13: sums and extrema of exact numerics are read as arbitrary-precision "
                        + "decimals, and a count is a bigint that an empty table answers with nought");
    }

    @Test
    void anIntegerKeysExtremaAreDeclaredWideEnoughForAnUnsignedBigint() {
        SqlPlan keyFacts = plans(representative()).get(1);

        assertEquals(List.of(
                        new ResultSchema.Column("key_not_null_1", "bigint", Nullability.NOT_NULL),
                        new ResultSchema.Column("key_min_1", "decimal(20,0)", Nullability.NULLABLE),
                        new ResultSchema.Column("key_max_1", "decimal(20,0)", Nullability.NULLABLE)),
                keyFacts.resultSchema().columns(),
                "ADR-0037: a BIGINT UNSIGNED key maximum wraps to -1 when it is read as a signed long, so it is "
                        + "declared the same decimal(20,0) the preflight scan fixed");
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256) over the literal {@link #BATCH_SQL} and {@link #BATCH_COLUMNS}, in a separate Python
     * script quoted on ticket #137 — never copied out of the implementation.
     */
    @Test
    void theRepresentativeBatchFingerprintIsPinnedFromTheIndependentModel() {
        assertEquals("1ff848778d98b5b1ea249e0ff65452b2899729d38c3177f868ee4fbda5903321",
                plans(representative()).get(0).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a fact plan's fingerprint must not depend on the JVM, the machine or the run");
    }

    @Test
    void distinctItemsGiveDistinctFingerprints() {
        ValidationColumn total = column("总额", ValidationColumn.Aggregation.EXACT_NUMERIC,
                Nullability.NOT_NULL, false);
        ValidationColumn net = column("net", ValidationColumn.Aggregation.EXACT_NUMERIC,
                Nullability.NOT_NULL, false);
        SqlPlan base = plans(item(List.of(total), List.of())).get(0);
        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "the source table", plans(new ValidationItem(new TableCoordinate("shop", "orders"), TARGET,
                        List.of(new ValidationColumn(new ApprovedColumn(
                                new ColumnCoordinate("shop", "orders", "总额"), new TargetIdentifier("t")),
                                ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false)),
                        List.of(), false)).get(0),
                "the column name", plans(item(List.of(net), List.of())).get(0),
                "the aggregate family", plans(item(List.of(column("总额",
                        ValidationColumn.Aggregation.NONE, Nullability.NOT_NULL, false)), List.of())).get(0),
                "the source nullability", plans(item(List.of(column("总额",
                        ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NULLABLE, false)),
                        List.of())).get(0),
                "the large-record flag", plans(item(List.of(column("总额",
                        ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, true)),
                        List.of())).get(0),
                "a column added", plans(item(List.of(total, net), List.of())).get(0),
                "the column order", plans(item(List.of(net, total), List.of())).get(0)));

        assertEquals(base.fingerprint(), plans(item(List.of(total), List.of())).get(0).fingerprint(),
                "ADR-0008 §Plans: an equal item is an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only " + variant.getKey() + " must change the fingerprint");
        }
    }

    @Test
    void twoBatchesOfTheSameItemDifferInTheirFingerprintAndTheirBoundIndex() {
        List<SqlPlan> plans = plans(item(numericColumns(301), List.of()));

        assertEquals(2, plans.size(), "TP §9.2: 301 exact-numeric columns do not fit one batch of 300");
        assertEquals(List.of(new SqlValue.Int64(0)), plans.get(0).statements().get(0).parameters(),
                "TP §9.2: every plan names the batch index validation pairs the two sides by");
        assertEquals(List.of(new SqlValue.Int64(1)), plans.get(1).statements().get(0).parameters(),
                "TP §9.2: the second batch is batch one");
        assertNotEquals(plans.get(0).fingerprint(), plans.get(1).fingerprint(),
                "ADR-0008 §Plans: two batches of one item are two different plans");
    }

    // --- The shared batch rule (spec #134; obligation 23) --------------------------------------------

    private static List<ValidationColumn> numericColumns(int count) {
        List<ValidationColumn> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(exactNumeric("n" + i));
        }
        return columns;
    }

    @Test
    void threeHundredAndOneExactNumericColumnsSplitIntoTwoBatchesUnderTheSharedRule() {
        ValidationItem item = item(numericColumns(301), List.of());
        List<ValidationFactBatch> batches = ValidationFactBatch.partition(item);

        assertEquals(2, batches.size(), "TP §9.2: at most 300 exact-numeric columns per batch");
        assertEquals(300, batches.get(0).exactNumericColumns().size(), "TP §9.2: the first batch is full");
        assertEquals(1, batches.get(1).exactNumericColumns().size(), "TP §9.2: the 301st opens the second batch");
        assertEquals(List.of(0, 1), batches.stream().map(ValidationFactBatch::index).toList(),
                "the batch index is its position, which is what validation pairs the two sides by");

        List<SqlPlan> plans = plans(item);
        assertEquals(batches.size(), plans.size(),
                "spec #134: the dialect emits the shared rule's batches and partitions nothing of its own");
        for (int i = 0; i < batches.size(); i++) {
            for (ValidationColumn column : batches.get(i).columns()) {
                assertTrue(sql(plans.get(i)).contains("COUNT(`" + column.source().column() + "`)"),
                        "spec #134: batch " + i + "'s plan holds exactly the shared rule's batch " + i);
            }
        }
    }

    @Test
    void theSharedRuleIsTheOnlyPartitionSoBothSidesSeeTheSameColumnsInTheSameOrder() {
        ValidationItem item = representative();

        assertEquals(List.of(List.of("总额", "flag", "payload")),
                ValidationFactBatch.partition(item).stream()
                        .map(batch -> batch.columns().stream().map(column -> column.source().column()).toList())
                        .toList(),
                "spec #134: the partition is a function of the item alone, so source batch i and target batch i "
                        + "hold the same columns in the same order");
        assertEquals(ValidationFactBatch.partition(item), ValidationFactBatch.partition(List.of(item)),
                "the list form is the single-item form, flattened");
    }

    @Test
    void aColumnThatEarnsNoFactIsInNoBatchAtAll() {
        ValidationItem item = item(List.of(
                column("rate", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NULLABLE, false),
                column("note", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false)), List.of());

        assertEquals(List.of(), ValidationFactBatch.partition(item),
                "TP §9.2: an expression that answers no fact buys a scan and proves nothing");
        assertEquals(List.of(), plans(item), "an item with no fact and no key plans nothing");
    }

    @Test
    void aBatchRefusesMoreThanThreeHundredExactNumericColumns() {
        ValidationItem item = item(numericColumns(301), List.of());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ValidationFactBatch(item, 0, item.columns()));
        assertTrue(refused.getMessage().contains("300"),
                "TP §9.2: the 300 bound is the batch's own invariant, not a convention of one dialect — "
                        + refused.getMessage());
    }

    // --- TP §9.2 aggregate families -----------------------------------------------------------------

    @Test
    void floatsDoublesAndBooleansEarnNoExactNumericAggregate() {
        List<SqlPlan> plans = plans(item(List.of(
                column("rate", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NOT_NULL, false),
                column("ratio", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NOT_NULL, false),
                column("flag", ValidationColumn.Aggregation.BOOLEAN, Nullability.NOT_NULL, false)), List.of()));

        assertEquals(1, plans.size(), "the three columns earn their null counts, so they share one batch");
        assertEquals(List.of("batch_index", "null_count_1", "null_count_2", "null_count_3"), labels(plans.get(0)),
                "TP §9.2: floating types and Booleans are not exact numeric aggregate assertions, so they get "
                        + "no COUNT, SUM, MIN or MAX — only the null count ADR-0040 adds");
        assertFalse(sql(plans.get(0)).contains("SUM(`rate`)"),
                "TP §9.2: an IEEE sum is order-dependent, so it is never planned");
    }

    @Test
    void aNotNullColumnsNullCountRidesAlongAndCountsTowardNothing() {
        List<ValidationColumn> columns = new ArrayList<>(numericColumns(300));
        columns.add(column("note", ValidationColumn.Aggregation.NONE, Nullability.NOT_NULL, false));
        List<ValidationFactBatch> batches = ValidationFactBatch.partition(item(columns, List.of()));

        assertEquals(1, batches.size(),
                "ADR-0040: 非空约束符合性 rides in the existing batch so it costs no extra scan");
        assertEquals(301, batches.get(0).columns().size(), "the null-count column joins the full batch");
        assertEquals(300, batches.get(0).exactNumericColumns().size(),
                "TP §9.2: only exact-numeric columns count toward the 300");
        assertTrue(sql(plans(item(columns, List.of())).get(0)).contains("COUNT(*) - COUNT(`note`) AS `null_count_301`"),
                "validation obligation 10: every source NOT NULL contract column gets a null count");
    }

    @Test
    void aNullableColumnGetsNoNullCount() {
        SqlPlan plan = plans(item(List.of(exactNumeric("总额")), List.of())).get(0);

        assertFalse(labels(plan).stream().anyMatch(label -> label.startsWith("null_count")),
                "ADR-0040: 非空约束符合性 covers the columns the approved contract marks NOT NULL on the source "
                        + "side; a nullable column has no constraint to conform to");
    }

    /**
     * Obligation 23b and ADR-0040: the byte length is ADR-0003's, taken from {@code source.preflightScanPlan}'s
     * own statement rather than restated here, so the two can only agree.
     */
    @Test
    void aLargeRecordColumnsByteLengthIsByteIdenticalToThePreflightScansExpression() {
        String preflight = PAIR.source().preflightScanPlan(tableWithPayload(),
                List.of(new ApprovedColumn(at("payload"), new TargetIdentifier("payload"))), List.of())
                .statements().get(0).sql();
        String expression = preflight.substring(preflight.indexOf("COALESCE"),
                preflight.indexOf(") AS `value_bytes_1`"));
        SqlPlan plan = plans(representative()).get(0);

        assertEquals("COALESCE(OCTET_LENGTH(CAST(`payload` AS BINARY)), 0)", expression,
                "ADR-0003: the preflight scan's per-value byte length is the expression ADR-0040 reuses");
        assertTrue(sql(plan).contains("SUM(" + expression + ") AS `large_value_bytes_sum_3`")
                        && sql(plan).contains("MAX(" + expression + ") AS `large_value_bytes_max_3`"),
                "ADR-0040: 大记录值完整性 sums and maximises the very expression preflight measured — " + sql(plan));
        assertTrue(sql(plan).contains("COUNT(`payload`) AS `large_value_count_3`"),
                "ADR-0040: the non-null count comes with the two length aggregates");
    }

    @Test
    void onlyALargeRecordTableMayCarryALargeRecordColumn() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ValidationItem(SOURCE, TARGET,
                        List.of(column("payload", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, true)),
                        List.of(), false));

        assertTrue(refused.getMessage().contains("payload"),
                "ADR-0040: 大记录值完整性 applies only within a 大记录表 — " + refused.getMessage());
    }

    // --- Key facts (TP §9.2) ------------------------------------------------------------------------

    @Test
    void everyKeyComponentGetsItsOwnNonNullCount() {
        List<SqlPlan> plans = plans(item(List.of(),
                List.of(key("客户", ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE),
                        key("line no", ValidationKeyComponent.Ordering.INTEGER))));

        assertEquals(2, plans.size(), "TP §9.2: key facts are their own plans — the aggregates, then the duplicates");
        assertEquals(List.of("key_not_null_1", "key_not_null_2"), labels(plans.get(0)),
                "TP §9.2: each component's non-nullness is verified");
        assertEquals("SELECT COUNT(`客户`) AS `key_not_null_1`, COUNT(`line no`) AS `key_not_null_2` FROM "
                + TABLE_SQL, sql(plans.get(0)), "TP §9.2: components are counted in key order");
        assertEquals("SELECT COUNT(*) AS `duplicate_key_values` FROM (SELECT 1 FROM " + TABLE_SQL
                        + " GROUP BY `客户`, `line no` HAVING COUNT(*) > 1) AS `duplicate_keys`", sql(plans.get(1)),
                "TP §9.2: uniqueness evidence groups by all typed components and detects any count above one");
    }

    @Test
    void stringCollatedAndCompositeKeyExtremaAreNotPlannedAtAll() {
        List<ValidationItem> unordered = List.of(
                item(List.of(), List.of(key("客户", ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE))),
                item(List.of(), List.of(key("id", ValidationKeyComponent.Ordering.INTEGER),
                        key("line no", ValidationKeyComponent.Ordering.INTEGER))),
                item(List.of(), List.of(key("id", ValidationKeyComponent.Ordering.DATE),
                        key("客户", ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE))));

        for (ValidationItem item : unordered) {
            for (SqlPlan plan : plans(item)) {
                assertFalse(sql(plan).contains("MIN(") || sql(plan).contains("MAX("),
                        "TP §9.2: a string, collated or composite key has no cross-database order, so DBX marks "
                                + "its extrema not applicable rather than send a query that manufactures one — "
                                + sql(plan));
            }
        }
    }

    @Test
    void eachProvableOrderingDeclaresAnExtremumTypeThatLosesNothing() {
        Map<ValidationKeyComponent.Ordering, String> declared = new TreeMap<>(Map.of(
                ValidationKeyComponent.Ordering.INTEGER, "decimal(20,0)",
                ValidationKeyComponent.Ordering.EXACT_DECIMAL, "decimal",
                ValidationKeyComponent.Ordering.DATE, "date",
                ValidationKeyComponent.Ordering.BYTE_ORDERED_BINARY, "varbinary"));

        for (Map.Entry<ValidationKeyComponent.Ordering, String> entry : declared.entrySet()) {
            SqlPlan plan = plans(item(List.of(), List.of(key("id", entry.getKey())))).get(0);
            assertEquals(List.of(
                            new ResultSchema.Column("key_not_null_1", "bigint", Nullability.NOT_NULL),
                            new ResultSchema.Column("key_min_1", entry.getValue(), Nullability.NULLABLE),
                            new ResultSchema.Column("key_max_1", entry.getValue(), Nullability.NULLABLE)),
                    plan.resultSchema().columns(),
                    "TP §9.2 and validation obligation 13: a " + entry.getKey() + " extremum is read without loss");
        }
        assertFalse(ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE.extremaComparable(),
                "TP §9.2: the fifth ordering is the refusal, and it is the only one");
    }

    @Test
    void aTableWithoutAKeyPlansNoKeyFacts() {
        List<SqlPlan> plans = plans(item(List.of(exactNumeric("总额")), List.of()));

        assertEquals(1, plans.size(), "TP §9.2: NOT_APPLICABLE / NO_PRIMARY_KEY is validation's grading, and a "
                + "key DBX does not have is a key DBX does not query");
    }

    // --- Hostile names and values (obligation 5; TP §7.1) -------------------------------------------

    @Test
    void aHostileNameNeverChangesTheStatementStructureOrTheBindings() {
        String expected = null;
        for (String name : HOSTILE) {
            TableCoordinate table = new TableCoordinate("shop", name);
            ValidationItem item = new ValidationItem(table, TARGET,
                    List.of(new ValidationColumn(new ApprovedColumn(
                            new ColumnCoordinate("shop", name, name), new TargetIdentifier("t")),
                            ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false)),
                    List.of(new ValidationKeyComponent(new ColumnCoordinate("shop", name, name),
                            ValidationKeyComponent.Ordering.INTEGER)), false);
            List<SqlPlan> plans = PAIR.source().validationFactPlans(List.of(item));

            StringBuilder structure = new StringBuilder();
            for (SqlPlan plan : plans) {
                ParameterizedStatement statement = plan.statements().get(0);
                structure.append(skeleton(statement.sql())).append('\n');
                assertEquals(statement.sql().length() - statement.sql().replace("?", "").length()
                                - quotedPlaceholders(statement.sql()), statement.parameters().size(),
                        "ADR-0008 §Plans: every placeholder outside a quoted identifier is bound — "
                                + statement.sql());
            }
            if (expected == null) {
                expected = structure.toString();
            }
            assertEquals(expected, structure.toString(),
                    "TP §7.1: a hostile name is quoted, never able to change the statement's structure — " + name);
        }
    }

    /** How many {@code ?} characters sit inside a backtick-quoted identifier, and so bind nothing. */
    private static int quotedPlaceholders(String statement) {
        int inside = 0;
        Matcher matcher = QUOTED.matcher(statement);
        while (matcher.find()) {
            inside += matcher.group().length() - matcher.group().replace("?", "").length();
        }
        return inside;
    }

    @Test
    void aNulInANameIsRefusedRatherThanQuoted() {
        assertThrows(IllegalArgumentException.class,
                () -> plans(item(List.of(exactNumeric("a" + (char) 0 + "b")), List.of())),
                "TP §7.1: a MySQL identifier cannot contain U+0000, so it is refused, never quoted");
    }

    // --- Boundaries and refusals ---------------------------------------------------------------------

    @Test
    void noItemsPlanNothingAndNoListAtAllIsRefused() {
        assertEquals(List.of(), PAIR.source().validationFactPlans(List.of()),
                "TP §9.2: nothing to validate plans nothing");
        assertThrows(NullPointerException.class, () -> PAIR.source().validationFactPlans(null),
                "ADR-0008 §Ownership: a missing argument is a caller bug, not an empty answer");
    }

    @Test
    void aColumnOrKeyComponentOfAnotherTableIsRefused() {
        ColumnCoordinate elsewhere = new ColumnCoordinate("shop", "customers", "id");

        assertThrows(IllegalArgumentException.class, () -> new ValidationItem(SOURCE, TARGET,
                        List.of(new ValidationColumn(new ApprovedColumn(elsewhere, new TargetIdentifier("id")),
                                ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NULLABLE, false)),
                        List.of(), false),
                "TP §9.2: a validation item gathers one table's facts");
        assertThrows(IllegalArgumentException.class, () -> new ValidationItem(SOURCE, TARGET, List.of(),
                        List.of(new ValidationKeyComponent(elsewhere, ValidationKeyComponent.Ordering.INTEGER)),
                        false),
                "TP §9.2: a key component belongs to the table its duplicate count groups");
    }

    @Test
    void aRepeatedColumnOrKeyComponentIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> item(List.of(exactNumeric("总额"), exactNumeric("总额")), List.of()),
                "TP §9.2: a column gathered twice would be paired against itself");
        assertThrows(IllegalArgumentException.class, () -> item(List.of(),
                        List.of(key("id", ValidationKeyComponent.Ordering.INTEGER),
                                key("id", ValidationKeyComponent.Ordering.INTEGER))),
                "TP §9.2: a key component grouped twice is a broken key, not a duplicate count");
    }

    @Test
    void thisModuleGradesNothing() {
        for (SqlPlan plan : plans(representative())) {
            String statement = sql(plan);
            for (String threshold : List.of("1048576", "20971520", "26214400", "67108864", "9223372036854775807")) {
                assertFalse(statement.contains(threshold),
                        "validation obligations 12–20: dialect plans facts and compares nothing — " + statement);
            }
        }
    }

    /** A one-column table for the preflight scan the byte-length expression is taken from. */
    private static SourceTableMetadata tableWithPayload() {
        SourceColumn payload = new SourceColumn(at("payload"), "longblob", "longblob", false,
                OptionalLong.empty(), OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.empty(), Nullability.NULLABLE, Optional.empty(),
                "", 1);
        return new SourceTableMetadata(SOURCE, List.of(payload), List.of(new ColumnComment(at("payload"), "")),
                List.of(), List.of(), "", Optional.empty(),
                new TableStatistics(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                        OptionalLong.empty()));
    }
}
