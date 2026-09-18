package com.dbx.dialect.postgres;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultRows;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SamplingLookupKeys;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TargetIdentifier;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code target.samplingLookupPlan} (obligation 23; TP §9.3): one target lookup per sampled key tuple.
 *
 * <p><strong>One plan per key tuple</strong> (spec #134 correction 4), in the order the keys were given, so
 * every plan keeps one honest cardinality and one fingerprint — a single plan carrying a thousand lookups
 * would have neither. Each is <strong>typed parameterized equality on every key component</strong>
 * ({@code validation} obligation 27): the key columns are quoted through {@link PostgresIdentifier} and every
 * key value is bound as the {@link SqlValue} {@code validation} handed over, keeping its type. No value is
 * rendered, inspected, converted or re-typed here.
 *
 * <p><strong>It counts the matches rather than fetching the row.</strong> TP §9.3 requires a lookup to find
 * exactly one row, and that requirement is graded by {@code validation}: a sampled key that matches no row,
 * or two, is a {@code FAIL} it must report. Fetching the row under a declared {@code EXACTLY_ONE_ROW} would
 * instead turn a missing row into an execution error, which {@code validation} obligation 14 must read as
 * {@code INCONCLUSIVE} — the wrong conclusion about a real difference. Counting answers exactly one row for
 * every key, so the declared cardinality is the truth about the statement and the finding stays a finding.
 * It also means the plan reads no customer value at all, which is what lets the evidence policy below be the
 * whole story rather than a promise about what a caller does with the rows.
 *
 * <p><strong>Evidence is {@link EvidencePolicy#STATEMENT_ONLY}.</strong> ADR-0028 lets no record value or
 * primary-key value be persisted as evidence, not even on request, and a lookup's statement binds the very
 * key values that ban covers.
 *
 * <p>This class compares nothing: which rows a sample is drawn from, how many there are, and every value
 * semantics rule of TP §9.3 are {@code validation}'s ({@code validation} obligations 26–29).
 */
final class SamplingLookup {

    /** The label the match count comes back under; a fixed scheme, never a column name. */
    private static final String MATCHED_ROWS = "matched_rows";

    /**
     * A {@code count} is a {@code bigint} and a lookup that matches nothing answers nought, so the one
     * result column is never {@code NULL}.
     */
    private static final ResultSchema SCHEMA = new ResultSchema(
            List.of(new ResultSchema.Column(MATCHED_ROWS, "bigint", Nullability.NOT_NULL)),
            ResultSchema.Cardinality.EXACTLY_ONE_ROW);

    /**
     * Reaching an object through its schema needs {@code USAGE} on that schema ({@code TARGET_READ_CATALOG});
     * reading the rows of a DBX-owned table is {@code TARGET_OWN_OBJECT}. A lookup writes nothing.
     */
    private static final Set<RequiredPrivilege> PRIVILEGES =
            Set.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT);

    private SamplingLookup() {
    }

    /**
     * The lookups of one sample, in key order. No key at all plans nothing: an empty sample is a fact
     * {@code validation} reports, not a statement to send.
     *
     * @throws IllegalArgumentException when a key tuple's arity differs from {@link
     *     SamplingLookupKeys#keyColumns()} — a tuple with too few or too many components cannot be compared
     *     on every component, and silently comparing a prefix would look up the wrong row
     */
    static List<SqlPlan> plans(SamplingLookupKeys keys) {
        Objects.requireNonNull(keys, "keys are required");
        List<TargetIdentifier> keyColumns = keys.keyColumns();
        String predicate = keyColumns.stream()
                .map(column -> PostgresIdentifier.quoted(column) + " = ?")
                .collect(Collectors.joining(" AND "));
        String sql = "SELECT count(*) AS " + PostgresIdentifier.quoted(new TargetIdentifier(MATCHED_ROWS))
                + " FROM " + PostgresIdentifier.qualified(keys.table()) + " WHERE " + predicate;

        List<SqlPlan> plans = new ArrayList<>(keys.keys().size());
        for (ResultRows.Row key : keys.keys()) {
            List<SqlValue> values = key.values();
            if (values.size() != keyColumns.size()) {
                throw new IllegalArgumentException("TP §9.3: a target lookup is typed parameterized equality on "
                        + "every key component, so a key tuple of " + keys.table() + " has one value per key "
                        + "column; this one has " + values.size() + " for " + keyColumns.size() + " columns");
            }
            plans.add(new SqlPlan(
                    OperationKind.TARGET_SAMPLING_LOOKUP,
                    List.of(new ParameterizedStatement(sql, values)),
                    SCHEMA,
                    TimeoutClass.EXACT_SCAN,
                    PRIVILEGES,
                    EvidencePolicy.STATEMENT_ONLY));
        }
        return List.copyOf(plans);
    }
}
