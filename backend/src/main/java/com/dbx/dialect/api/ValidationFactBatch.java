package com.dbx.dialect.api;

import java.util.ArrayList;
import java.util.List;

/**
 * One batch of a {@link ValidationItem}'s columns, and <strong>the one rule that produces batches</strong>
 * (TP §9.2; {@code docs/spec/dialect.md} obligations 23 and 23b).
 *
 * <p>The rule is shared and public on purpose. {@code validation} pairs source batch <em>i</em> with target
 * batch <em>i</em> by index — a zip, not a join — so the two dialects must not each partition. Both call
 * {@link #partition(ValidationItem)}, which is deterministic and depends on the item alone, so batch
 * <em>i</em> holds the same columns in the same order on both sides by construction. The rule lives here
 * rather than in an endpoint package because {@code mysql} and {@code postgres} may not reference each
 * other (ADR-0008; {@code DialectContractTest.endpointDialectsNeverDependOnEachOther}).
 *
 * <p>Batching is by <strong>exact-numeric</strong> columns only: at most {@value #MAX_EXACT_NUMERIC_COLUMNS}
 * of them per batch, four expressions each (TP §9.2). The 非空约束符合性 (null constraint conformance) null
 * counts and the 大记录值完整性 (large record value integrity) byte-length aggregates ride in whichever
 * batch their column falls into and count toward nothing, because ADR-0040 added them precisely so that
 * they cost no extra scan. A column earning neither an aggregate, a null count nor byte lengths is left out
 * of every batch: planning a column that produces no fact would buy an expression and answer nothing.
 *
 * @param item the item this batch belongs to
 * @param index the batch's zero-based position among that item's batches — the index {@code validation}
 *     pairs the two sides by, and which each emitted plan names
 * @param columns the batch's columns, a contiguous run of {@link ValidationItem#columns()} in that order
 */
public record ValidationFactBatch(ValidationItem item, int index, List<ValidationColumn> columns) {

    /**
     * TP §9.2: "batches of at most 300 exact-numeric columns (four expressions each) with matching
     * source/target batches". The bound exists because a 1200-expression statement is where servers, drivers
     * and result-set metadata start to disagree, so it is a property of the batch and not of either engine.
     */
    public static final int MAX_EXACT_NUMERIC_COLUMNS = 300;

    public ValidationFactBatch {
        Checks.present(item, "item");
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative, was " + index);
        }
        columns = Checks.nonEmptyList(columns, "columns");
        int exactNumeric = 0;
        for (ValidationColumn column : columns) {
            if (!item.columns().contains(column)) {
                throw new IllegalArgumentException("TP §9.2: " + column.source() + " is not a column of the item "
                        + "this batch belongs to, so the two sides could not hold the same batch");
            }
            if (column.aggregation().exactNumeric()) {
                exactNumeric++;
            }
        }
        if (exactNumeric > MAX_EXACT_NUMERIC_COLUMNS) {
            throw new IllegalArgumentException("TP §9.2: a batch holds at most " + MAX_EXACT_NUMERIC_COLUMNS
                    + " exact-numeric columns, this one holds " + exactNumeric);
        }
    }

    /**
     * The one batch rule, called by both dialects and by neither privately.
     *
     * <p>It walks {@link ValidationItem#columns()} once in order, keeps every column that earns at least one
     * fact, and closes the batch when it already holds {@value #MAX_EXACT_NUMERIC_COLUMNS} exact-numeric
     * columns and another one arrives. An item that earns no fact at all yields no batch.
     *
     * @return the batches in index order; empty when no column earns a fact
     */
    public static List<ValidationFactBatch> partition(ValidationItem item) {
        Checks.present(item, "item");
        List<ValidationFactBatch> batches = new ArrayList<>();
        List<ValidationColumn> current = new ArrayList<>();
        int exactNumeric = 0;
        for (ValidationColumn column : item.columns()) {
            if (!plannable(column)) {
                continue;
            }
            if (column.aggregation().exactNumeric() && exactNumeric == MAX_EXACT_NUMERIC_COLUMNS) {
                batches.add(new ValidationFactBatch(item, batches.size(), current));
                current = new ArrayList<>();
                exactNumeric = 0;
            }
            current.add(column);
            if (column.aggregation().exactNumeric()) {
                exactNumeric++;
            }
        }
        if (!current.isEmpty()) {
            batches.add(new ValidationFactBatch(item, batches.size(), current));
        }
        return List.copyOf(batches);
    }

    /** The same rule over many items, flattened in item order; an item's indexes restart at nought. */
    public static List<ValidationFactBatch> partition(List<ValidationItem> items) {
        Checks.present(items, "items");
        List<ValidationFactBatch> batches = new ArrayList<>();
        for (ValidationItem item : items) {
            batches.addAll(partition(item));
        }
        return List.copyOf(batches);
    }

    /** The columns of this batch that earn TP §9.2's {@code COUNT}, {@code SUM}, {@code MIN}, {@code MAX}. */
    public List<ValidationColumn> exactNumericColumns() {
        return columns.stream().filter(column -> column.aggregation().exactNumeric()).toList();
    }

    /** The columns of this batch that earn ADR-0040's 非空约束符合性 null count. */
    public List<ValidationColumn> nullCountColumns() {
        return columns.stream().filter(column -> column.sourceNullability() == Nullability.NOT_NULL).toList();
    }

    /** The columns of this batch that earn ADR-0040's 大记录值完整性 byte-length aggregates. */
    public List<ValidationColumn> largeRecordColumns() {
        return columns.stream().filter(ValidationColumn::largeRecordValue).toList();
    }

    /**
     * The one-based position of a column in this batch, which is what a result label carries. A label is
     * never a column name: a column name is not a legal result label everywhere, and it changes on a rename.
     *
     * @throws IllegalArgumentException when the column is not in this batch
     */
    public int ordinalOf(ValidationColumn column) {
        int ordinal = columns.indexOf(Checks.present(column, "column"));
        if (ordinal < 0) {
            throw new IllegalArgumentException("TP §9.2: " + column.source() + " is not in batch " + index);
        }
        return ordinal + 1;
    }

    /** Whether the column produces any fact at all; one that produces none is planned by neither side. */
    private static boolean plannable(ValidationColumn column) {
        return column.aggregation().exactNumeric()
                || column.sourceNullability() == Nullability.NOT_NULL
                || column.largeRecordValue();
    }
}
