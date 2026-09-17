package com.dbx.dialect.api;

import java.util.List;

/**
 * One foreign key of a source table, from {@code REFERENTIAL_CONSTRAINTS} and {@code KEY_COLUMN_USAGE}:
 * its columns in constraint order, each paired with the column it references, and its rules.
 */
public record SourceForeignKey(
        String name,
        TableCoordinate referencedTable,
        List<Part> parts,
        ReferentialAction onUpdate,
        ReferentialAction onDelete) {

    public SourceForeignKey {
        Checks.nonEmpty(name, "name");
        Checks.present(referencedTable, "referencedTable");
        parts = Checks.list(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("foreign key " + name + " has no columns");
        }
        for (Part part : parts) {
            ColumnCoordinate referenced = part.referencedColumn();
            if (!referenced.database().equals(referencedTable.database())
                    || !referenced.table().equals(referencedTable.table())) {
                throw new IllegalArgumentException("foreign key " + name + ": " + referenced
                        + " is not a column of the referenced table " + referencedTable);
            }
        }
        Checks.present(onUpdate, "onUpdate");
        Checks.present(onDelete, "onDelete");
    }

    public record Part(ColumnCoordinate column, ColumnCoordinate referencedColumn) {

        public Part {
            Checks.present(column, "column");
            Checks.present(referencedColumn, "referencedColumn");
        }
    }

    /** {@code UPDATE_RULE} and {@code DELETE_RULE}, one constant per value MySQL 8.0 reports. */
    public enum ReferentialAction {
        CASCADE,
        SET_NULL,
        SET_DEFAULT,
        RESTRICT,
        NO_ACTION
    }
}
