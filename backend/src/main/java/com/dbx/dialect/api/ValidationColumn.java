package com.dbx.dialect.api;

/**
 * One approved column with the three facts the shared validation batch rule needs about it (TP §9.2;
 * ADR-0040). An {@link ApprovedColumn} alone carries a source coordinate and a target name, which is
 * enough to project a row and not enough to decide which aggregate a column earns, so the caller states
 * the facts here and both dialects read the same ones.
 *
 * <p>{@code dialect} classifies nothing here. Whether a source type is exact numeric, approximate or
 * Boolean follows from the mapping decision {@code validation} already holds, exactly as
 * {@link SamplingKey.KeyColumn} carries the source type name without this module parsing it. A dialect
 * that re-derived the classification from a type string would be a second, disagreeing rule.
 *
 * @param column the approved column: its source coordinate and its approved target name
 * @param aggregation which TP §9.2 aggregate family the column belongs to
 * @param sourceNullability what the approved contract says the <strong>source</strong> side is;
 *     {@link Nullability#NOT_NULL} earns the 非空约束符合性 (null constraint conformance) null count
 *     (ADR-0040; {@code validation} obligation 10)
 * @param largeRecordValue whether this is a 大记录表 (large record table) column whose value or row
 *     exceeded the 1 MiB boundary, and so earns the 大记录值完整性 (large record value integrity)
 *     byte-length aggregates (ADR-0040; ADR-0003; {@code validation} obligation 11)
 */
public record ValidationColumn(ApprovedColumn column, Aggregation aggregation, Nullability sourceNullability,
        boolean largeRecordValue) {

    public ValidationColumn {
        Checks.present(column, "column");
        Checks.present(aggregation, "aggregation");
        Checks.present(sourceNullability, "sourceNullability");
    }

    /** The source coordinate of {@link #column}, the only thing a source plan renders. */
    public ColumnCoordinate source() {
        return column.source();
    }

    /**
     * TP §9.2's aggregate families. Only {@link #EXACT_NUMERIC} earns {@code COUNT/SUM/MIN/MAX} and only
     * it counts toward a batch's 300; the other three are named separately rather than lumped together so
     * that "floating types and Booleans are not exact numeric aggregate assertions" stays a stated fact of
     * the input instead of an omission.
     */
    public enum Aggregation {

        /** Integer and exact decimal: {@code COUNT}, {@code SUM}, {@code MIN}, {@code MAX} (TP §9.2). */
        EXACT_NUMERIC,

        /** {@code FLOAT}/{@code DOUBLE}: no aggregate, because IEEE summation is order-dependent (TP §9.2). */
        APPROXIMATE_NUMERIC,

        /** A Boolean column: no aggregate; its domain is proven by preflight, not summed (TP §9.2). */
        BOOLEAN,

        /** Text, binary, temporal, JSON: no §9.2 aggregate. It may still earn a null count or byte lengths. */
        NONE;

        /** Whether TP §9.2 gives this family the four exact-numeric aggregates. */
        public boolean exactNumeric() {
            return this == EXACT_NUMERIC;
        }
    }
}
