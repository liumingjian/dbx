package com.dbx.dialect.postgres;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TargetTableCoordinate;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code target.catalogReadPlan} (obligation 26): one read of everything structural proof compares
 * (TP §7.4; ADR-0011 §DDL and structural proof; ADR-0023).
 *
 * <p>One statement whose rows are facts of five kinds ({@link Fact}), stacked with {@code UNION ALL} into one
 * typed result so the plan has one {@link ResultSchema}. A column a fact does not own is {@code NULL} on its
 * rows, and every expression is cast to the column's declared type so the branches cannot disagree.
 *
 * <p><strong>No identifier becomes SQL text here.</strong> Schema and table names are bound values against
 * {@code pg_namespace.nspname} and {@code pg_class.relname}, one pair of placeholders per coordinate per
 * branch. That is also what makes the read closed: the only tables it can return are the ones it binds.
 * Contrast {@code target.maintenancePlans}, whose DDL does quote an identifier.
 *
 * <p>That this query returns what {@link CatalogNormalizer} parses cannot be proven at L1: it is proven at L2
 * by {@code contract} slice 8 through {@code gateway} against a real PostgreSQL 15 ({@code dialect.md}
 * §Verification).
 */
final class CatalogRead {

    /** The kinds of fact the read returns, in branch order. */
    enum Fact {
        TABLE("pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace", "", "0"),
        COLUMN("pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid "
                + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum",
                " AND a.attnum > 0 AND NOT a.attisdropped", "a.attnum"),
        PRIMARY_KEY("pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_constraint k ON k.conrelid = c.oid AND k.contype = 'p' "
                + "JOIN pg_catalog.pg_index i ON i.indexrelid = k.conindid "
                + "JOIN LATERAL unnest(k.conkey) WITH ORDINALITY AS kc(attnum, ord) ON true "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = kc.attnum",
                "", "kc.ord"),
        ENUM_CHECK("pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_constraint k ON k.conrelid = c.oid AND k.contype = 'c' "
                + "JOIN LATERAL unnest(k.conkey) WITH ORDINALITY AS kc(attnum, ord) ON true "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = kc.attnum",
                "", "a.attnum"),
        SEQUENCE("pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_catalog.pg_depend dep ON dep.refclassid = 'pg_catalog.pg_class'::regclass "
                + "AND dep.refobjid = c.oid AND dep.classid = 'pg_catalog.pg_class'::regclass "
                + "AND dep.deptype = 'a' "
                + "JOIN pg_catalog.pg_class s ON s.oid = dep.objid AND s.relkind = 'S' "
                + "JOIN pg_catalog.pg_namespace sn ON sn.oid = s.relnamespace "
                + "JOIN pg_catalog.pg_sequence q ON q.seqrelid = s.oid "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = dep.refobjsubid",
                "", "dep.refobjsubid");

        final String from;
        final String extraPredicate;
        final String factOrder;

        Fact(String from, String extraPredicate, String factOrder) {
            this.from = from;
            this.extraPredicate = extraPredicate;
            this.factOrder = factOrder;
        }
    }

    enum Type {
        TEXT("text", SqlValue.Type.TEXT),
        INT("bigint", SqlValue.Type.INT64),
        BOOL("boolean", SqlValue.Type.BOOLEAN);

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

        RELKIND("relkind", Type.TEXT, Fact.TABLE, "c.relkind::text"),
        PG_CLASS_OID("pg_class_oid", Type.INT, Fact.TABLE, "c.oid::bigint"),

        COLUMN_NAME("column_name", Type.TEXT, Fact.COLUMN, "a.attname::text"),
        CATALOG_TYPE("catalog_type", Type.TEXT, Fact.COLUMN, "pg_catalog.format_type(a.atttypid, a.atttypmod)"),
        NOT_NULL("not_null", Type.BOOL, Fact.COLUMN, "a.attnotnull"),
        IDENTITY("identity", Type.TEXT, Fact.COLUMN, "a.attidentity::text"),
        COLUMN_DEFAULT("column_default", Type.TEXT, Fact.COLUMN, "pg_catalog.pg_get_expr(d.adbin, d.adrelid)"),

        KEY_COLUMN_NAME("key_column_name", Type.TEXT, Fact.PRIMARY_KEY, "a.attname::text"),

