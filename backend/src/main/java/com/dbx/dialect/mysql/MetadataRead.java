package com.dbx.dialect.mysql;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.MetadataScope;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TableCoordinate;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The source metadata plan (obligations 15, 19b). Two statements:
 *
 * <ol>
 *   <li>{@code SET SESSION information_schema_stats_expiry = 0}, so {@code TABLES} statistics are read
 *       from the storage engine instead of MySQL 8.0's cache of up to a day. It needs no privilege and
 *       writes nothing; {@code ANALYZE TABLE} would need {@code INSERT} (ADR-0006).
 *   <li>One read whose rows are facts of four kinds ({@link Fact}), stacked with {@code UNION ALL} into
 *       one typed result so the plan has one {@link ResultSchema}. A column a fact does not own is
 *       {@code NULL} on its rows.
 * </ol>
 *
 * <p>Database and table names are bound values in every predicate; no identifier becomes SQL text.
 * Each fact's rows are numbered by the server ({@code ROW_NUMBER() OVER (ORDER BY …)} over the
 * {@code information_schema} columns themselves, so index names order in the server's collation), and
 * the result is ordered by that number. Text is converted to {@code utf8mb4_bin} only after ordering,
 * so the four branches cannot disagree on a collation.
 */
final class MetadataRead {

    static final String STATS_EXPIRY = "SET SESSION information_schema_stats_expiry = 0";

    /** The kinds of fact the read returns, in branch order. */
    enum Fact {
        TABLE("information_schema.TABLES t", "t.TABLE_SCHEMA", "t.TABLE_NAME",
                "t.TABLE_NAME", " AND t.TABLE_TYPE = 'BASE TABLE'"),
        COLUMN("information_schema.COLUMNS c", "c.TABLE_SCHEMA", "c.TABLE_NAME",
                "c.TABLE_NAME, c.ORDINAL_POSITION", ""),
        INDEX("information_schema.STATISTICS s", "s.TABLE_SCHEMA", "s.TABLE_NAME",
                "s.TABLE_NAME, s.INDEX_NAME, s.SEQ_IN_INDEX", ""),
        FOREIGN_KEY("information_schema.REFERENTIAL_CONSTRAINTS r LEFT JOIN information_schema.KEY_COLUMN_USAGE k "
                + "ON k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA AND k.TABLE_NAME = r.TABLE_NAME "
                + "AND k.CONSTRAINT_NAME = r.CONSTRAINT_NAME AND k.REFERENCED_TABLE_NAME IS NOT NULL",
                "r.CONSTRAINT_SCHEMA", "r.TABLE_NAME", "r.TABLE_NAME, r.CONSTRAINT_NAME, k.ORDINAL_POSITION", "");

        final String from;
        final String schemaColumn;
        final String tableColumn;
        final String serverOrder;
        final String extraPredicate;

        Fact(String from, String schemaColumn, String tableColumn, String serverOrder, String extraPredicate) {
            this.from = from;
            this.schemaColumn = schemaColumn;
            this.tableColumn = tableColumn;
            this.serverOrder = serverOrder;
            this.extraPredicate = extraPredicate;
        }
    }

    enum Type {
        TEXT("varchar", SqlValue.Type.TEXT),
        INT("bigint", SqlValue.Type.INT64);

        final String databaseType;
        final SqlValue.Type valueType;

        Type(String databaseType, SqlValue.Type valueType) {
            this.databaseType = databaseType;
            this.valueType = valueType;
        }
    }

    /** The result columns, in order. A column with an owner is {@code NULL} on every other fact's rows. */
    enum Column {
        FACT("fact", Type.TEXT, null, null),
        FACT_ORDER("fact_order", Type.INT, null, null),
        TABLE_SCHEMA("table_schema", Type.TEXT, null, null),
        TABLE_NAME("table_name", Type.TEXT, null, null),

