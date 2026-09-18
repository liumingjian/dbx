package com.dbx.dialect.api;

import java.util.ArrayList;
import java.util.List;

/**
 * One table's columns to gather validation facts for, on both sides (TP §9.2; ADR-0040). Owned by
 * slices 6 and 8, partitioned into batches by {@link ValidationFactBatch#partition(ValidationItem)}.
 *
 * <p>It carries facts, not just coordinates. {@link ValidationColumn} states which aggregate family a
 * column belongs to, what the approved contract says its source nullability is, and whether it is a
 * 大记录表 (large record table) column over the 1 MiB boundary; {@link ValidationKeyComponent} states the
 * key and the ordering the pair can prove for it. Those are the inputs the shared batch rule needs, and
 * both dialects read the same ones, so source batch <em>i</em> and target batch <em>i</em> can be paired
 * by index rather than joined by name ({@code validation} obligations 7, 8, 10 and 11).
 *
 * @param source the source table the facts are read from
 * @param target the target table the matching facts are read from
 * @param columns the approved columns with their facts, in the order the plans render them; may be empty
 *     when a table earns key facts only
 * @param keyComponents the key's components in key order, empty for a table without a key — TP §9.2's
 *     {@code NOT_APPLICABLE / NO_PRIMARY_KEY} is {@code validation}'s grading, so this module simply
 *     plans no key facts
 * @param largeRecordTable whether the table is a 大记录表 (large record table); only such a table may
 *     carry a {@link ValidationColumn#largeRecordValue()} column (ADR-0040; ADR-0003)
 */
public record ValidationItem(TableCoordinate source, TargetTableCoordinate target, List<ValidationColumn> columns,
        List<ValidationKeyComponent> keyComponents, boolean largeRecordTable) {

    public ValidationItem {
        Checks.present(source, "source");
        Checks.present(target, "target");
        columns = Checks.list(columns, "columns");
        keyComponents = Checks.list(keyComponents, "keyComponents");

        List<ColumnCoordinate> seen = new ArrayList<>(columns.size());
        for (ValidationColumn column : columns) {
            ColumnCoordinate coordinate = ofTable(source, column.source(), "a validation column");
            if (seen.contains(coordinate)) {
                throw new IllegalArgumentException("TP §9.2: " + coordinate + " appears twice, so its facts would "
                        + "be gathered twice and paired against themselves");
            }
            seen.add(coordinate);
            if (column.largeRecordValue() && !largeRecordTable) {
                throw new IllegalArgumentException("ADR-0040: 大记录值完整性 (large record value integrity) applies "
                        + "only within a large record table, so " + coordinate + " cannot be a large-record column "
                        + "of " + source + ", which is not one");
            }
        }

        List<ColumnCoordinate> keys = new ArrayList<>(keyComponents.size());
        for (ValidationKeyComponent component : keyComponents) {
            ColumnCoordinate coordinate = ofTable(source, component.column(), "a key component");
            if (keys.contains(coordinate)) {
                throw new IllegalArgumentException("TP §9.2: " + coordinate + " appears twice in the key, so the "
                        + "duplicate count would group by it twice");
            }
            keys.add(coordinate);
        }
    }

    /**
     * TP §9.2 compares {@code MIN}/{@code MAX} only for a <em>single</em> key whose ordering the pair can
     * prove; a composite key has no single order, and a string or collated one has no shared order.
     */
    public boolean keyExtremaComparable() {
        return keyComponents.size() == 1 && keyComponents.get(0).ordering().extremaComparable();
    }

    private static ColumnCoordinate ofTable(TableCoordinate table, ColumnCoordinate column, String what) {
        Checks.present(column, what);
        if (!column.database().equals(table.database()) || !column.table().equals(table.table())) {
            throw new IllegalArgumentException("TP §9.2: a validation item gathers one table's facts, so " + column
                    + " cannot be " + what + " of " + table);
        }
        return column;
    }
}
