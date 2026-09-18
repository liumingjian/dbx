package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TimeoutClass;
import com.dbx.dialect.api.ValidationColumn;
import com.dbx.dialect.api.ValidationFactBatch;
import com.dbx.dialect.api.ValidationItem;
import com.dbx.dialect.api.ValidationKeyComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code source.validationFactPlans} (obligations 23 and 23b): TP §9.2's automatic checks as source-side
 * aggregate plans, with ADR-0040's two extra checks riding in the same scan.
 *
 * <p>Per {@link ValidationItem}, in this order:
 *
 * <ol>
 *   <li>one plan per {@link ValidationFactBatch}, in batch index order. Each batch carries, in turn, the
 *       exact-numeric {@code COUNT}/{@code SUM}/{@code MIN}/{@code MAX} (TP §9.2), the 非空约束符合性
 *       (null constraint conformance) null counts (ADR-0040; {@code validation} obligation 10) and the
 *       大记录值完整性 (large record value integrity) byte-length aggregates (ADR-0040; obligation 11).
 *       The last two ride along because ADR-0040 added them to cost no extra scan;
 *   <li>the key facts plan: one non-null count per key component, plus {@code MIN} and {@code MAX} for a
 *       single key whose ordering the pair can prove (TP §9.2);
 *   <li>the key duplicate plan: how many key values occur more than once, from a group-by over all typed
 *       components (TP §9.2).
 * </ol>
 *
 * <p><strong>This class does not partition.</strong> {@link ValidationFactBatch#partition(ValidationItem)}
 * is the one rule, shared with the target dialect, so source batch <em>i</em> and target batch <em>i</em>
 * hold the same columns in the same order and {@code validation} pairs them by index.
 *
 * <p><strong>Labels come from a fixed scheme</strong>, never from a column name, which is not a legal result
 * label everywhere and moves on a rename: the column's one-based ordinal <em>within its batch</em> follows
 * the kind, and the key labels carry the component's one-based ordinal in key order. The batch index is
 * bound as a value and selected as {@code batch_index}, so a plan read on its own still names the batch
 * {@code validation} must pair it with.
 *
 * <p><strong>Types that lose nothing.</strong> Sums and extrema of exact numerics are declared plain
 * {@code decimal}: {@code evaluate} compares arbitrary-precision decimals ({@code validation} obligation 13)
 * and a sum of {@code BIGINT UNSIGNED} outgrows twenty digits while a sum of {@code DECIMAL(30,10)} keeps a
 * scale, so {@link PreflightScan#UNSIGNED_SAFE_DECIMAL} — which is exactly twenty digits and no scale — is
 * deliberately <em>not</em> reused for them. It is reused for an integer key's extrema, where the value is a
 * key and {@code BIGINT UNSIGNED} is its widest domain.
 *
 * <p>This class grades nothing. Every comparison, every reason code and the whole
 * {@code NOT_APPLICABLE / NO_PRIMARY_KEY} question belong to {@code validation} (obligations 12–20).
 */
final class ValidationFacts {

    /** A count is a count of rows: a MySQL {@code COUNT} is a {@code bigint} whatever it counts. */
    private static final String COUNT_TYPE = "bigint";

    /**
     * An unconstrained decimal: the widest exact-numeric domain MySQL names. The result is read as an
     * arbitrary-precision decimal, which is what {@code validation} obligation 13 compares.
     */
    private static final String DECIMAL_TYPE = "decimal";

    private ValidationFacts() {
    }

    static List<SqlPlan> plans(List<ValidationItem> items) {
        Objects.requireNonNull(items, "items are required");
        List<SqlPlan> plans = new ArrayList<>();
        for (ValidationItem item : items) {
            Objects.requireNonNull(item, "a validation item is required");
            for (ValidationFactBatch batch : ValidationFactBatch.partition(item)) {
                plans.add(batchPlan(batch));
            }
            if (!item.keyComponents().isEmpty()) {
                plans.add(keyFactsPlan(item));
                plans.add(keyDuplicatePlan(item));
            }
        }
        return List.copyOf(plans);
    }

    /** One batch, one bounded aggregate query: TP §9.2's four expressions plus ADR-0040's two checks. */
    private static SqlPlan batchPlan(ValidationFactBatch batch) {
        ValidationItem item = batch.item();
        List<Aggregate> aggregates = new ArrayList<>();
        aggregates.add(new Aggregate("?", "batch_index", COUNT_TYPE, Nullability.NOT_NULL));

        for (ValidationColumn column : batch.exactNumericColumns()) {
            String read = QueryProjection.columnExpression(column.source());
            int ordinal = batch.ordinalOf(column);
            aggregates.add(new Aggregate("COUNT(" + read + ")", "count_" + ordinal, COUNT_TYPE,
                    Nullability.NOT_NULL));
            aggregates.add(nullable("SUM(" + read + ")", "sum_" + ordinal, DECIMAL_TYPE));
            aggregates.add(nullable("MIN(" + read + ")", "min_" + ordinal, DECIMAL_TYPE));
            aggregates.add(nullable("MAX(" + read + ")", "max_" + ordinal, DECIMAL_TYPE));
        }

        // 非空约束符合性: how many rows hold a NULL where the approved contract says the source has none.
        // COUNT(*) - COUNT(E), never SUM(CASE …), so an empty table answers an honest nought rather than
        // a NULL validation obligation 19 would have to read as one.
        for (ValidationColumn column : batch.nullCountColumns()) {
            String read = QueryProjection.columnExpression(column.source());
            aggregates.add(new Aggregate("COUNT(*) - COUNT(" + read + ")",
                    "null_count_" + batch.ordinalOf(column), COUNT_TYPE, Nullability.NOT_NULL));
        }

        // 大记录值完整性: length, never content. The byte-length expression is PreflightScan's, called and
        // never re-derived, so the check measures the very bytes ADR-0003 fixed for the envelope.
        for (ValidationColumn column : batch.largeRecordColumns()) {
            ColumnCoordinate source = column.source();
            String bytes = PreflightScan.byteLength(source);
            int ordinal = batch.ordinalOf(column);
            aggregates.add(new Aggregate("COUNT(" + QueryProjection.columnExpression(source) + ")",
                    "large_value_count_" + ordinal, COUNT_TYPE, Nullability.NOT_NULL));
            aggregates.add(nullable("SUM(" + bytes + ")", "large_value_bytes_sum_" + ordinal, DECIMAL_TYPE));
            aggregates.add(nullable("MAX(" + bytes + ")", "large_value_bytes_max_" + ordinal, COUNT_TYPE));
        }

        return plan(select(aggregates, item), List.of(new SqlValue.Int64(batch.index())), aggregates);
    }

    /** Per-component non-nullness, and extrema only where TP §9.2 can prove the two engines agree. */
    private static SqlPlan keyFactsPlan(ValidationItem item) {
        List<Aggregate> aggregates = new ArrayList<>();
        List<ValidationKeyComponent> components = item.keyComponents();
        for (int i = 0; i < components.size(); i++) {
            String read = QueryProjection.columnExpression(components.get(i).column());
            aggregates.add(new Aggregate("COUNT(" + read + ")", "key_not_null_" + (i + 1), COUNT_TYPE,
                    Nullability.NOT_NULL));
        }
        if (item.keyExtremaComparable()) {
            ValidationKeyComponent component = components.get(0);
            String type = extremumType(component);
            aggregates.add(nullable(PreflightScan.minimum(component.column()), "key_min_1", type));
            aggregates.add(nullable(PreflightScan.maximum(component.column()), "key_max_1", type));
        }
        return plan(select(aggregates, item), List.of(), aggregates);
    }

    /**
     * TP §9.2's fallback uniqueness evidence: group by all typed components and detect any count above one.
     * Whether a proven target constraint makes this unnecessary is {@code validation}'s judgement
     * (obligation 8), so the fact is always planned and never graded here.
     */
    private static SqlPlan keyDuplicatePlan(ValidationItem item) {
        String key = item.keyComponents().stream()
                .map(component -> QueryProjection.columnExpression(component.column()))
                .collect(Collectors.joining(", "));
        Aggregate duplicates = new Aggregate("COUNT(*)", "duplicate_key_values", COUNT_TYPE, Nullability.NOT_NULL);
        String sql = "SELECT " + duplicates.selectItem() + " FROM (SELECT 1 FROM "
                + MySqlIdentifier.qualified(item.source()) + " GROUP BY " + key + " HAVING COUNT(*) > 1) AS "
                + MySqlIdentifier.quoted("duplicate_keys");
        return plan(sql, List.of(), List.of(duplicates));
    }

    private static String select(List<Aggregate> aggregates, ValidationItem item) {
        return "SELECT " + aggregates.stream().map(Aggregate::selectItem).collect(Collectors.joining(", "))
                + " FROM " + MySqlIdentifier.qualified(item.source());
    }

    private static SqlPlan plan(String sql, List<SqlValue> parameters, List<Aggregate> aggregates) {
        return new SqlPlan(
                OperationKind.SOURCE_VALIDATION_FACTS,
                List.of(new ParameterizedStatement(sql, parameters)),
                new ResultSchema(aggregates.stream().map(Aggregate::resultColumn).toList(),
                        ResultSchema.Cardinality.EXACTLY_ONE_ROW),
                TimeoutClass.EXACT_SCAN,
                Set.of(RequiredPrivilege.SOURCE_SELECT),
                EvidencePolicy.STATEMENT_AND_AGGREGATES);
    }

    /**
     * The MySQL type an extremum of a comparable key is read as. An integer key's widest domain is
     * {@code BIGINT UNSIGNED}, whose maximum wraps to -1 when read as a signed 64-bit integer, so it takes
     * {@link PreflightScan#UNSIGNED_SAFE_DECIMAL} — the same spelling the preflight scan fixed. An exact
     * decimal key keeps an unknown scale, so it takes the unconstrained decimal instead.
     */
    private static String extremumType(ValidationKeyComponent component) {
        return switch (component.ordering()) {
            case INTEGER -> PreflightScan.UNSIGNED_SAFE_DECIMAL;
            case EXACT_DECIMAL -> DECIMAL_TYPE;
            case DATE -> "date";
            case BYTE_ORDERED_BINARY -> "varbinary";
            case NOT_ORDER_COMPARABLE -> throw new IllegalStateException("TP §9.2: " + component.column()
                    + " has no cross-database order, so no extremum is planned for it");
        };
    }

    /** An empty table gives {@code NULL}, so every {@code SUM}, {@code MIN} and {@code MAX} is nullable. */
    private static Aggregate nullable(String expression, String label, String databaseType) {
        return new Aggregate(expression, label, databaseType, Nullability.NULLABLE);
    }

    /** One selected expression: its SQL, its stable label and the type the caller must read it as. */
    private record Aggregate(String expression, String label, String databaseType, Nullability nullability) {

        String selectItem() {
            return expression + " AS " + MySqlIdentifier.quoted(label);
        }

        ResultSchema.Column resultColumn() {
            return new ResultSchema.Column(label, databaseType, nullability);
        }
    }
}