        TABLE_ROWS("table_rows", Type.INT, Fact.TABLE, "t.TABLE_ROWS"),
        AVG_ROW_LENGTH("avg_row_length", Type.INT, Fact.TABLE, "t.AVG_ROW_LENGTH"),
        DATA_LENGTH("data_length", Type.INT, Fact.TABLE, "t.DATA_LENGTH"),
        AUTO_INCREMENT("auto_increment", Type.INT, Fact.TABLE, "t.AUTO_INCREMENT"),
        TABLE_COMMENT("table_comment", Type.TEXT, Fact.TABLE, "t.TABLE_COMMENT"),
        TABLE_COLLATION("table_collation", Type.TEXT, Fact.TABLE, "t.TABLE_COLLATION"),

        COLUMN_NAME("column_name", Type.TEXT, Fact.COLUMN, "c.COLUMN_NAME"),
        DATA_TYPE("data_type", Type.TEXT, Fact.COLUMN, "c.DATA_TYPE"),
        COLUMN_TYPE("column_type", Type.TEXT, Fact.COLUMN, "c.COLUMN_TYPE"),
        CHARACTER_MAXIMUM_LENGTH("character_maximum_length", Type.INT, Fact.COLUMN, "c.CHARACTER_MAXIMUM_LENGTH"),
        CHARACTER_OCTET_LENGTH("character_octet_length", Type.INT, Fact.COLUMN, "c.CHARACTER_OCTET_LENGTH"),
        NUMERIC_PRECISION("numeric_precision", Type.INT, Fact.COLUMN, "c.NUMERIC_PRECISION"),
        NUMERIC_SCALE("numeric_scale", Type.INT, Fact.COLUMN, "c.NUMERIC_SCALE"),
        DATETIME_PRECISION("datetime_precision", Type.INT, Fact.COLUMN, "c.DATETIME_PRECISION"),
        CHARACTER_SET_NAME("character_set_name", Type.TEXT, Fact.COLUMN, "c.CHARACTER_SET_NAME"),
        COLLATION_NAME("collation_name", Type.TEXT, Fact.COLUMN, "c.COLLATION_NAME"),
        IS_NULLABLE("is_nullable", Type.TEXT, Fact.COLUMN, "c.IS_NULLABLE"),
        COLUMN_DEFAULT("column_default", Type.TEXT, Fact.COLUMN, "c.COLUMN_DEFAULT"),
        EXTRA("extra", Type.TEXT, Fact.COLUMN, "c.EXTRA"),
        ORDINAL_POSITION("ordinal_position", Type.INT, Fact.COLUMN, "c.ORDINAL_POSITION"),
        COLUMN_COMMENT("column_comment", Type.TEXT, Fact.COLUMN, "c.COLUMN_COMMENT"),

        INDEX_NAME("index_name", Type.TEXT, Fact.INDEX, "s.INDEX_NAME"),
        NON_UNIQUE("non_unique", Type.INT, Fact.INDEX, "s.NON_UNIQUE"),
        SEQ_IN_INDEX("seq_in_index", Type.INT, Fact.INDEX, "s.SEQ_IN_INDEX"),
        INDEX_COLUMN_NAME("index_column_name", Type.TEXT, Fact.INDEX, "s.COLUMN_NAME"),
        EXPRESSION("expression", Type.TEXT, Fact.INDEX, "s.EXPRESSION"),
        SUB_PART("sub_part", Type.INT, Fact.INDEX, "s.SUB_PART"),
        INDEX_COLLATION("index_collation", Type.TEXT, Fact.INDEX, "s.COLLATION"),
        IS_VISIBLE("is_visible", Type.TEXT, Fact.INDEX, "s.IS_VISIBLE"),
        INDEX_TYPE("index_type", Type.TEXT, Fact.INDEX, "s.INDEX_TYPE"),

        CONSTRAINT_NAME("constraint_name", Type.TEXT, Fact.FOREIGN_KEY, "r.CONSTRAINT_NAME"),
        FK_COLUMN_NAME("fk_column_name", Type.TEXT, Fact.FOREIGN_KEY, "k.COLUMN_NAME"),
        FK_ORDINAL_POSITION("fk_ordinal_position", Type.INT, Fact.FOREIGN_KEY, "k.ORDINAL_POSITION"),
        REFERENCED_TABLE_SCHEMA("referenced_table_schema", Type.TEXT, Fact.FOREIGN_KEY, "r.UNIQUE_CONSTRAINT_SCHEMA"),
        REFERENCED_TABLE_NAME("referenced_table_name", Type.TEXT, Fact.FOREIGN_KEY, "r.REFERENCED_TABLE_NAME"),
        REFERENCED_COLUMN_NAME("referenced_column_name", Type.TEXT, Fact.FOREIGN_KEY, "k.REFERENCED_COLUMN_NAME"),
        UPDATE_RULE("update_rule", Type.TEXT, Fact.FOREIGN_KEY, "r.UPDATE_RULE"),
        DELETE_RULE("delete_rule", Type.TEXT, Fact.FOREIGN_KEY, "r.DELETE_RULE");

