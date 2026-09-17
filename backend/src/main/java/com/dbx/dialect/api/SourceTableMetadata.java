package com.dbx.dialect.api;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One source table's normalised metadata (obligations 15, 19b): the raw {@code information_schema}
 * facts of its columns, its indexes and foreign keys in the order the server returned them, its
 * comment and collation, and its statistics. Facts that contradict each other are refused here, naming
 * the coordinate: they are a broken read, not an unsupported source.
 */
public record SourceTableMetadata(
        TableCoordinate table,
        List<SourceColumn> columns,
        List<ColumnComment> columnComments,
        List<SourceIndex> indexes,
        List<SourceForeignKey> foreignKeys,
        String comment,
        Optional<String> collation,
        TableStatistics statistics) {

    public SourceTableMetadata {
        Checks.present(table, "table");
        columns = Checks.list(columns, "columns");
        columnComments = Checks.list(columnComments, "columnComments");
        indexes = Checks.list(indexes, "indexes");
        foreignKeys = Checks.list(foreignKeys, "foreignKeys");
        Checks.present(comment, "comment");
        Checks.present(collation, "collation");
        Checks.present(statistics, "statistics");

        if (columns.isEmpty()) {
            throw new IllegalArgumentException(table + " has no columns; every MySQL table has at least one");
        }
        Set<ColumnCoordinate> known = new HashSet<>();
        for (SourceColumn column : columns) {
            ColumnCoordinate coordinate = column.coordinate();
            if (!coordinate.database().equals(table.database()) || !coordinate.table().equals(table.table())) {
                throw new IllegalArgumentException(coordinate + " is not a column of " + table);
            }
            if (!known.add(coordinate)) {
                throw new IllegalArgumentException(coordinate + " appears twice in " + table);
            }
        }
        if (columnComments.size() != columns.size()) {
            throw new IllegalArgumentException(table + " has " + columns.size() + " columns and "
                    + columnComments.size() + " column comments; there is one per column");
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!columnComments.get(i).column().equals(columns.get(i).coordinate())) {
                throw new IllegalArgumentException(columnComments.get(i).column() + ": its comment is not in the "
                        + "column order of " + table);
            }
        }
        Set<String> indexNames = new HashSet<>();
        for (SourceIndex index : indexes) {
            if (!indexNames.add(index.name())) {
                throw new IllegalArgumentException("index " + index.name() + " appears twice in " + table);
            }
            for (SourceIndex.KeyPart part : index.keyParts()) {
                if (part.subject() instanceof SourceIndex.Subject.Column column && !known.contains(column.column())) {
                    throw new IllegalArgumentException("index " + index.name() + " of " + table
                            + " has a key part naming an unknown column " + column.column());
                }
            }
        }
        Set<String> foreignKeyNames = new HashSet<>();
        for (SourceForeignKey foreignKey : foreignKeys) {
            if (!foreignKeyNames.add(foreignKey.name())) {
                throw new IllegalArgumentException("foreign key " + foreignKey.name() + " appears twice in " + table);
            }
            for (SourceForeignKey.Part part : foreignKey.parts()) {
                if (!known.contains(part.column())) {
                    throw new IllegalArgumentException("foreign key " + foreignKey.name() + " of " + table
                            + " names an unknown column " + part.column());
                }
            }
        }
    }

    /** The index named {@code PRIMARY}, when the table has a primary key. */
    public Optional<SourceIndex> primaryKey() {
        return indexes.stream().filter(SourceIndex::primaryKey).findFirst();
    }
}
