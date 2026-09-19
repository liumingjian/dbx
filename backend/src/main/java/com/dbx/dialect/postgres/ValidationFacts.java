package com.dbx.dialect.postgres;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TargetIdentifier;
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
 * {@code target.validationFactPlans} (obligation 23, target half): the PostgreSQL mirror of
 * {@code source.validationFactPlans}. TP §9.2's automatic checks as target-side aggregate plans, with
 * ADR-0040's 非空约束符合性 (null constraint conformance) and 大记录值完整性 (large record value integrity)
 * riding in the same scan.
 *
 * <p>Per {@link ValidationItem}, in the same order as the source side emits them: one plan per
 * {@link ValidationFactBatch} in batch index order, then the key facts plan, then the key duplicate plan.
 * The labels are the same fixed scheme too, so a reader holding both result sets can line them up by eye
 * as well as by index.
 *
 * <p><strong>This class does not partition.</strong> {@link ValidationFactBatch#partition(ValidationItem)}
 * is the one rule, shared with the source dialect and called by both; it is a function of the item alone,
 * so target batch <em>i</em> holds the same columns in the same order as source batch <em>i</em> and
 * {@code validation} pairs them by index — a zip, not a join. A second partition here, however carefully
 * written, is the bug that reports a {@code FAIL} because source column 17 met target column 18.
 *
 * <p><strong>Target names, not source names.</strong> Every column reaches SQL as the approved target
 * identifier of its {@code ApprovedColumn}, through {@link PostgresIdentifier}, because the pair may have
 * renamed it (TP §7.1). That is also why a key component has to be an approved column: a
 * {@link ValidationKeyComponent} carries a <em>source</em> coordinate, and the only statement of what that
 * column is called on the target is the item's approved column list. Reading the source name as a target
 * name would query whichever column happened to share the name.
 *
 * <p><strong>Types that lose nothing.</strong> A count is a {@code bigint} an empty table answers with
 * nought. Sums and extrema of exact numerics are declared {@code numeric}: {@code evaluate} compares
 * arbitrary-precision decimals ({@code validation} obligation 13), and the target of a MySQL
 * {@code BIGINT UNSIGNED} is itself a {@code numeric(20,0)}, so reading either into a Java {@code long}
 * would be the lossy read ADR-0037 exists to prevent.
 *
 * <p><strong>{@code octet_length}, deliberately.</strong> PostgreSQL counts the bytes of its own UTF-8
 * encoding, which is why the two sides' byte lengths agree only for the type families ADR-0040 calls
 * comparable and why {@code pair.validationCapabilities} answers that question at all. Whether a given
 * column's two lengths may be compared is {@code validation}'s to ask and the pair's to answer; this class
 * measures the target's bytes and says nothing about them.
 *
 * <p>This class grades nothing: every comparison, reason code and conclusion is {@code validation}'s
 * (obligations 12–20). That any of these statements runs on a real PostgreSQL 15 is not provable at L1 —
 * {@code dialect.md} §Verification puts it at L2 in {@code contract} slice 8 through {@code gateway}.
 */
final class ValidationFacts {

    /** A PostgreSQL {@code count} is a {@code bigint} whatever it counts, and an empty table answers nought. */
    private static final String COUNT_TYPE = "bigint";

    /**
     * An unconstrained {@code numeric}: PostgreSQL's arbitrary-precision exact type, and the type the
     * target of every exact-numeric MySQL source type widens into without loss.
     */
    private static final String NUMERIC_TYPE = "numeric";

    /**
     * The widest integer key domain the pair maps: MySQL's {@code BIGINT UNSIGNED} becomes a
     * {@code numeric(20,0)} target column, whose maximum has twenty digits and no scale.
     */
    private static final String UNSIGNED_SAFE_NUMERIC = "numeric(20,0)";

    /**
     * Reaching an object through its schema needs {@code USAGE} on that schema, which is what
     * {@code TARGET_READ_CATALOG} asks the DBA for; reading the rows of a DBX-owned table is
     * {@code TARGET_OWN_OBJECT}. Nothing here creates, writes or drops anything.
     */
    private static final Set<RequiredPrivilege> PRIVILEGES =
            Set.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT);

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
        // The index is bound, never rendered, so the two sides' result schemas are the same shape and a
        // plan read on its own still names the batch validation must pair it with.
        aggregates.add(new Aggregate("?", "batch_index", COUNT_TYPE, Nullability.NOT_NULL));

        for (ValidationColumn column : batch.exactNumericColumns()) {
            String read = read(column);
            int ordinal = batch.ordinalOf(column);
            aggregates.add(new Aggregate("count(" + read + ")", "count_" + ordinal, COUNT_TYPE,
                    Nullability.NOT_NULL));
            aggregates.add(nullable("sum(" + read + ")", "sum_" + ordinal, NUMERIC_TYPE));
            aggregates.add(nullable("min(" + read + ")", "min_" + ordinal, NUMERIC_TYPE));
            aggregates.add(nullable("max(" + read + ")", "max_" + ordinal, NUMERIC_TYPE));
        }

        // 非空约束符合性: how many rows hold a NULL where the approved contract says the source has none.
        // count(*) - count(E), never sum(CASE …), so an empty table answers an honest nought rather than
        // the NULL validation obligation 19 would have to read as one.
        for (ValidationColumn column : batch.nullCountColumns()) {
            aggregates.add(new Aggregate("count(*) - count(" + read(column) + ")",
                    "null_count_" + batch.ordinalOf(column), COUNT_TYPE, Nullability.NOT_NULL));
        }

        // 大记录值完整性: length, never content. octet_length counts PostgreSQL's own UTF-8 bytes, the very
        // asymmetry ADR-0040's comparability rule is about; the target's bytes are measured, not judged.
        for (ValidationColumn column : batch.largeRecordColumns()) {
            String read = read(column);
            String bytes = "octet_length(" + read + ")";
            int ordinal = batch.ordinalOf(column);
            aggregates.add(new Aggregate("count(" + read + ")", "large_value_count_" + ordinal, COUNT_TYPE,
                    Nullability.NOT_NULL));
            aggregates.add(nullable("sum(" + bytes + ")", "large_value_bytes_sum_" + ordinal, NUMERIC_TYPE));
            aggregates.add(nullable("max(" + bytes + ")", "large_value_bytes_max_" + ordinal, COUNT_TYPE));
        }

        return plan(select(aggregates, item), List.of(new SqlValue.Int64(batch.index())), aggregates);
    }

    /** Per-component non-nullness, and extrema only where TP §9.2 can prove the two engines agree. */
    private static SqlPlan keyFactsPlan(ValidationItem item) {
        List<Aggregate> aggregates = new ArrayList<>();
        List<ValidationKeyComponent> components = item.keyComponents();
        for (int i = 0; i < components.size(); i++) {
            aggregates.add(new Aggregate("count(" + keyRead(item, components.get(i)) + ")",
                    "key_not_null_" + (i + 1), COUNT_TYPE, Nullability.NOT_NULL));
        }
        if (item.keyExtremaComparable()) {
            ValidationKeyComponent component = components.get(0);
            String read = keyRead(item, component);
            String type = extremumType(component);
            aggregates.add(nullable("min(" + read + ")", "key_min_1", type));
            aggregates.add(nullable("max(" + read + ")", "key_max_1", type));
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
                .map(component -> keyRead(item, component))
                .collect(Collectors.joining(", "));
        Aggregate duplicates = new Aggregate("count(*)", "duplicate_key_values", COUNT_TYPE, Nullability.NOT_NULL);
        String sql = "SELECT " + duplicates.selectItem() + " FROM (SELECT 1 FROM "
                + PostgresIdentifier.qualified(item.target()) + " GROUP BY " + key + " HAVING count(*) > 1) AS "
                + quotedLabel("duplicate_keys");
        return plan(sql, List.of(), List.of(duplicates));
    }

    private static String select(List<Aggregate> aggregates, ValidationItem item) {
        return "SELECT " + aggregates.stream().map(Aggregate::selectItem).collect(Collectors.joining(", "))
                + " FROM " + PostgresIdentifier.qualified(item.target());
    }

    private static SqlPlan plan(String sql, List<SqlValue> parameters, List<Aggregate> aggregates) {
        return new SqlPlan(
                OperationKind.TARGET_VALIDATION_FACTS,
                List.of(new ParameterizedStatement(sql, parameters)),
                new ResultSchema(aggregates.stream().map(Aggregate::resultColumn).toList(),
                        ResultSchema.Cardinality.EXACTLY_ONE_ROW),
                TimeoutClass.EXACT_SCAN,
                PRIVILEGES,
                EvidencePolicy.STATEMENT_AND_AGGREGATES);
    }

    /** The approved target name of one validated column, quoted: the only name this side may use. */
    private static String read(ValidationColumn column) {
        return PostgresIdentifier.quoted(column.column().target());
    }

    /**
     * The approved target name of one key component. A {@link ValidationKeyComponent} names a source
     * coordinate, so the name it goes by on the target is known only from the item's approved columns; a
     * key the contract did not approve is refused rather than guessed, because a rename (TP §7.1) makes the
     * source name the name of some other column, or of none.
     */
    private static String keyRead(ValidationItem item, ValidationKeyComponent component) {
        for (ValidationColumn column : item.columns()) {
            if (column.source().equals(component.column())) {
                return PostgresIdentifier.quoted(column.column().target());
            }
        }
        throw new IllegalArgumentException("TP §9.2: " + component.column() + " is a key component of " + item.source()
                + " but not one of its approved columns, so this dialect has no approved target name for it; the "
                + "source name is not one, because the pair may have renamed the column (TP §7.1)");
    }

    /**
     * The PostgreSQL type an extremum of a comparable key is read as. An integer key's widest target domain
     * is {@code numeric(20,0)} — what the pair maps {@code BIGINT UNSIGNED} to — whose maximum has twenty
     * digits and does not fit a signed 64-bit integer. An exact decimal key keeps an unknown scale, so it
     * takes the unconstrained {@code numeric} instead.
     */
    private static String extremumType(ValidationKeyComponent component) {
        return switch (component.ordering()) {
            case INTEGER -> UNSIGNED_SAFE_NUMERIC;
            case EXACT_DECIMAL -> NUMERIC_TYPE;
            case DATE -> "date";
            case BYTE_ORDERED_BINARY -> "bytea";
            case NOT_ORDER_COMPARABLE -> throw new IllegalStateException("TP §9.2: " + component.column()
                    + " has no cross-database order, so no extremum is planned for it");
        };
    }

    /** An empty table gives {@code NULL}, so every {@code sum}, {@code min} and {@code max} is nullable. */
    private static Aggregate nullable(String expression, String label, String databaseType) {
        return new Aggregate(expression, label, databaseType, Nullability.NULLABLE);
    }

    /** A result label from the fixed scheme, quoted the one way a name becomes PostgreSQL text. */
    private static String quotedLabel(String label) {
        return PostgresIdentifier.quoted(new TargetIdentifier(label));
    }

    /** One selected expression: its SQL, its stable label and the type the caller must read it as. */
    private record Aggregate(String expression, String label, String databaseType, Nullability nullability) {

        String selectItem() {
            return expression + " AS " + quotedLabel(label);
        }

        ResultSchema.Column resultColumn() {
            return new ResultSchema.Column(label, databaseType, nullability);
        }
    }
}
