package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ColumnComment;
import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.ResultRows;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.SourceForeignKey;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.SourceTableMetadata;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TableCoordinate;
import com.dbx.dialect.api.TableStatistics;
import com.dbx.dialect.mysql.MetadataRead.Column;
import com.dbx.dialect.mysql.MetadataRead.Fact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Turns the rows of {@link MetadataRead} into one {@link SourceTableMetadata} per table. Tables, columns,
 * indexes, key parts and foreign keys keep the order the server returned them in: nothing is sorted
 * here. Contradictory rows are a broken read and throw {@link IllegalArgumentException} naming the
 * coordinate; nothing about the source is unsupported.
 */
final class MetadataNormalizer {

    /** A numeric {@code column_type} with the {@code unsigned} attribute, e.g. {@code int unsigned zerofill}. */
    private static final Pattern UNSIGNED = Pattern.compile("[a-z ]+(\\([0-9, ]*\\))? unsigned( zerofill)?");

    private MetadataNormalizer() {
    }

    static List<SourceTableMetadata> normalize(ResultRows rows) {
        Objects.requireNonNull(rows, "rows are required");
        if (!rows.schema().equals(MetadataRead.SCHEMA)) {
            throw new IllegalArgumentException("these rows were not read by source.metadataPlan: their result "
                    + "schema differs from the plan's");
        }
        LinkedHashMap<TableCoordinate, TableFacts> tables = new LinkedHashMap<>();
        for (Fact fact : Fact.values()) {
            for (ResultRows.Row row : rows.rows()) {
                if (factOf(row) == fact) {
                    read(fact, new Values(row), tables);
                }
            }
        }
        return tables.values().stream().map(TableFacts::build).toList();
    }

    private static Fact factOf(ResultRows.Row row) {
        String name = new Values(row).text(Column.FACT);
        for (Fact fact : Fact.values()) {
            if (fact.name().equals(name)) {
                return fact;
            }
        }
        throw new IllegalArgumentException("a metadata row names an unknown fact: " + name);
    }

    private static void read(Fact fact, Values row, LinkedHashMap<TableCoordinate, TableFacts> tables) {
        TableCoordinate table = new TableCoordinate(row.text(Column.TABLE_SCHEMA), row.text(Column.TABLE_NAME));
        if (fact == Fact.TABLE) {
            if (tables.containsKey(table)) {
                throw new IllegalArgumentException(table + " has two TABLES rows");
            }
            tables.put(table, new TableFacts(table, row));
            return;
        }
        TableFacts facts = tables.get(table);
        if (facts == null) {
            throw new IllegalArgumentException("a " + fact + " row names " + table + ", which has no TABLES row");
        }
        switch (fact) {
            case COLUMN -> facts.column(row);
            case INDEX -> facts.keyPart(row);
            case FOREIGN_KEY -> facts.foreignKeyPart(row);
            case TABLE -> throw new AssertionError("handled above");
        }
    }

    /** One table's facts, collected in row order. */
    private static final class TableFacts {

        private final TableCoordinate table;
        private final String comment;
        private final Optional<String> collation;
        private final TableStatistics statistics;
        private final List<SourceColumn> columns = new ArrayList<>();
        private final List<ColumnComment> columnComments = new ArrayList<>();
        private final LinkedHashMap<String, IndexFacts> indexes = new LinkedHashMap<>();
        private final LinkedHashMap<String, ForeignKeyFacts> foreignKeys = new LinkedHashMap<>();

        TableFacts(TableCoordinate table, Values row) {
            this.table = table;
            this.comment = row.text(Column.TABLE_COMMENT);
            this.collation = row.optionalText(Column.TABLE_COLLATION);
            this.statistics = new TableStatistics(row.optionalLong(Column.TABLE_ROWS),
                    row.optionalLong(Column.AVG_ROW_LENGTH), row.optionalLong(Column.DATA_LENGTH));
        }

        void column(Values row) {
            ColumnCoordinate coordinate = new ColumnCoordinate(table.database(), table.table(),
                    row.text(Column.COLUMN_NAME));
            String dataType = row.text(Column.DATA_TYPE);
            String columnType = row.text(Column.COLUMN_TYPE);
            columns.add(new SourceColumn(
                    coordinate,
                    dataType,
                    columnType,
                    !dataType.equals("enum") && !dataType.equals("set") && UNSIGNED.matcher(columnType).matches(),
                    row.optionalLong(Column.CHARACTER_MAXIMUM_LENGTH),
                    row.optionalLong(Column.CHARACTER_OCTET_LENGTH),
                    row.optionalInt(Column.NUMERIC_PRECISION),
                    row.optionalInt(Column.NUMERIC_SCALE),
                    row.optionalInt(Column.DATETIME_PRECISION),
                    row.optionalText(Column.CHARACTER_SET_NAME),
                    row.optionalText(Column.COLLATION_NAME),
                    switch (row.text(Column.IS_NULLABLE)) {
                        case "YES" -> Nullability.NULLABLE;
                        case "NO" -> Nullability.NOT_NULL;
                        default -> throw new IllegalArgumentException(coordinate + " has IS_NULLABLE "
                                + row.text(Column.IS_NULLABLE) + ", neither YES nor NO");
                    },
                    row.optionalText(Column.COLUMN_DEFAULT),
                    row.text(Column.EXTRA),
                    row.integer(Column.ORDINAL_POSITION)));
            columnComments.add(new ColumnComment(coordinate, row.text(Column.COLUMN_COMMENT)));
        }

