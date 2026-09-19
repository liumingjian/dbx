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
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code target.validationFactPlans}: TP §9.2's automatic checks and ADR-0040's 非空约束符合性 (null
 * constraint conformance) and 大记录值完整性 (large record value integrity) on the PostgreSQL side, driven
 * through {@code dialect.api} with values ({@code docs/spec/dialect.md} obligation 23, target half).
 *
 * <p>The ruling this class exists for is the shared batch rule: the target must plan
 * {@link ValidationFactBatch#partition(ValidationItem)}'s batches and no partition of its own, so target
 * batch <em>i</em> holds the same columns in the same order as source batch <em>i</em> and {@code validation}
 * pairs the two by index rather than by name.
 *
 * <p>What this module never does: compare an aggregate, choose a reason code, or decide what a difference
 * means — all {@code validation}'s (obligations 12–20, 28). What L1 cannot prove: that any of these
 * statements runs on a real PostgreSQL 15 — {@code dialect.md} §Verification puts that at L2 in
 * {@code contract} slice 8 through {@code gateway}.
 */
class TargetValidationFactsContractTest {

    private static final TableCoordinate SOURCE = new TableCoordinate("shop", "order`s");

    private static final TargetTableCoordinate TARGET =
            new TargetTableCoordinate(new TargetIdentifier("public"), new TargetIdentifier("订单"));

    /** Hostile target names PostgreSQL accepts once quoted: quotes, comment and statement delimiters, limits. */
    private static final List<String> HOSTILE = List.of(
            "it's", "back\\slash", "$$dollar$$", "`tick`", "new\nline", "cr\rtab\t", "a\"b", "?",
            "x\"; DROP TABLE t; --", "'); DROP TABLE t; --", "a''b", "e\\'", "a -- b", "a /* b */",
            "客户订单", "emoji😀", " padded ", "a".repeat(63), "订".repeat(21));

    /** Names past {@code NAMEDATALEN - 1}: PostgreSQL would truncate them into a different object (TP §7.1). */
    private static final List<String> OVERSIZED = List.of("a".repeat(64), "订".repeat(22));

    /** A double-quoted PostgreSQL identifier: a doubled quote stays inside, so the span ends at a lone one. */
    private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"]|\"\")*?\"(?!\")");

    private static final String TABLE_SQL = "\"public\".\"订单\"";

    /**
     * The representative batch. {@code 总额} is approved as {@code total}, so the statement proves the plan
     * renders the <em>approved target</em> name; {@code rate} earns no fact and so is in no batch at all,
     * which is why {@code flag} and {@code payload} have ordinals 3 and 4 rather than 4 and 5.
     */
    private static final String BATCH_SQL = "SELECT ? AS \"batch_index\", "
            + "count(\"id\") AS \"count_1\", sum(\"id\") AS \"sum_1\", min(\"id\") AS \"min_1\", "
            + "max(\"id\") AS \"max_1\", "
            + "count(\"total\") AS \"count_2\", sum(\"total\") AS \"sum_2\", min(\"total\") AS \"min_2\", "
            + "max(\"total\") AS \"max_2\", "
            + "count(*) - count(\"id\") AS \"null_count_1\", count(*) - count(\"total\") AS \"null_count_2\", "
            + "count(*) - count(\"flag\") AS \"null_count_3\", "
            + "count(\"payload\") AS \"large_value_count_4\", "
            + "sum(octet_length(\"payload\")) AS \"large_value_bytes_sum_4\", "
            + "max(octet_length(\"payload\")) AS \"large_value_bytes_max_4\" "
            + "FROM " + TABLE_SQL;

    private static final String KEY_SQL = "SELECT count(\"id\") AS \"key_not_null_1\", "
            + "min(\"id\") AS \"key_min_1\", max(\"id\") AS \"key_max_1\" FROM " + TABLE_SQL;

    private static final String DUPLICATE_SQL = "SELECT count(*) AS \"duplicate_key_values\" FROM "
            + "(SELECT 1 FROM " + TABLE_SQL + " GROUP BY \"id\" HAVING count(*) > 1) AS \"duplicate_keys\"";

    private static final List<ResultSchema.Column> BATCH_COLUMNS = List.of(
            new ResultSchema.Column("batch_index", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("count_1", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("sum_1", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("min_1", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("max_1", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("count_2", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("sum_2", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("min_2", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("max_2", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("null_count_1", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("null_count_2", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("null_count_3", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("large_value_count_4", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("large_value_bytes_sum_4", "numeric", Nullability.NULLABLE),
            new ResultSchema.Column("large_value_bytes_max_4", "bigint", Nullability.NULLABLE));

    // --- Fixture ------------------------------------------------------------------------------------

    private static ColumnCoordinate at(String column) {
        return new ColumnCoordinate(SOURCE.database(), SOURCE.table(), column);
    }

    private static ValidationColumn column(String source, String target, ValidationColumn.Aggregation aggregation,
            Nullability sourceNullability, boolean largeRecordValue) {
        return new ValidationColumn(new ApprovedColumn(at(source), new TargetIdentifier(target)), aggregation,
                sourceNullability, largeRecordValue);
    }

    private static ValidationColumn exactNumeric(String source, String target) {
        return column(source, target, ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NULLABLE, false);
    }

    private static ValidationKeyComponent key(String source, ValidationKeyComponent.Ordering ordering) {
        return new ValidationKeyComponent(at(source), ordering);
    }

    /**
     * Two exact numerics that are also {@code NOT NULL} (one of them renamed by the pair, one of them the
     * key), one approximate numeric that earns nothing, one Boolean that earns only its null count, one
     * large-record column, and an integer single key.
     */
    private static ValidationItem representative() {
        return new ValidationItem(SOURCE, TARGET, List.of(
                column("id", "id", ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false),
                column("总额", "total", ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false),
                column("rate", "rate", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NULLABLE,
                        false),
                column("flag", "flag", ValidationColumn.Aggregation.BOOLEAN, Nullability.NOT_NULL, false),
                column("payload", "payload", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, true)),
                List.of(key("id", ValidationKeyComponent.Ordering.INTEGER)), true);
    }

    private static ValidationItem item(List<ValidationColumn> columns, List<ValidationKeyComponent> keyComponents) {
        boolean large = columns.stream().anyMatch(ValidationColumn::largeRecordValue);
        return new ValidationItem(SOURCE, TARGET, columns, keyComponents, large);
    }

    private static List<SqlPlan> plans(ValidationItem... items) {
        return PAIR.target().validationFactPlans(List.of(items));
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

    private static List<ValidationColumn> numericColumns(int count) {
        List<ValidationColumn> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(exactNumeric("n" + i, "t" + i));
        }
        return columns;
    }

    // --- Plan shape (ADR-0008 §Plans) ---------------------------------------------------------------

    @Test
    void everyFactPlanCarriesTheTargetKindTimeoutPrivilegesEvidenceAndCardinality() {
        List<SqlPlan> plans = plans(representative());

        assertEquals(3, plans.size(), "TP §9.2: one batch plan, then the key facts and the duplicate count");
        for (SqlPlan plan : plans) {
            assertEquals(OperationKind.TARGET_VALIDATION_FACTS, plan.operationKind(),
                    "docs/spec/dialect.md obligation 23: target validation facts are their own operation kind");
            assertEquals(TimeoutClass.EXACT_SCAN, plan.timeoutClass(),
                    "TP §9.2: an aggregate over every row is an exact scan, not a catalog read");
            assertEquals(Set.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT),
                    plan.requiredPrivileges(),
                    "ADR-0006: reading a DBX-owned table needs USAGE on its schema and the object privilege, "
                            + "and nothing that creates, writes or drops");
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
        assertEquals(List.of(new SqlValue.Int64(0)), plans.get(0).statements().get(0).parameters(),
                "TP §9.2: the batch index is bound, not rendered, so both sides' result schemas line up");
    }

    @Test
    void theBatchResultSchemaDeclaresTypesThatLoseNothing() {
        SqlPlan batch = plans(representative()).get(0);

        assertEquals(BATCH_COLUMNS, batch.resultSchema().columns(),
                "validation obligation 13: sums and extrema of exact numerics are read as arbitrary-precision "
                        + "numerics, and a count is a bigint that an empty table answers with nought");
    }

    @Test
    void anIntegerKeysExtremaAreDeclaredWideEnoughForAnUnsignedBigint() {
        SqlPlan keyFacts = plans(representative()).get(1);

        assertEquals(List.of(
                        new ResultSchema.Column("key_not_null_1", "bigint", Nullability.NOT_NULL),
                        new ResultSchema.Column("key_min_1", "numeric(20,0)", Nullability.NULLABLE),
                        new ResultSchema.Column("key_max_1", "numeric(20,0)", Nullability.NULLABLE)),
                keyFacts.resultSchema().columns(),
                "ADR-0037: the target of a BIGINT UNSIGNED key is a numeric(20,0), whose maximum has twenty "
                        + "digits and does not fit a signed long");
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256) over the literal {@link #BATCH_SQL} and {@link #BATCH_COLUMNS}, in a separate Python
     * script quoted on ticket #142 — never copied out of the implementation. The same script reproduces the
     * three pins already in this module, ticket #137's source batch among them.
     */
    @Test
    void theRepresentativeBatchFingerprintIsPinnedFromTheIndependentModel() {
        assertEquals("49b054a11c1b0ba364ef48ef74cf5ffcd9a5c60913ae08449707d7b5404199c8",
                plans(representative()).get(0).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a fact plan's fingerprint must not depend on the JVM, the machine or the run");
    }

    @Test
    void distinctItemsGiveDistinctFingerprints() {
        ValidationColumn total = column("总额", "total", ValidationColumn.Aggregation.EXACT_NUMERIC,
                Nullability.NOT_NULL, false);
        ValidationColumn net = column("net", "net", ValidationColumn.Aggregation.EXACT_NUMERIC,
                Nullability.NOT_NULL, false);
        SqlPlan base = plans(item(List.of(total), List.of())).get(0);
        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "the target table", plans(new ValidationItem(SOURCE,
                        new TargetTableCoordinate(new TargetIdentifier("public"), new TargetIdentifier("orders")),
                        List.of(total), List.of(), false)).get(0),
                "the target schema", plans(new ValidationItem(SOURCE,
                        new TargetTableCoordinate(new TargetIdentifier("dbx"), new TargetIdentifier("订单")),
                        List.of(total), List.of(), false)).get(0),
                "the approved target name", plans(item(List.of(column("总额", "amount",
                        ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false)),
                        List.of())).get(0),
                "the aggregate family", plans(item(List.of(column("总额", "total",
                        ValidationColumn.Aggregation.NONE, Nullability.NOT_NULL, false)), List.of())).get(0),
                "the source nullability", plans(item(List.of(column("总额", "total",
                        ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NULLABLE, false)),
                        List.of())).get(0),
                "the large-record flag", plans(item(List.of(column("总额", "total",
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

    // --- The one shared partition (spec #134; obligation 23) ----------------------------------------

    /**
     * The ruling of this ticket. The two dialects call {@link ValidationFactBatch#partition(ValidationItem)}
     * and neither partitions privately, so the batches are equal batch for batch — which is what lets
     * {@code validation} zip source batch <em>i</em> with target batch <em>i</em> instead of joining them by
     * name.
     */
    @Test
    void theSourceAndTargetPartitionsOfOneItemAreEqualBatchForBatch() {
        for (ValidationItem item : List.of(representative(), item(numericColumns(301), List.of()))) {
            List<ValidationFactBatch> batches = ValidationFactBatch.partition(item);
            List<SqlPlan> source = PAIR.source().validationFactPlans(List.of(item));
            List<SqlPlan> target = PAIR.target().validationFactPlans(List.of(item));

            assertEquals(source.size(), target.size(),
                    "spec #134: the two sides emit the same plans for one item, in the same order");
            for (int i = 0; i < batches.size(); i++) {
                assertEquals(labels(source.get(i)), labels(target.get(i)),
                        "spec #134: batch " + i + " holds the same columns in the same order on both sides, so "
                                + "the result labels are the same too");
                assertEquals(source.get(i).statements().get(0).parameters(),
                        target.get(i).statements().get(0).parameters(),
                        "TP §9.2: the two sides bind the same batch index");
                for (ValidationColumn column : batches.get(i).columns()) {
                    assertTrue(sql(target.get(i)).contains("count(\"" + column.column().target().name() + "\")"),
                            "spec #134: target batch " + i + " is exactly the shared rule's batch " + i);
                }
            }
        }
    }

    @Test
    void aColumnThatEarnsNoFactIsInNoBatchOnThisSideEither() {
        ValidationItem item = item(List.of(
                column("rate", "rate", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NULLABLE,
                        false),
                column("note", "note", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false)), List.of());

        assertEquals(List.of(), ValidationFactBatch.partition(item),
                "TP §9.2: an expression that answers no fact buys a scan and proves nothing");
        assertEquals(List.of(), plans(item), "an item with no fact and no key plans nothing");
    }

    // --- TP §9.2 aggregate families -----------------------------------------------------------------

    @Test
    void floatsDoublesAndBooleansEarnNoExactNumericAggregate() {
        List<SqlPlan> plans = plans(item(List.of(
                column("rate", "rate", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NOT_NULL,
                        false),
                column("ratio", "ratio", ValidationColumn.Aggregation.APPROXIMATE_NUMERIC, Nullability.NOT_NULL,
                        false),
                column("flag", "flag", ValidationColumn.Aggregation.BOOLEAN, Nullability.NOT_NULL, false)),
                List.of()));

        assertEquals(1, plans.size(), "the three columns earn their null counts, so they share one batch");
        assertEquals(List.of("batch_index", "null_count_1", "null_count_2", "null_count_3"), labels(plans.get(0)),
                "TP §9.2: floating types and Booleans are not exact numeric aggregate assertions, so they get "
                        + "no count, sum, min or max — only the null count ADR-0040 adds");
        assertFalse(sql(plans.get(0)).contains("sum(\"rate\")"),
                "TP §9.2: an IEEE sum is order-dependent, so it is never planned");
    }

    @Test
    void aNotNullColumnsNullCountRidesAlongAndANullableColumnGetsNone() {
        List<ValidationColumn> columns = new ArrayList<>(numericColumns(300));
        columns.add(column("note", "note", ValidationColumn.Aggregation.NONE, Nullability.NOT_NULL, false));
        List<ValidationFactBatch> batches = ValidationFactBatch.partition(item(columns, List.of()));

        assertEquals(1, batches.size(),
                "ADR-0040: 非空约束符合性 rides in the existing batch so it costs no extra scan");
        assertEquals(300, batches.get(0).exactNumericColumns().size(),
                "TP §9.2: only exact-numeric columns count toward the 300");
        assertTrue(sql(plans(item(columns, List.of())).get(0))
                        .contains("count(*) - count(\"note\") AS \"null_count_301\""),
                "validation obligation 10: every source NOT NULL contract column gets a null count, as "
                        + "count(*) - count(col) so an empty table answers nought and not NULL");
        assertFalse(labels(plans(item(List.of(exactNumeric("总额", "total")), List.of())).get(0)).stream()
                        .anyMatch(label -> label.startsWith("null_count")),
                "ADR-0040: a nullable column has no null constraint to conform to");
    }

    /**
     * Obligation 23b and ADR-0040. {@code octet_length} counts the bytes of PostgreSQL's own UTF-8 encoding,
     * which is exactly why ticket #143's comparability rule exists: MySQL's {@code CAST(E AS BINARY)} counts
     * source-charset bytes, and the two agree only for the families {@code pair.validationCapabilities} calls
     * comparable. This class measures; it never decides whether the two numbers may be compared.
     */
    @Test
    void aLargeRecordColumnsByteLengthsUseOctetLength() {
        SqlPlan plan = plans(representative()).get(0);

        assertTrue(sql(plan).contains("sum(octet_length(\"payload\")) AS \"large_value_bytes_sum_4\"")
                        && sql(plan).contains("max(octet_length(\"payload\")) AS \"large_value_bytes_max_4\""),
                "ADR-0040: 大记录值完整性 sums and maximises the target's own byte length — " + sql(plan));
        assertTrue(sql(plan).contains("count(\"payload\") AS \"large_value_count_4\""),
                "ADR-0040: the non-null count comes with the two length aggregates");
        assertEquals(new ResultSchema.Column("large_value_bytes_sum_4", "numeric", Nullability.NULLABLE),
                plan.resultSchema().columns().get(13),
                "validation obligation 11: a sum of byte lengths outgrows a bigint on a large record table");
    }

    // --- Key facts (TP §9.2) ------------------------------------------------------------------------

    @Test
    void everyKeyComponentGetsItsOwnNonNullCountAndTheDuplicateCountGroupsByThemAll() {
        ValidationItem item = item(List.of(
                column("客户", "customer", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false),
                column("line no", "line no", ValidationColumn.Aggregation.NONE, Nullability.NOT_NULL, false)),
                List.of(key("客户", ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE),
                        key("line no", ValidationKeyComponent.Ordering.INTEGER)));
        List<SqlPlan> plans = plans(item);

        assertEquals(3, plans.size(), "one batch for the null count, then the key facts and the duplicates");
        assertEquals(List.of("key_not_null_1", "key_not_null_2"), labels(plans.get(1)),
                "TP §9.2: each component's non-nullness is verified");
        assertEquals("SELECT count(\"customer\") AS \"key_not_null_1\", count(\"line no\") AS \"key_not_null_2\" "
                        + "FROM " + TABLE_SQL, sql(plans.get(1)),
                "TP §9.2: components are counted in key order, under their approved target names");
        assertEquals("SELECT count(*) AS \"duplicate_key_values\" FROM (SELECT 1 FROM " + TABLE_SQL
                        + " GROUP BY \"customer\", \"line no\" HAVING count(*) > 1) AS \"duplicate_keys\"",
                sql(plans.get(2)),
                "TP §9.2: uniqueness evidence groups by all typed components and detects any count above one");
    }

    @Test
    void stringCollatedAndCompositeKeyExtremaAreNotPlannedAtAll() {
        ValidationColumn id = column("id", "id", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false);
        ValidationColumn customer =
                column("客户", "customer", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false);
        ValidationColumn line =
                column("line no", "line no", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false);
        List<ValidationItem> unordered = List.of(
                item(List.of(customer), List.of(key("客户", ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE))),
                item(List.of(id, line), List.of(key("id", ValidationKeyComponent.Ordering.INTEGER),
                        key("line no", ValidationKeyComponent.Ordering.INTEGER))),
                item(List.of(id, customer), List.of(key("id", ValidationKeyComponent.Ordering.DATE),
                        key("客户", ValidationKeyComponent.Ordering.NOT_ORDER_COMPARABLE))));

        for (ValidationItem item : unordered) {
            for (SqlPlan plan : plans(item)) {
                assertFalse(sql(plan).contains("min(") || sql(plan).contains("max("),
                        "TP §9.2: a string, collated or composite key has no cross-database order, so DBX marks "
                                + "its extrema not applicable rather than send a query that manufactures one — "
                                + sql(plan));
            }
        }
    }

    @Test
    void eachProvableOrderingDeclaresAnExtremumTypeThatLosesNothing() {
        Map<ValidationKeyComponent.Ordering, String> declared = new TreeMap<>(Map.of(
                ValidationKeyComponent.Ordering.INTEGER, "numeric(20,0)",
                ValidationKeyComponent.Ordering.EXACT_DECIMAL, "numeric",
                ValidationKeyComponent.Ordering.DATE, "date",
                ValidationKeyComponent.Ordering.BYTE_ORDERED_BINARY, "bytea"));
        ValidationColumn id = column("id", "id", ValidationColumn.Aggregation.NONE, Nullability.NULLABLE, false);

        for (Map.Entry<ValidationKeyComponent.Ordering, String> entry : declared.entrySet()) {
            SqlPlan plan = plans(item(List.of(id), List.of(key("id", entry.getKey())))).get(0);
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
        assertEquals(1, plans(item(List.of(exactNumeric("总额", "total")), List.of())).size(),
                "TP §9.2: NOT_APPLICABLE / NO_PRIMARY_KEY is validation's grading, and a key DBX does not have "
                        + "is a key DBX does not query");
    }

    /**
     * A {@link ValidationKeyComponent} carries a source coordinate, and the pair may have renamed the column
     * (TP §7.1), so the target name of a key is known only from the item's approved columns. The source side
     * can plan such an item and this side cannot: it is refused rather than guessed, because the source name
     * may well be the name of a different target column.
     */
    @Test
    void aKeyComponentThatIsNotAnApprovedColumnIsRefusedRatherThanGuessed() {
        ValidationItem item = item(List.of(exactNumeric("总额", "total")),
                List.of(key("id", ValidationKeyComponent.Ordering.INTEGER)));

        assertEquals(3, PAIR.source().validationFactPlans(List.of(item)).size(),
                "the source side renders source names, so it needs no approved name for the key");
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> plans(item));
        assertTrue(refused.getMessage().contains("id"),
                "TP §9.2: the target has no approved name for the key component — " + refused.getMessage());
    }

    // --- Names (obligation 5; TP §7.1) --------------------------------------------------------------

    @Test
    void theApprovedTargetNameIsRenderedAndTheSourceNameNeverReachesTheStatement() {
        for (SqlPlan plan : plans(representative())) {
            assertFalse(sql(plan).contains("总额") || sql(plan).contains("order`s") || sql(plan).contains("shop"),
                    "TP §7.1: the target statement names target objects only, under their approved names — "
                            + sql(plan));
        }
    }

    @Test
    void aHostileTargetNameNeverChangesTheStatementStructureOrTheBindings() {
        String expected = null;
        for (String name : HOSTILE) {
            List<SqlPlan> plans = PAIR.target().validationFactPlans(List.of(hostile(name)));

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

    @Test
    void aTargetNameOverSixtyThreeBytesIsRefusedRatherThanTruncated() {
        for (String name : OVERSIZED) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> PAIR.target().validationFactPlans(List.of(hostile(name))),
                    "TP §7.1: PostgreSQL truncates a longer identifier into a different object");
            assertTrue(refused.getMessage().contains("63 bytes"),
                    "TP §7.1: the refusal names the limit — " + refused.getMessage());
        }
    }

    /** One item whose target table and every target column carry the same hostile name. */
    private static ValidationItem hostile(String name) {
        TargetIdentifier identifier = new TargetIdentifier(name);
        return new ValidationItem(SOURCE,
                new TargetTableCoordinate(new TargetIdentifier("public"), identifier),
                List.of(new ValidationColumn(new ApprovedColumn(at("id"), identifier),
                        ValidationColumn.Aggregation.EXACT_NUMERIC, Nullability.NOT_NULL, false)),
                List.of(key("id", ValidationKeyComponent.Ordering.INTEGER)), false);
    }

    /** How many {@code ?} characters sit inside a double-quoted identifier, and so bind nothing. */
    private static int quotedPlaceholders(String statement) {
        int inside = 0;
        Matcher matcher = QUOTED.matcher(statement);
        while (matcher.find()) {
            inside += matcher.group().length() - matcher.group().replace("?", "").length();
        }
        return inside;
    }

    // --- Boundaries and refusals ---------------------------------------------------------------------

    @Test
    void noItemsPlanNothingAndNoListAtAllIsRefused() {
        assertEquals(List.of(), PAIR.target().validationFactPlans(List.of()),
                "TP §9.2: nothing to validate plans nothing");
        assertThrows(NullPointerException.class, () -> PAIR.target().validationFactPlans(null),
                "ADR-0008 §Ownership: a missing argument is a caller bug, not an empty answer");
    }

    @Test
    void thisModuleGradesNothing() {
        for (SqlPlan plan : plans(representative())) {
            String statement = sql(plan);
            for (String threshold : List.of("1048576", "20971520", "26214400", "67108864", "9223372036854775807")) {
                assertFalse(statement.contains(threshold),
                        "validation obligations 12–20 and 28: dialect plans facts and compares nothing — "
                                + statement);
            }
        }
    }
}
