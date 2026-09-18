package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.KeysetColumn;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.SourceTableMetadata;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code source.baselinePlan} (obligation 19): the source baseline (源基线) of one table as one bounded
 * aggregate query — the exact {@code COUNT(*)} and, when the table has a keyset column (键集列), that
 * column's terminal values (ADR-0037 §The keyset column; TP §4 step 8).
 *
 * <p>The statement carries, in this order:
 *
 * <ol>
 *   <li>{@code COUNT(*)}, always. It is the boundary a run is finished against, so it is exact and never
 *       the 预估行数 the slice-5 statistics carry;
 *   <li>with a keyset column, {@link PreflightScan#minimum} and {@link PreflightScan#maximum} of it —
 *       the very rendering obligation 17's keyset extrema use, called and never re-derived.
 * </ol>
 *
 * <p><strong>Without a keyset column the count is the whole plan</strong>: exactly one result column.
 * {@code validation} obligation 23 compares only the count for such a table, because its baseline holds
 * only the count, so inventing a pair of always-{@code NULL} extrema columns here would give drift a
 * fact that is not evidence of anything.
 *
 * <p>The extrema are declared {@link PreflightScan#UNSIGNED_SAFE_DECIMAL} rather than {@code bigint}: a
 * {@code BIGINT UNSIGNED} terminal value read as a signed 64-bit integer wraps 2^64-1 to -1, and that
 * overflow is exactly what ADR-0037 §The keyset column blocks.
 *
 * <p>This class grades nothing. Whether the minimum is 0 or more and the maximum at most 2^63-1 is
 * {@code validation}'s evaluation (validation obligation 22) against the facts this plan reads; a guard
 * here would refuse to even measure the table whose measurement is the finding. Nor does the plan know
 * of occasions: the same shape serves {@code validation.driftPlan} (validation obligation 23), so
 * 运行前 and 收口 are one plan to {@code dialect} and two readings to {@code validation}.
 */
final class BaselineRead {

    /** The exact row count. {@code COUNT(*)} answers on an empty table too, so it is never {@code NULL}. */
    static final String ROW_COUNT = "row_count";

    /** The keyset column's terminal values; an empty table answers {@code NULL} for both. */
    static final String KEYSET_MIN = "keyset_min";
    static final String KEYSET_MAX = "keyset_max";

    /** A MySQL {@code COUNT(*)} is a {@code bigint}. */
    private static final String COUNT_TYPE = "bigint";

    private BaselineRead() {
    }

    static SqlPlan plan(SourceTableMetadata table, Optional<KeysetColumn> keysetColumn) {
        Objects.requireNonNull(table, "table is required");
        Objects.requireNonNull(keysetColumn, "keysetColumn is required");

        List<String> selectItems = new ArrayList<>();
        List<ResultSchema.Column> columns = new ArrayList<>();
        selectItems.add("COUNT(*) AS " + MySqlIdentifier.quoted(ROW_COUNT));
        columns.add(new ResultSchema.Column(ROW_COUNT, COUNT_TYPE, Nullability.NOT_NULL));

        if (keysetColumn.isPresent()) {
            ColumnCoordinate column = columnOfTable(table, keysetColumn.get().column());
            selectItems.add(PreflightScan.minimum(column) + " AS " + MySqlIdentifier.quoted(KEYSET_MIN));
            selectItems.add(PreflightScan.maximum(column) + " AS " + MySqlIdentifier.quoted(KEYSET_MAX));
            columns.add(new ResultSchema.Column(KEYSET_MIN, PreflightScan.UNSIGNED_SAFE_DECIMAL,
                    Nullability.NULLABLE));
            columns.add(new ResultSchema.Column(KEYSET_MAX, PreflightScan.UNSIGNED_SAFE_DECIMAL,
                    Nullability.NULLABLE));
        }

        String sql = "SELECT " + String.join(", ", selectItems)
                + " FROM " + MySqlIdentifier.qualified(table.table());

        return new SqlPlan(
                OperationKind.SOURCE_BASELINE_READ,
                List.of(new ParameterizedStatement(sql, List.of())),
                new ResultSchema(columns, ResultSchema.Cardinality.EXACTLY_ONE_ROW),
                TimeoutClass.EXACT_SCAN,
                Set.of(RequiredPrivilege.SOURCE_SELECT),
                EvidencePolicy.STATEMENT_AND_AGGREGATES);
    }

    private static ColumnCoordinate columnOfTable(SourceTableMetadata table, ColumnCoordinate column) {
        for (SourceColumn candidate : table.columns()) {
            if (candidate.coordinate().equals(column)) {
                return column;
            }
        }
        throw new IllegalArgumentException("ADR-0037 §The keyset column: the keyset column names " + column
                + ", which is not a column of " + table.table() + ", so the caller merged the wrong lists");
    }
}
