package com.dbx.dialect.api;

import java.util.List;
import java.util.Optional;

/**
 * A source structure left out of the minimal writable table and delivered as supplemental SQL (ADR-0026
 * §Supplemental SQL; obligation 25). Its content is ticket #125's metadata, and every target name in it is
 * already approved by {@code contract}. A pruned column and an out-of-scope referenced table are explicit
 * variants, so the renderer never guesses whether a name was left out.
 */
public sealed interface DeferredStructure {

    /** The source table the structure belongs to. */
    TableCoordinate source();

    /** The approved target table of {@link #source()}. */
    TargetTableCoordinate target();

    /** A source column with its approved target name, or the flag that the column was pruned. */
    sealed interface MappedColumn {

        ColumnCoordinate source();

        record Approved(ColumnCoordinate source, TargetIdentifier target) implements MappedColumn {

            public Approved {
                Checks.present(source, "source");
                Checks.present(target, "target");
            }
        }

        record Pruned(ColumnCoordinate source) implements MappedColumn {

            public Pruned {
                Checks.present(source, "source");
            }
        }
    }

    /**
     * A unique or ordinary index other than the primary key, which ADR-0011's DDL already builds. {@code columns}
     * maps the index's column key parts, one entry each, in key-part order; expression key parts have none.
     */
    record Index(TableCoordinate source, TargetTableCoordinate target, SourceIndex index, List<MappedColumn> columns)
            implements DeferredStructure {

        public Index {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.present(index, "index");
            columns = Checks.list(columns, "columns");
            if (index.primaryKey()) {
                throw new IllegalArgumentException("ADR-0011 §DDL: the primary key is part of the DDL, not deferred");
            }
            List<ColumnCoordinate> keyColumns = index.keyParts().stream()
                    .map(SourceIndex.KeyPart::subject)
                    .filter(SourceIndex.Subject.Column.class::isInstance)
                    .map(subject -> ((SourceIndex.Subject.Column) subject).column())
                    .toList();
            requireColumns("index " + index.name(), source, keyColumns, columns);
        }
    }

    /**
     * A foreign key. {@code columns} maps its referencing columns in constraint order; {@code referenced} says
     * whether the referenced table is in the migration scope and, when it is, maps the referenced columns.
     */
    record ForeignKey(
            TableCoordinate source,
            TargetTableCoordinate target,
            SourceForeignKey foreignKey,
            List<MappedColumn> columns,
            ReferencedTable referenced) implements DeferredStructure {

        public ForeignKey {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.present(foreignKey, "foreignKey");
            columns = Checks.list(columns, "columns");
            Checks.present(referenced, "referenced");
            String what = "foreign key " + foreignKey.name();
            requireColumns(what, source,
                    foreignKey.parts().stream().map(SourceForeignKey.Part::column).toList(), columns);
            if (referenced instanceof ReferencedTable.InScope inScope) {
                requireColumns(what + " (referenced)", foreignKey.referencedTable(),
                        foreignKey.parts().stream().map(SourceForeignKey.Part::referencedColumn).toList(),
                        inScope.columns());
            }
        }
    }

    /** Where a foreign key's referenced table stands in the approved migration scope. */
    sealed interface ReferencedTable {

        /** The referenced table's approved target and its referenced columns in constraint order. */
        record InScope(TargetTableCoordinate table, List<MappedColumn> columns) implements ReferencedTable {

            public InScope {
                Checks.present(table, "table");
                columns = Checks.list(columns, "columns");
            }
        }

        /** The flag that the referenced table is outside the migration scope. */
        record OutOfScope() implements ReferencedTable {
        }
    }

    /** A non-empty table comment. */
    record TableComment(TableCoordinate source, TargetTableCoordinate target, String comment)
            implements DeferredStructure {

        public TableComment {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.nonEmpty(comment, "comment");
        }
    }

    /** A non-empty column comment. */
    record ColumnCommentText(TableCoordinate source, TargetTableCoordinate target, MappedColumn column, String comment)
            implements DeferredStructure {

        public ColumnCommentText {
            Checks.present(source, "source");
            Checks.present(target, "target");
            requireColumn(source, column);
            Checks.nonEmpty(comment, "comment");
        }
    }

    /** A table collation (no column) or a column collation, by its MySQL name. */
    record Collation(TableCoordinate source, TargetTableCoordinate target, Optional<MappedColumn> column,
            String collation) implements DeferredStructure {

        public Collation {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.present(column, "column");
            column.ifPresent(mapped -> requireColumn(source, mapped));
            Checks.nonEmpty(collation, "collation");
        }
    }

    /** A column's {@code ON UPDATE} definition as MySQL reports it, for example {@code CURRENT_TIMESTAMP(3)}. */
    record OnUpdate(TableCoordinate source, TargetTableCoordinate target, MappedColumn column, String definition)
            implements DeferredStructure {

        public OnUpdate {
            Checks.present(source, "source");
            Checks.present(target, "target");
            requireColumn(source, column);
            Checks.nonEmpty(definition, "definition");
        }
    }

    /**
     * A source default outside ADR-0011's whitelist, delivered after the migration (ADR-0026). A
     * {@link Value.Constant} PostgreSQL can hold is set with {@code ALTER COLUMN … SET DEFAULT}; a
     * {@link Value.Expression} is not, because DBX cannot prove a translated MySQL expression means the same
     * thing there (ADR-0026 as amended by #133).
     */
    record ColumnDefault(TableCoordinate source, TargetTableCoordinate target, MappedColumn column, Value value)
            implements DeferredStructure {

        public ColumnDefault {
            Checks.present(source, "source");
            Checks.present(target, "target");
            requireColumn(source, column);
            Checks.present(value, "value");
        }

        /** What the source default holds: a constant, or a MySQL expression kept verbatim. */
        public sealed interface Value {

            /** A constant PostgreSQL can hold. A typed {@code NULL} is no default at all. */
            record Constant(SqlValue value) implements Value {

                public Constant {
                    Checks.present(value, "value");
                    if (value instanceof SqlValue.Null) {
                        throw new IllegalArgumentException(
                                "ADR-0026: a NULL default is no default and is not deferred");
                    }
                }
            }

            /**
             * The expression of a MySQL {@code DEFAULT (expression)}, alone, as
             * {@code information_schema.COLUMNS.COLUMN_DEFAULT} reports it: without the parentheses MySQL's
             * syntax puts around it, which a renderer supplies. Kept exactly, never translated.
             */
            record Expression(String expression) implements Value {

                public Expression {
                    Checks.nonEmpty(expression, "expression");
                }
            }
        }
    }

    private static void requireColumn(TableCoordinate table, MappedColumn column) {
        Checks.present(column, "column");
        ColumnCoordinate coordinate = column.source();
        if (!coordinate.database().equals(table.database()) || !coordinate.table().equals(table.table())) {
            throw new IllegalArgumentException(coordinate + " is not a column of " + table);
        }
    }

    private static void requireColumns(
            String what, TableCoordinate table, List<ColumnCoordinate> expected, List<MappedColumn> mapped) {
        List<ColumnCoordinate> actual = mapped.stream().map(MappedColumn::source).toList();
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(what + " of " + table + " maps " + actual + " but its columns are "
                    + expected + ", one mapping each in order");
        }
        mapped.forEach(column -> requireColumn(table, column));
    }
}