        void keyPart(Values row) {
            String name = row.text(Column.INDEX_NAME);
            String where = "index " + name + " of " + table;
            boolean unique = switch ((int) row.integer(Column.NON_UNIQUE)) {
                case 0 -> true;
                case 1 -> false;
                default -> throw new IllegalArgumentException(where + " has NON_UNIQUE " + row.integer(Column.NON_UNIQUE));
            };
            boolean visible = switch (row.text(Column.IS_VISIBLE)) {
                case "YES" -> true;
                case "NO" -> false;
                default -> throw new IllegalArgumentException(where + " has IS_VISIBLE " + row.text(Column.IS_VISIBLE));
            };
            SourceIndex.IndexType indexType = switch (row.text(Column.INDEX_TYPE)) {
                case "BTREE" -> SourceIndex.IndexType.BTREE;
                case "HASH" -> SourceIndex.IndexType.HASH;
                case "FULLTEXT" -> SourceIndex.IndexType.FULLTEXT;
                case "SPATIAL" -> SourceIndex.IndexType.SPATIAL;
                default -> throw new IllegalArgumentException(where + " has an unknown INDEX_TYPE "
                        + row.text(Column.INDEX_TYPE));
            };
            IndexFacts index = indexes.computeIfAbsent(name, n -> new IndexFacts(name, unique, visible, indexType));
            if (index.unique != unique || index.visible != visible || index.indexType != indexType) {
                throw new IllegalArgumentException(where + ": its key parts disagree on uniqueness, visibility or type");
            }

            Optional<String> columnName = row.optionalText(Column.INDEX_COLUMN_NAME);
            Optional<String> expression = row.optionalText(Column.EXPRESSION);
            if (columnName.isPresent() == expression.isPresent()) {
                throw new IllegalArgumentException(where + ": a key part names exactly one of a column or an "
                        + "expression; this one names " + (columnName.isPresent() ? "both" : "neither"));
            }
            SourceIndex.Subject subject = columnName.isPresent()
                    ? new SourceIndex.Subject.Column(new ColumnCoordinate(table.database(), table.table(), columnName.get()))
                    : new SourceIndex.Subject.Expression(expression.get());
            SourceIndex.Direction direction = row.optionalText(Column.INDEX_COLLATION)
                    .map(value -> switch (value) {
                        case "A" -> SourceIndex.Direction.ASCENDING;
                        case "D" -> SourceIndex.Direction.DESCENDING;
                        default -> throw new IllegalArgumentException(where + " has key part COLLATION " + value);
                    })
                    .orElse(SourceIndex.Direction.NOT_SORTED);
            index.parts.add(new SourceIndex.KeyPart(row.integer(Column.SEQ_IN_INDEX), subject,
                    row.optionalLong(Column.SUB_PART), direction));
        }

        void foreignKeyPart(Values row) {
            String name = row.text(Column.CONSTRAINT_NAME);
            String where = "foreign key " + name + " of " + table;
            Optional<String> columnName = row.optionalText(Column.FK_COLUMN_NAME);
            if (columnName.isEmpty()) {
                throw new IllegalArgumentException(where + " has no columns in KEY_COLUMN_USAGE");
            }
            TableCoordinate referenced = new TableCoordinate(row.text(Column.REFERENCED_TABLE_SCHEMA),
                    row.text(Column.REFERENCED_TABLE_NAME));
            SourceForeignKey.ReferentialAction onUpdate = action(where, row.text(Column.UPDATE_RULE));
            SourceForeignKey.ReferentialAction onDelete = action(where, row.text(Column.DELETE_RULE));
            ForeignKeyFacts foreignKey = foreignKeys.computeIfAbsent(name,
                    n -> new ForeignKeyFacts(name, referenced, onUpdate, onDelete));
            if (!foreignKey.referenced.equals(referenced) || foreignKey.onUpdate != onUpdate
                    || foreignKey.onDelete != onDelete) {
                throw new IllegalArgumentException(where + ": its rows disagree on the referenced table or the rules");
            }
            int position = row.integer(Column.FK_ORDINAL_POSITION);
            if (position != foreignKey.parts.size() + 1) {
                throw new IllegalArgumentException(where + ": column " + columnName.get() + " has ORDINAL_POSITION "
                        + position + " where " + (foreignKey.parts.size() + 1) + " comes next");
            }
            String referencedColumn = row.optionalText(Column.REFERENCED_COLUMN_NAME).orElseThrow(() ->
                    new IllegalArgumentException(where + ": column " + columnName.get() + " references no column"));
            foreignKey.parts.add(new SourceForeignKey.Part(
                    new ColumnCoordinate(table.database(), table.table(), columnName.get()),
                    new ColumnCoordinate(referenced.database(), referenced.table(), referencedColumn)));
        }