        CHECK_COLUMN_NAME("check_column_name", Type.TEXT, Fact.ENUM_CHECK, "a.attname::text"),
        CHECK_DEFINITION("check_definition", Type.TEXT, Fact.ENUM_CHECK, "pg_catalog.pg_get_constraintdef(k.oid)"),

        SEQUENCE_SCHEMA("sequence_schema", Type.TEXT, Fact.SEQUENCE, "sn.nspname::text"),
        SEQUENCE_NAME("sequence_name", Type.TEXT, Fact.SEQUENCE, "s.relname::text"),
        SEQUENCE_OWNER_COLUMN("sequence_owner_column", Type.TEXT, Fact.SEQUENCE, "a.attname::text"),
        SEQUENCE_DATA_TYPE("sequence_data_type", Type.TEXT, Fact.SEQUENCE,
                "pg_catalog.format_type(q.seqtypid, NULL)");

        final String label;
        final Type type;
        final Fact owner;
        final String expression;

        Column(String label, Type type, Fact owner, String expression) {
            this.label = label;
            this.type = type;
            this.owner = owner;
            this.expression = expression;
        }
    }

    /**
     * The typed result the plan declares and the only rows {@code normalizeCatalog} accepts. The four
     * discriminator columns are always present; a fact-owned column is {@code NULL} on the other facts' rows.
     */
    static final ResultSchema SCHEMA = new ResultSchema(
            Arrays.stream(Column.values())
                    .map(column -> new ResultSchema.Column(column.label, column.type.databaseType,
                            column.owner == null ? Nullability.NOT_NULL : Nullability.NULLABLE))
                    .toList(),
            ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS);

    private CatalogRead() {
    }

    static SqlPlan plan(List<TargetTableCoordinate> coordinates) {
        if (coordinates.isEmpty()) {
            throw new IllegalArgumentException("TP §7.4: a catalog read names the tables structural proof "
                    + "compares; an empty coordinate list is a caller bug, not an empty proof");
        }
        Set<TargetTableCoordinate> distinct = new HashSet<>(coordinates);
        if (distinct.size() != coordinates.size()) {
            throw new IllegalArgumentException("TP §7.4: a coordinate appears twice in the catalog read, so one "
                    + "table would be proven against two row sets: " + coordinates);
        }
        List<String> branches = new ArrayList<>();
        List<SqlValue> parameters = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            branches.add(branch(fact, coordinates));
            for (TargetTableCoordinate coordinate : coordinates) {
                parameters.add(new SqlValue.Text(coordinate.schema().name()));
                parameters.add(new SqlValue.Text(coordinate.name().name()));
            }
        }
        String sql = String.join(" UNION ALL ", branches)
                + " ORDER BY fact, table_schema, table_name, fact_order, check_definition, sequence_name";
        return new SqlPlan(
                OperationKind.TARGET_CATALOG_READ,
                List.of(new ParameterizedStatement(sql, parameters)),
                SCHEMA,
                TimeoutClass.CATALOG_READ,
                Set.of(RequiredPrivilege.TARGET_READ_CATALOG),
                EvidencePolicy.STATEMENT_AND_RESULT);
    }

    /** One {@code UNION ALL} branch. Only the first branch carries {@code AS <label>}; the rest match by position. */
    private static String branch(Fact fact, List<TargetTableCoordinate> coordinates) {
        boolean aliased = fact.ordinal() == 0;
        List<String> selected = new ArrayList<>();
        for (Column column : Column.values()) {
            String expression = switch (column) {
                case FACT -> "'" + fact.name() + "'::text";
                case FACT_ORDER -> fact.factOrder + "::bigint";
                case TABLE_SCHEMA -> "n.nspname::text";
                case TABLE_NAME -> "c.relname::text";
                default -> column.owner == fact ? column.expression : "NULL::" + column.type.databaseType;
            };
            selected.add(aliased ? expression + " AS " + column.label : expression);
        }
        String predicate = coordinates.stream()
                .map(coordinate -> "(n.nspname = ? AND c.relname = ?)")
                .collect(Collectors.joining(" OR "));
        return "SELECT " + String.join(", ", selected) + " FROM " + fact.from
                + " WHERE (" + predicate + ")" + fact.extraPredicate;
    }
}
