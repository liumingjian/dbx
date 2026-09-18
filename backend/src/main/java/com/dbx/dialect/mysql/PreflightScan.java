package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ApprovedColumn;
import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.KeysetCandidate;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.PreflightObligation;
import com.dbx.dialect.api.RequiredPreflight;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.SourceTableMetadata;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code source.preflightScanPlan} (obligations 16 and 17): one table's whole preflight merged into
 * <strong>one</strong> bounded aggregate query (ADR-0003 ¶2; TP §6.6; {@code preflight} obligations 5–7).
 * Never one scan per column, and never a scan the table does not need.
 *
 * <p>The statement carries, in this order:
 *
 * <ol>
 *   <li>the envelope (obligation 16): {@code MAX} of {@link #byteLength} per approved column, then
 *       {@code MAX} of their sum once, as the row payload. A pruned column is in neither, because
 *       {@code approvedColumns} — not the table — is what the run reads ({@code preflight} obligation 6);
 *   <li>one type-domain aggregate per obligation that demands one, in the obligations' own order;
 *   <li>the keyset extrema (obligation 17): {@code MIN} and {@code MAX} per {@link KeysetCandidates}
 *       candidate.
 * </ol>
 *
 * <p>Type-domain checks are <strong>counts of offending rows, never values</strong> (ADR-0028), the one
 * exception being {@code UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE}, where the number itself is the evidence
 * a DBA is shown. Counted aggregates are {@code SUM(CASE … THEN 1 ELSE 0 END)} rather than {@code COUNT},
 * so that — like every {@code MIN} and {@code MAX} here — an empty table yields {@code NULL} and every
 * result column is honestly {@link Nullability#NULLABLE}.
 *
 * <p><strong>Labels come from a fixed scheme</strong>, never from a column name, which is not a legal result
 * label everywhere: {@code value_bytes_<n>} and each type-domain kind carry the column's one-based ordinal in
 * {@code approvedColumns}, {@code keyset_min_<n>}/{@code keyset_max_<n>} carry the candidate's one-based
 * ordinal in {@code source.keysetCandidates} (a candidate need not be an approved column at all), and
 * {@code row_bytes} is one per table. So a label is always ASCII letters, underscores and digits, and it
 * stays stable when a column is renamed.
 *
 * <p>This class grades nothing. 20 MiB, 1 MiB, 2^63-1 and 64 MiB are compared in {@code preflight}
 * (spec #134 correction 6), and {@code AUTO_INCREMENT_NEXT_VALUE_WITHIN_SIGNED_RANGE} plans no column at
 * all: the next auto-increment value is {@code TableStatistics.autoIncrement}, an
 * {@code information_schema.TABLES} fact the slice-5 read already returns (correction 5).
 *
 * <p><strong>Keyset candidates are not filtered by the approved selection</strong> (spec #134 gap 3).
 * A pruned candidate cannot be the incrementing column of a query-mode read, but that rule belongs to
 * {@code preflight} obligations 16–18, which sees the whole approval; this class plans extrema for every
 * candidate {@code source.keysetCandidates} reports and invents no rule of its own.
 */
final class PreflightScan {

    /**
     * The declared type of a value that can be a {@code BIGINT UNSIGNED} maximum. Reading such a value as a
     * signed 64-bit integer wraps 2^64-1 to -1, which would turn the one check that exists to prove the
     * maximum fits into a silent lie (ADR-0037; TP §6.6 check 3). The spelling is lowercase MySQL type text,
     * because {@code ResultSchema.Column.databaseType} is "the database's own" type name.
     */
    static final String UNSIGNED_SAFE_DECIMAL = "decimal(20,0)";

    /** Counts and byte lengths: a MySQL aggregate over them is at most a {@code bigint}. */
    private static final String COUNT_TYPE = "bigint";

    private PreflightScan() {
    }

    static SqlPlan plan(SourceTableMetadata table, List<ApprovedColumn> approvedColumns,
            List<PreflightObligation> obligations) {
        Objects.requireNonNull(table, "table is required");
        Objects.requireNonNull(approvedColumns, "approvedColumns are required");
        Objects.requireNonNull(obligations, "obligations are required");
        if (approvedColumns.isEmpty()) {
            throw new IllegalArgumentException("ADR-0003 ¶2: a preflight scan measures the approved extraction "
                    + "expressions, so " + table.table() + " needs at least one approved column");
        }

        List<ColumnCoordinate> approved = new ArrayList<>(approvedColumns.size());
        for (ApprovedColumn column : approvedColumns) {
            Objects.requireNonNull(column, "an approved column is required");
            approved.add(columnOfTable(table, column.source(), "an approved column"));
        }

        List<Aggregate> aggregates = new ArrayList<>();
        envelope(approved, aggregates);
        typeDomain(table, approved, obligations, aggregates);
        keysetExtrema(table, aggregates);

        String sql = "SELECT " + aggregates.stream().map(Aggregate::selectItem).collect(Collectors.joining(", "))
                + " FROM " + MySqlIdentifier.qualified(table.table());
        ResultSchema schema = new ResultSchema(aggregates.stream().map(Aggregate::resultColumn).toList(),
                ResultSchema.Cardinality.EXACTLY_ONE_ROW);

        return new SqlPlan(
                OperationKind.SOURCE_PREFLIGHT_SCAN,
                List.of(new ParameterizedStatement(sql, List.of())),
                schema,
                TimeoutClass.EXACT_SCAN,
                Set.of(RequiredPrivilege.SOURCE_SELECT),
                EvidencePolicy.STATEMENT_AND_AGGREGATES);
    }

    /**
     * The bytes one approved value occupies on the source (ADR-0003 ¶2). {@code <E>} is
     * {@link QueryProjection#columnExpression}, <strong>called</strong> and never re-derived, so the envelope
     * measures the very expression the run extracts (obligation 19a). {@code COALESCE(…, 0)} makes a
     * {@code NULL} value nought bytes rather than a {@code NULL} sum for the whole row.
     */
    static String byteLength(ColumnCoordinate column) {
        return "COALESCE(OCTET_LENGTH(CAST(" + QueryProjection.columnExpression(column) + " AS BINARY)), 0)";
    }

    /** {@code MIN(<E>)}; its result is declared {@link #UNSIGNED_SAFE_DECIMAL} by every caller. */
    static String minimum(ColumnCoordinate column) {
        return "MIN(" + QueryProjection.columnExpression(column) + ")";
    }

    /** {@code MAX(<E>)}; its result is declared {@link #UNSIGNED_SAFE_DECIMAL} by every caller. */
    static String maximum(ColumnCoordinate column) {
        return "MAX(" + QueryProjection.columnExpression(column) + ")";
    }

    /** Per approved column, then the row payload once — one query, never one scan per column. */
    private static void envelope(List<ColumnCoordinate> approved, List<Aggregate> aggregates) {
        for (int i = 0; i < approved.size(); i++) {
            aggregates.add(new Aggregate("MAX(" + byteLength(approved.get(i)) + ")",
                    "value_bytes_" + (i + 1), COUNT_TYPE));
        }
        aggregates.add(new Aggregate(
                "MAX(" + approved.stream().map(PreflightScan::byteLength).collect(Collectors.joining(" + ")) + ")",
                "row_bytes", COUNT_TYPE));
    }

    /**
     * One aggregate per obligation that demands one, in the order the caller merged them. {@code NO_ZERO_DATE}
     * and {@code ZERO_DATE_ROWS_COUNTED} are the same count graded differently by {@code preflight}, so a
     * column carrying both contributes one column, not two.
     */
    private static void typeDomain(SourceTableMetadata table, List<ColumnCoordinate> approved,
            List<PreflightObligation> obligations, List<Aggregate> aggregates) {
        Set<String> labels = new HashSet<>();
        for (PreflightObligation obligation : obligations) {
            Objects.requireNonNull(obligation, "an obligation is required");
            ColumnCoordinate column = columnOfTable(table, obligation.column(),
                    "the " + obligation.preflight() + " obligation");
            int ordinal = approved.indexOf(column);
            if (ordinal < 0) {
                throw new IllegalArgumentException("preflight obligation 6: the " + obligation.preflight()
                        + " obligation names " + column + ", which is not an approved column, so the caller "
                        + "merged the wrong lists");
            }
            Optional<Check> check = checkFor(obligation.preflight());
            if (check.isEmpty()) {
                continue;
            }
            String label = check.get().label + "_" + (ordinal + 1);
            if (labels.add(label)) {
                aggregates.add(new Aggregate(check.get().expression(QueryProjection.columnExpression(column)),
                        label, check.get().databaseType));
            }
        }
    }

    /** Obligation 17: the range a keyset read would page over, per candidate, in candidate order. */
    private static void keysetExtrema(SourceTableMetadata table, List<Aggregate> aggregates) {
        List<KeysetCandidate> candidates = KeysetCandidates.of(table);
        for (int i = 0; i < candidates.size(); i++) {
            ColumnCoordinate column = candidates.get(i).column();
            aggregates.add(new Aggregate(minimum(column), "keyset_min_" + (i + 1), UNSIGNED_SAFE_DECIMAL));
            aggregates.add(new Aggregate(maximum(column), "keyset_max_" + (i + 1), UNSIGNED_SAFE_DECIMAL));
        }
    }

    /** The two obligations that plan no scan column: one is already measured, one is a metadata fact. */
    private static Optional<Check> checkFor(RequiredPreflight preflight) {
        return switch (preflight) {
            case BOOLEAN_VALUES_ZERO_OR_ONE -> Optional.of(Check.BOOLEAN_DOMAIN);
            case UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE -> Optional.of(Check.UNSIGNED_MAX);
            case TIME_WITHIN_DAY -> Optional.of(Check.TIME_DOMAIN);
            case ENUM_VALUE_DECLARED -> Optional.of(Check.ENUM_SENTINEL);
            case NO_ZERO_DATE, ZERO_DATE_ROWS_COUNTED -> Optional.of(Check.ZERO_DATE_ROWS);
            // Every approved column is in the envelope already, whether or not it carries this obligation.
            case LARGE_RECORD_ENVELOPE -> Optional.empty();
            // TableStatistics.autoIncrement, read by source.metadataPlan (spec #134 correction 5).
            case AUTO_INCREMENT_NEXT_VALUE_WITHIN_SIGNED_RANGE -> Optional.empty();
        };
    }

    private static ColumnCoordinate columnOfTable(SourceTableMetadata table, ColumnCoordinate column, String what) {
        for (SourceColumn candidate : table.columns()) {
            if (candidate.coordinate().equals(column)) {
                return column;
            }
        }
        throw new IllegalArgumentException("ADR-0003 ¶2: " + what + " names " + column + ", which is not a column of "
                + table.table() + ", so the caller merged the wrong lists");
    }

    /** TP §6.6 checks 2–6, each an aggregate over one approved column's read expression. */
    private enum Check {
        BOOLEAN_DOMAIN("boolean_domain", COUNT_TYPE),
        UNSIGNED_MAX("unsigned_max", UNSIGNED_SAFE_DECIMAL),
        TIME_DOMAIN("time_domain", COUNT_TYPE),
        ENUM_SENTINEL("enum_sentinel", COUNT_TYPE),
        ZERO_DATE_ROWS("zero_date_rows", COUNT_TYPE);

        private final String label;
        private final String databaseType;

        Check(String label, String databaseType) {
            this.label = label;
            this.databaseType = databaseType;
        }

        /**
         * {@code read} is {@link QueryProjection#columnExpression} for the column.
         *
         * <p>{@code ZERO_DATE_ROWS} compares components, never a zero-date literal: under
         * {@code NO_ZERO_DATE}/{@code NO_ZERO_IN_DATE} sql_mode a literal zero date raises an error, and an
         * error is an {@code INCONCLUSIVE} that would block a healthy table (TP §6.6 check 6). A zero date is
         * exactly a date one of whose components is nought, so the components answer it without a literal.
         *
         * <p>{@code ENUM_SENTINEL} counts rows whose {@code ENUM} ordinal is nought. MySQL storage already
         * guarantees membership, the nought ordinal is the illegal-value sentinel and the only escape, so no
         * member list is bound and a member containing a quote cannot defeat the check (TP §6.6 check 5).
         *
         * <p>{@code TIME_DOMAIN} counts rows outside {@code [00:00:00, 24:00:00)}; MySQL {@code TIME} spans
         * ±838 hours, which the PostgreSQL and Connect time-of-day domain does not (TP §6.6 check 4).
         */
        String expression(String read) {
            return switch (this) {
                case BOOLEAN_DOMAIN -> offendingRows(read, read + " NOT IN (0, 1)");
                case UNSIGNED_MAX -> "MAX(" + read + ")";
                case TIME_DOMAIN -> offendingRows(read,
                        "(TIME_TO_SEC(" + read + ") < 0 OR TIME_TO_SEC(" + read + ") >= 86400)");
                case ENUM_SENTINEL -> offendingRows(read, read + " + 0 = 0");
                case ZERO_DATE_ROWS -> offendingRows(read, "(YEAR(" + read + ") = 0 OR MONTH(" + read + ") = 0 "
                        + "OR DAYOFMONTH(" + read + ") = 0)");
            };
        }

        /** A {@code NULL} value offends nothing; an empty table gives {@code NULL}, not a false nought. */
        private static String offendingRows(String read, String condition) {
            return "SUM(CASE WHEN " + read + " IS NOT NULL AND " + condition + " THEN 1 ELSE 0 END)";
        }
    }

    /** One aggregate: its SQL, its stable label and the type the caller must read it as. */
    private record Aggregate(String expression, String label, String databaseType) {

        String selectItem() {
            return expression + " AS " + MySqlIdentifier.quoted(label);
        }

        ResultSchema.Column resultColumn() {
            return new ResultSchema.Column(label, databaseType, Nullability.NULLABLE);
        }
    }
}
