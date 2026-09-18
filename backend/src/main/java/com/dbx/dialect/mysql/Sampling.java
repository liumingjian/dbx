package com.dbx.dialect.mysql;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SamplingKey;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code source.samplingPlan}: TP §9.3's manual deterministic sampling as plans, one plan per statement so
 * every plan keeps one honest cardinality (spec #134 correction 4). Obligation 23.
 *
 * <p>This class renders and nothing else. The evenly spaced seek thresholds are arbitrary-precision
 * arithmetic on data and belong to {@code validation} (TP §9.3; validation obligation 26), which also
 * requires the primary key, normalizes and deduplicates the keys and reports the actual sample count. The
 * only number this class derives is the split of the caller's {@code n} into ⌈n/2⌉ and ⌊n/2⌋, which is
 * arithmetic on a row count, never on a data value. A threshold is bound exactly as it was handed over, in
 * the order it was handed over; it is never inspected, converted or re-typed. A temporal key is no
 * exception, even though {@code SqlValue} has no temporal variant: {@code validation} chooses how a date
 * threshold binds, and this class binds it.
 *
 * <p>Evidence is {@link EvidencePolicy#STATEMENT_ONLY} on every plan: a sample reads customer rows, and
 * ADR-0028 lets no record value or primary-key value be persisted as evidence, not even on request.
 */
final class Sampling {

    private Sampling() {
    }

    /**
     * The ordered plans of one sample of {@code n} rows.
     *
     * <p>With seek thresholds, one {@code AT_MOST_ONE_ROW} seek per threshold in threshold order; the list
     * has one threshold per sampled row, so {@code seekThresholds.size()} must equal {@code n}. Without
     * them, exactly two {@code ANY_NUMBER_OF_ROWS} plans — the first ⌈n/2⌉ rows ascending and the last
     * ⌊n/2⌋ rows descending, in typed source key order. Two plans are returned whatever {@code n} is, so
     * the shape of the list never depends on the row count; {@code n = 1} therefore returns a descending
     * plan bounded to zero rows.
     */
    static List<SqlPlan> plans(SamplingKey key, int n) {
        Objects.requireNonNull(key, "a sampling key is required");
        if (n < 1) {
            throw new IllegalArgumentException("TP §9.3: a sample reads n rows, so n must be positive, was " + n);
        }
        List<SqlValue> thresholds = key.seekThresholds().orElse(null);
        if (thresholds == null) {
            // n - n / 2 is ⌈n/2⌉ without the overflow (n + 1) / 2 has at Integer.MAX_VALUE.
            return List.of(window(key, Direction.ASC, n - n / 2), window(key, Direction.DESC, n / 2));
        }
        if (thresholds.size() != n) {
            throw new IllegalArgumentException("TP §9.3: evenly spaced seek thresholds are one per sampled row, so a "
                    + "sample of " + n + " rows needs " + n + " thresholds, not " + thresholds.size());
        }
        List<SqlPlan> plans = new ArrayList<>(thresholds.size());
        for (SqlValue threshold : thresholds) {
            plans.add(seek(key, threshold));
        }
        return List.copyOf(plans);
    }

    /**
     * {@code SELECT <key> FROM <table> WHERE <key> >= ? ORDER BY <key> ASC LIMIT 1}: the first row at or
     * after one threshold. {@code AT_MOST_ONE_ROW}, because a threshold past the last key finds none —
     * that is a smaller sample for {@code validation} to report, not a failure.
     *
     * <p>{@code LIMIT 1} is the shape of a seek and is not derived from any argument, so it is written out;
     * the threshold is the caller's value and is bound.
     */
    private static SqlPlan seek(SamplingKey key, SqlValue threshold) {
        String column = quoted(key.keyColumns().get(0));
        String sql = "SELECT " + column + " FROM " + MySqlIdentifier.qualified(key.table())
                + " WHERE " + column + " >= ? ORDER BY " + column + " " + Direction.ASC.keyword + " LIMIT 1";
        return plan(sql, List.of(threshold), key, ResultSchema.Cardinality.AT_MOST_ONE_ROW);
    }

    /**
     * {@code SELECT <key cols> FROM <table> ORDER BY <key cols> <dir> LIMIT ?}: one end of the typed source
     * key order. The row count is the caller's {@code n} and is bound, like every other caller-supplied
     * value (ADR-0008 §Plans); no value is spliced into the text.
     */
    private static SqlPlan window(SamplingKey key, Direction direction, int rows) {
        List<String> columns = key.keyColumns().stream().map(Sampling::quoted).toList();
        String sql = "SELECT " + String.join(", ", columns) + " FROM " + MySqlIdentifier.qualified(key.table())
                + " ORDER BY " + String.join(", ", columns.stream().map(c -> c + " " + direction.keyword).toList())
                + " LIMIT ?";
        return plan(sql, List.of(new SqlValue.Int64(rows)), key, ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS);
    }

    private static SqlPlan plan(String sql, List<SqlValue> parameters, SamplingKey key,
            ResultSchema.Cardinality cardinality) {
        return new SqlPlan(
                OperationKind.SOURCE_SAMPLING,
                List.of(new ParameterizedStatement(sql, parameters)),
                schema(key, cardinality),
                TimeoutClass.EXACT_SCAN,
                Set.of(RequiredPrivilege.SOURCE_SELECT),
                EvidencePolicy.STATEMENT_ONLY);
    }

    /**
     * The key columns in key order, each declared under the source's own type name. A seek projects only the
     * single key column it compares, so it declares only that one.
     *
     * <p>{@code NOT_NULL}: TP §9.3 samples through the primary key, whose components are all {@code NOT
     * NULL}. Requiring that key is {@code validation}'s (obligation 26), so this plan declares the schema
     * that requirement implies rather than re-checking it here.
     */
    private static ResultSchema schema(SamplingKey key, ResultSchema.Cardinality cardinality) {
        List<SamplingKey.KeyColumn> columns = cardinality == ResultSchema.Cardinality.AT_MOST_ONE_ROW
                ? List.of(key.keyColumns().get(0))
                : key.keyColumns();
        return new ResultSchema(columns.stream()
                .map(c -> new ResultSchema.Column(c.column().column(), c.databaseType(), Nullability.NOT_NULL))
                .toList(), cardinality);
    }

    /**
     * The one text a key column becomes, so the projection, the {@code WHERE} and the {@code ORDER BY} of a
     * statement are byte-identical and cannot drift apart.
     */
    private static String quoted(SamplingKey.KeyColumn keyColumn) {
        return MySqlIdentifier.quoted(keyColumn.column().column());
    }

    private enum Direction {
        ASC("ASC"),
        DESC("DESC");

        private final String keyword;

        Direction(String keyword) {
            this.keyword = keyword;
        }
    }
}