        SourceTableMetadata build() {
            return new SourceTableMetadata(
                    table,
                    columns,
                    columnComments,
                    indexes.values().stream()
                            .map(index -> new SourceIndex(index.name, index.unique, index.visible, index.indexType,
                                    index.parts))
                            .toList(),
                    foreignKeys.values().stream()
                            .map(fk -> new SourceForeignKey(fk.name, fk.referenced, fk.parts, fk.onUpdate, fk.onDelete))
                            .toList(),
                    comment,
                    collation,
                    statistics);
        }

        private static SourceForeignKey.ReferentialAction action(String where, String rule) {
            return switch (rule) {
                case "CASCADE" -> SourceForeignKey.ReferentialAction.CASCADE;
                case "SET NULL" -> SourceForeignKey.ReferentialAction.SET_NULL;
                case "SET DEFAULT" -> SourceForeignKey.ReferentialAction.SET_DEFAULT;
                case "RESTRICT" -> SourceForeignKey.ReferentialAction.RESTRICT;
                case "NO ACTION" -> SourceForeignKey.ReferentialAction.NO_ACTION;
                default -> throw new IllegalArgumentException(where + " has an unknown referential rule " + rule);
            };
        }
    }

    private static final class IndexFacts {

        final String name;
        final boolean unique;
        final boolean visible;
        final SourceIndex.IndexType indexType;
        final List<SourceIndex.KeyPart> parts = new ArrayList<>();

        IndexFacts(String name, boolean unique, boolean visible, SourceIndex.IndexType indexType) {
            this.name = name;
            this.unique = unique;
            this.visible = visible;
            this.indexType = indexType;
        }
    }

    private static final class ForeignKeyFacts {

        final String name;
        final TableCoordinate referenced;
        final SourceForeignKey.ReferentialAction onUpdate;
        final SourceForeignKey.ReferentialAction onDelete;
        final List<SourceForeignKey.Part> parts = new ArrayList<>();

        ForeignKeyFacts(String name, TableCoordinate referenced, SourceForeignKey.ReferentialAction onUpdate,
                SourceForeignKey.ReferentialAction onDelete) {
            this.name = name;
            this.referenced = referenced;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }
    }

    /** Typed access to one row by {@link Column}; a value of the wrong type is a broken read. */
    private record Values(ResultRows.Row row) {

        private SqlValue value(Column column) {
            return row.values().get(column.ordinal());
        }

        Optional<String> optionalText(Column column) {
            return switch (value(column)) {
                case SqlValue.Text text -> Optional.of(text.value());
                case SqlValue.Null n when n.type() == SqlValue.Type.TEXT -> Optional.empty();
                default -> throw wrongType(column);
            };
        }

        String text(Column column) {
            return optionalText(column).orElseThrow(() -> isNull(column));
        }

        OptionalLong optionalLong(Column column) {
            return switch (value(column)) {
                case SqlValue.Int64 number -> OptionalLong.of(number.value());
                case SqlValue.Null n when n.type() == SqlValue.Type.INT64 -> OptionalLong.empty();
                default -> throw wrongType(column);
            };
        }

        OptionalInt optionalInt(Column column) {
            OptionalLong value = optionalLong(column);
            return value.isPresent() ? OptionalInt.of(toInt(column, value.getAsLong())) : OptionalInt.empty();
        }

        int integer(Column column) {
            OptionalLong value = optionalLong(column);
            if (value.isEmpty()) {
                throw isNull(column);
            }
            return toInt(column, value.getAsLong());
        }

        private static int toInt(Column column, long value) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(column.label + " " + value + " does not fit the fact it describes");
            }
            return (int) value;
        }

        private IllegalArgumentException wrongType(Column column) {
            return new IllegalArgumentException(column.label + " is declared " + column.type.valueType
                    + " but the row holds " + value(column));
        }

        private IllegalArgumentException isNull(Column column) {
            return new IllegalArgumentException(column.label + " is NULL on a " + optionalText(Column.FACT).orElse("?")
                    + " row of table " + optionalText(Column.TABLE_NAME).orElse("?") + " in database "
                    + optionalText(Column.TABLE_SCHEMA).orElse("?"));
        }
    }
}