        final String label;
        final Type type;
        final Fact owner;
        final String source;

        Column(String label, Type type, Fact owner, String source) {
            this.label = label;
            this.type = type;
            this.owner = owner;
            this.source = source;
        }
    }

    /** Every column is typed; the four shared columns are never {@code NULL}. */
    static final ResultSchema SCHEMA = new ResultSchema(
            Arrays.stream(Column.values())
                    .map(column -> new ResultSchema.Column(column.label, column.type.databaseType,
                            column.owner == null ? Nullability.NOT_NULL : Nullability.NULLABLE))
                    .toList(),
            ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS);

    private MetadataRead() {
    }

    static SqlPlan plan(MetadataScope scope) {
        Objects.requireNonNull(scope, "scope is required");
        if (scope.tables().isEmpty()) {
            throw new IllegalArgumentException("a metadata plan reads at least one table; the scope of "
                    + scope.database() + " selects none");
        }
        Set<TableCoordinate> seen = new HashSet<>();
        for (TableCoordinate table : scope.tables()) {
            if (!table.database().equals(scope.database().database())) {
                throw new IllegalArgumentException(table + " is outside the scope's database " + scope.database());
            }
            if (!seen.add(table)) {
                throw new IllegalArgumentException(table + " appears twice in the scope");
            }
        }

        String inList = scope.tables().stream().map(table -> "?").collect(Collectors.joining(", "));
        List<SqlValue> branchParameters = new ArrayList<>();
        branchParameters.add(new SqlValue.Text(scope.database().database()));
        scope.tables().forEach(table -> branchParameters.add(new SqlValue.Text(table.table())));

        List<String> branches = new ArrayList<>();
        List<SqlValue> parameters = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            branches.add(branch(fact, inList));
            parameters.addAll(branchParameters);
        }
        String read = String.join("\nUNION ALL\n", branches) + "\nORDER BY fact, fact_order";

        return new SqlPlan(
                OperationKind.SOURCE_METADATA_READ,
                List.of(new ParameterizedStatement(STATS_EXPIRY, List.of()), new ParameterizedStatement(read, parameters)),
                SCHEMA,
                TimeoutClass.CATALOG_READ,
                Set.of(RequiredPrivilege.SOURCE_READ_METADATA),
                EvidencePolicy.STATEMENT_AND_RESULT);
    }

    private static String branch(Fact fact, String inList) {
        boolean first = fact.ordinal() == 0;
        List<String> expressions = new ArrayList<>();
        for (Column column : Column.values()) {
            String expression = switch (column) {
                case FACT -> "'" + fact.name() + "'";
                case FACT_ORDER -> "CAST(ROW_NUMBER() OVER (ORDER BY " + fact.serverOrder + ") AS SIGNED)";
                case TABLE_SCHEMA -> typed(Type.TEXT, fact.schemaColumn);
                case TABLE_NAME -> typed(Type.TEXT, fact.tableColumn);
                default -> column.owner == fact ? typed(column.type, column.source) : "NULL";
            };
            expressions.add(first ? expression + " AS " + column.label : expression);
        }
        return "SELECT " + String.join(", ", expressions) + " FROM " + fact.from
                + " WHERE " + fact.schemaColumn + " = ? AND " + fact.tableColumn + " IN (" + inList + ")"
                + fact.extraPredicate;
    }

    private static String typed(Type type, String source) {
        return switch (type) {
            case TEXT -> "CONVERT(" + source + " USING utf8mb4) COLLATE utf8mb4_bin";
            case INT -> "CAST(" + source + " AS SIGNED)";
        };
    }
}
