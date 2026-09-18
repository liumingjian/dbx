package com.dbx.dialect.pair;

import com.dbx.dialect.api.BoundedReadRequirement;
import com.dbx.dialect.api.ExecutionRequirements;
import com.dbx.dialect.api.RequiredPreflight;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Supported;
import com.dbx.dialect.api.ValidationCapabilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Execution requirements and validation capabilities (ADR-0008 §Plans; ADR-0040). Slice 9. */
final class PairRequirements {

    /**
     * The source character sets whose bytes MySQL and PostgreSQL count the same way. MySQL's
     * {@code CAST(E AS BINARY)} counts bytes in the column's own character set and PostgreSQL's
     * {@code octet_length} counts UTF-8 bytes, so the two agree only here (ADR-0040).
     */
    private static final Set<String> BYTE_COMPARABLE_CHARACTER_SETS = Set.of("utf8mb4", "utf8mb3", "utf8", "ascii");

    /** Stored as bytes on both sides, so no character set enters the count (ADR-0040). */
    private static final Set<String> BINARY_FAMILY =
            Set.of("binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob");

    /**
     * Counted in the column's character set on the source side, so the set decides (ADR-0040). {@code json}
     * is a member because ADR-0040 puts it in this family, but {@link #JSON} answers it before the character
     * set is read.
     */
    private static final Set<String> CHARACTER_FAMILY =
            Set.of("char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum", "set", "json");

    /**
     * {@code json} is comparable unconditionally. MySQL reports it with no {@code CHARACTER_SET_NAME} in
     * {@code information_schema} yet stores it internally as utf8mb4, so ADR-0040's rationale holds by
     * construction and the reported character set decides nothing. It is the only character type read
     * that way: for every other one an absent or unrecognised character set means not provably
     * comparable, because absence is never permission.
     */
    private static final String JSON = "json";

    private PairRequirements() {
    }

    /**
     * The bounded read carried through unchanged, plus the one fact the mapping decisions settle: whether
     * any of them requires {@link RequiredPreflight#LARGE_RECORD_ENVELOPE}. Every other requirement is a
     * constant of {@link ExecutionRequirements}, so nothing here can weaken an ADR-0009 policy
     * (obligation 30). Nothing is computed from M and no connector configuration is derived.
     */
    static ExecutionRequirements executionRequirements(
            BoundedReadRequirement boundedRead, List<Supported> mappingDecisions) {
        Objects.requireNonNull(mappingDecisions, "mappingDecisions is required");
        if (mappingDecisions.isEmpty()) {
            throw new IllegalArgumentException("ADR-0009: a box carries at least one mapped column, so an empty "
                    + "mapping decision list is a caller bug, not a table without requirements");
        }
        boolean largeRecordEnvelope = mappingDecisions.stream()
                .anyMatch(decision -> decision.requiredPreflights().contains(RequiredPreflight.LARGE_RECORD_ENVELOPE));
        return new ExecutionRequirements(boundedRead, largeRecordEnvelope);
    }

    /**
     * One answer per column, in input order (ADR-0040). Comparability is a function of {@code dataType}
     * and {@code characterSetName} alone; nothing else about the column, and nothing about the target,
     * can change it.
     */
    static ValidationCapabilities validationCapabilities(List<SourceColumn> columns) {
        Objects.requireNonNull(columns, "columns is required");
        List<ValidationCapabilities.ByteLengthComparability> answers = new ArrayList<>(columns.size());
        for (SourceColumn column : columns) {
            Objects.requireNonNull(column, "a column is required: ADR-0040 decides comparability per column");
            answers.add(new ValidationCapabilities.ByteLengthComparability(
                    column.coordinate(), provablyComparable(column)));
        }
        return new ValidationCapabilities(answers);
    }

    private static boolean provablyComparable(SourceColumn column) {
        String dataType = column.dataType();
        if (BINARY_FAMILY.contains(dataType)) {
            return true;
        }
        if (JSON.equals(dataType)) {
            return true;
        }
        if (!CHARACTER_FAMILY.contains(dataType)) {
            return false;
        }
        return column.characterSetName().map(BYTE_COMPARABLE_CHARACTER_SETS::contains).orElse(false);
    }
}
