package com.dbx.dialect.api;

import java.util.List;
import java.util.Optional;

/**
 * The table, the typed key columns and — for a numeric single key — the seek thresholds deterministic
 * sampling reads at (TP §9.3). Owned by slice 6.
 *
 * <p>The two sampling strategies of TP §9.3 are distinguished by {@link #seekThresholds} alone:
 *
 * <ul>
 *   <li><b>present</b> — the numeric single key: {@code validation} computed the evenly spaced thresholds
 *       with arbitrary-precision arithmetic and hands them over already computed, and the source dialect
 *       renders one seek per threshold;
 *   <li><b>absent</b> — every other key: the first and last rows in typed source key order.
 * </ul>
 *
 * <p>{@code dialect} computes no threshold and does no arithmetic on data (obligation 23; TP §9.3). A
 * threshold reaches SQL only as the bound value it was given: this module does not inspect, convert, widen
 * or re-type one. That is also how a temporal key works, even though {@link SqlValue} has no temporal
 * variant — {@code validation} decides whether a date threshold binds as {@code TEXT}, {@code INT64} or
 * {@code BYTES}, and {@code dialect} binds what it is handed without interpreting it.
 */
public record SamplingKey(TableCoordinate table, List<KeyColumn> keyColumns,
        Optional<List<SqlValue>> seekThresholds) {

    public SamplingKey {
        Checks.present(table, "table");
        keyColumns = Checks.nonEmptyList(keyColumns, "keyColumns");
        Checks.present(seekThresholds, "seekThresholds");
        for (KeyColumn keyColumn : keyColumns) {
            if (!keyColumn.column().database().equals(table.database())
                    || !keyColumn.column().table().equals(table.table())) {
                throw new IllegalArgumentException("TP §9.3: a sampling key orders one table, so " + keyColumn.column()
                        + " cannot be a key column of " + table);
            }
        }
        List<KeyColumn> columns = keyColumns;
        seekThresholds = seekThresholds.map(thresholds -> checkThresholds(thresholds, columns));
    }

    /**
     * TP §9.3 gives seek thresholds to <em>numeric single</em> keys only, and the seek they render is one
     * {@code >=} comparison against one column, so a threshold list beside a composite key has no column to
     * compare against and is refused rather than rendered.
     *
     * <p>Only the shape of the key is checked here. The thresholds themselves are not inspected: their types
     * and their spacing are {@code validation}'s arbitrary-precision decision (TP §9.3), and second-guessing
     * one here would be the arithmetic on data this module must not do.
     */
    private static List<SqlValue> checkThresholds(List<SqlValue> thresholds, List<KeyColumn> keyColumns) {
        List<SqlValue> copy = Checks.nonEmptyList(thresholds, "seekThresholds");
        if (keyColumns.size() != 1) {
            throw new IllegalArgumentException("TP §9.3: seek thresholds belong to a numeric single key, but this "
                    + "key has " + keyColumns.size() + " columns; a composite key samples its first and last rows");
        }
        return copy;
    }

    /**
     * One key column under the source's own type name, which is what the plan's {@link ResultSchema} declares.
     * The name is free text out of {@code information_schema}, exactly as {@code SourceColumn.dataType} carries
     * it; nothing here parses it.
     */
    public record KeyColumn(ColumnCoordinate column, String databaseType) {

        public KeyColumn {
            Checks.present(column, "column");
            Checks.nonEmpty(databaseType, "databaseType");
        }
    }
}
