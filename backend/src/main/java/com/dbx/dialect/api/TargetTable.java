package com.dbx.dialect.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The minimal writable table an approved contract describes (ADR-0011 §DDL; TP §7.3): ordered columns and
 * the optional ordered primary key ({@code primaryKey} empty means no key). Construction refuses a table
 * whose DDL could not be proven equal to it.
 */
public record TargetTable(TargetTableCoordinate table, List<TargetColumn> columns, List<TargetIdentifier> primaryKey) {

    public TargetTable {
        Checks.present(table, "table");
        columns = Checks.nonEmptyList(columns, "columns");
        primaryKey = Checks.list(primaryKey, "primaryKey");
        Set<TargetIdentifier> names = new HashSet<>();
        for (TargetColumn column : columns) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("ADR-0011 §DDL: column " + column.name().quoted()
                        + " appears twice in " + table.name().quoted());
            }
        }
        Set<TargetIdentifier> keyed = new HashSet<>();
        for (TargetIdentifier key : primaryKey) {
            TargetColumn column = columns.stream().filter(c -> c.name().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("ADR-0011 §DDL: primary-key column "
                            + key.quoted() + " is not a column of " + table.name().quoted()));
            if (!keyed.add(key)) {
                throw new IllegalArgumentException("ADR-0011 §DDL: primary-key column " + key.quoted()
                        + " appears twice");
            }
            if (column.nullability() != Nullability.NOT_NULL) {
                throw new IllegalArgumentException("ADR-0011 §DDL: PostgreSQL makes a primary-key column NOT NULL, "
                        + "so the nullable key column " + key.quoted() + " could never pass structural proof");
            }
        }
        List<TargetColumn> generated = columns.stream()
                .filter(column -> !(column.identity() instanceof IdentityIntent.None))
                .toList();
        if (generated.size() > 1) {
            throw new IllegalArgumentException("TP §7.3: a MySQL table has at most one AUTO_INCREMENT column, so "
                    + "at most one column carries identity intent; found " + generated.size());
        }
        for (TargetColumn column : generated) {
            if (column.identity() instanceof IdentityIntent.OwnedSequence owned
                    && owned.sequence().equals(table.name())) {
                throw new IllegalArgumentException("TP §7.3: sequence " + owned.sequence().quoted()
                        + " would share its name with the table in one PostgreSQL schema");
            }
        }
    }
}
