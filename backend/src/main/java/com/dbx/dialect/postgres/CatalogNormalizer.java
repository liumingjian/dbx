package com.dbx.dialect.postgres;

import com.dbx.dialect.api.IdentityIntent;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.ResultRows;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TargetColumnFacts;
import com.dbx.dialect.api.TargetIdentifier;
import com.dbx.dialect.api.TargetSequenceFacts;
import com.dbx.dialect.api.TargetTableCoordinate;
import com.dbx.dialect.api.TargetTableFacts;
import com.dbx.dialect.api.TargetType;
import com.dbx.dialect.api.TargetTypeName;
import com.dbx.dialect.postgres.CatalogRead.Column;
import com.dbx.dialect.postgres.CatalogRead.Fact;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the rows of {@link CatalogRead} into one {@link TargetTableFacts} per table, in the order the server
 * returned them. It compares nothing: {@code contract.prove} owns every comparison ({@code contract.md}
 * obligation 20).
 *
 * <p>Two rules pull in opposite directions and both are deliberate:
 *
 * <ul>
 *   <li>A <em>broken read</em> throws {@link IllegalArgumentException} naming the coordinate: rows whose
 *       result schema is not the plan's, a table row for a coordinate nobody asked for, two table rows for
 *       one coordinate, or a column, key, check or sequence row for a table with no table row.
 *   <li>A <em>catalog state DBX cannot name</em> never throws. A type outside TP §6.2–6.4 survives as the
 *       catalog's own {@code format_type} text with no parsed {@link TargetType}, and {@code attidentity = 'a'}
 *       ({@code GENERATED ALWAYS}) survives as text with no parsed {@link IdentityIntent}. Both are
 *       differences for {@code contract.prove} to report.
 * </ul>
 */
final class CatalogNormalizer {

    /**
     * {@code format_type} output DBX can name, e.g. {@code character varying(64)} or
     * {@code timestamp(3) without time zone}. Anything else — an array, a domain, an extension type — does not
     * match and stays as text.
     */
    private static final Pattern FORMAT_TYPE =
            Pattern.compile("([a-z ]+?)(?:\\((\\d+(?:,\\d+)*)\\))?( without time zone| with time zone)?");

    /** A single-quoted PostgreSQL string constant, {@code ''} doubling included. */
    private static final Pattern LITERAL = Pattern.compile("'((?:[^']|'')*)'");

    private CatalogNormalizer() {
    }

    static List<TargetTableFacts> normalize(List<TargetTableCoordinate> coordinates, ResultRows rows) {
        Objects.requireNonNull(coordinates, "coordinates are required");
        Objects.requireNonNull(rows, "rows are required");
        if (!rows.schema().equals(CatalogRead.SCHEMA)) {
            throw new IllegalArgumentException("these rows were not read by target.catalogReadPlan: their result "
                    + "schema differs from the plan's");
        }
        LinkedHashMap<TargetTableCoordinate, TableFacts> tables = new LinkedHashMap<>();
        for (Fact fact : Fact.values()) {
            for (ResultRows.Row row : rows.rows()) {
                if (factOf(row) == fact) {
                    read(fact, new Values(row), coordinates, tables);
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
        throw new IllegalArgumentException("a catalog row names an unknown fact: " + name);
    }

    private static void read(Fact fact, Values row, List<TargetTableCoordinate> coordinates,
            LinkedHashMap<TargetTableCoordinate, TableFacts> tables) {
        TargetTableCoordinate table = new TargetTableCoordinate(
                new TargetIdentifier(row.text(Column.TABLE_SCHEMA)), new TargetIdentifier(row.text(Column.TABLE_NAME)));
        if (fact == Fact.TABLE) {
            if (!coordinates.contains(table)) {
                throw new IllegalArgumentException("TP §7.4: the catalog read binds the coordinates it asks for, so "
                        + where(table) + " in this row set is a broken read, not a difference");
            }
            if (tables.containsKey(table)) {
                throw new IllegalArgumentException(where(table) + " has two TABLE rows");
            }
            tables.put(table, new TableFacts(table, row));
            return;
        }
        TableFacts facts = tables.get(table);
        if (facts == null) {
            throw new IllegalArgumentException("a " + fact + " row names " + where(table) + ", which has no TABLE row");
        }
        switch (fact) {
            case COLUMN -> facts.column(row);
            case PRIMARY_KEY -> facts.keyColumn(row);
            case ENUM_CHECK -> facts.check(row);
            case SEQUENCE -> facts.sequence(row);
            case TABLE -> throw new AssertionError("handled above");
        }
    }

    private static String where(TargetTableCoordinate table) {
        return "table " + table.schema().quoted() + "." + table.name().quoted();
    }

    /** One table's facts, collected in row order. */
    private static final class TableFacts {

        private final TargetTableCoordinate table;
        private final String relkind;
        private final long pgClassOid;
        private final List<ColumnFacts> columns = new ArrayList<>();
        private final List<TargetIdentifier> primaryKey = new ArrayList<>();
        private final List<TargetSequenceFacts> sequences = new ArrayList<>();

        TableFacts(TargetTableCoordinate table, Values row) {
            this.table = table;
            this.relkind = row.text(Column.RELKIND);
            this.pgClassOid = row.longValue(Column.PG_CLASS_OID);
        }

        void column(Values row) {
            columns.add(new ColumnFacts(
                    new TargetIdentifier(row.text(Column.COLUMN_NAME)),
                    row.text(Column.CATALOG_TYPE),
                    row.bool(Column.NOT_NULL) ? Nullability.NOT_NULL : Nullability.NULLABLE,
                    row.optionalText(Column.COLUMN_DEFAULT),
                    row.text(Column.IDENTITY)));
        }

        void keyColumn(Values row) {
            primaryKey.add(new TargetIdentifier(row.text(Column.KEY_COLUMN_NAME)));
        }

        void check(Values row) {
            TargetIdentifier column = new TargetIdentifier(row.text(Column.CHECK_COLUMN_NAME));
            columns.stream().filter(facts -> facts.name.equals(column)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("a CHECK row names column " + column.quoted()
                            + " of " + where(table) + ", which has no COLUMN row"))
                    .checkDefinitions.add(row.text(Column.CHECK_DEFINITION));
        }

        void sequence(Values row) {
            sequences.add(new TargetSequenceFacts(
                    new TargetTableCoordinate(new TargetIdentifier(row.text(Column.SEQUENCE_SCHEMA)),
                            new TargetIdentifier(row.text(Column.SEQUENCE_NAME))),
                    new TargetIdentifier(row.text(Column.SEQUENCE_OWNER_COLUMN)),
                    row.text(Column.SEQUENCE_DATA_TYPE)));
        }

        TargetTableFacts build() {
            return new TargetTableFacts(table, relkind, pgClassOid,
                    columns.stream().map(column -> column.build(sequences)).toList(),
                    List.copyOf(primaryKey), List.copyOf(sequences));
        }
    }

    /** One column's facts while its {@code CHECK} constraints are still arriving. */
    private static final class ColumnFacts {

        final TargetIdentifier name;
        final String catalogType;
        final Nullability nullability;
        final Optional<String> defaultExpression;
        final String identityText;
        final List<String> checkDefinitions = new ArrayList<>();

        ColumnFacts(TargetIdentifier name, String catalogType, Nullability nullability,
                Optional<String> defaultExpression, String identityText) {
            this.name = name;
            this.catalogType = catalogType;
            this.nullability = nullability;
            this.defaultExpression = defaultExpression;
            this.identityText = identityText;
        }

        TargetColumnFacts build(List<TargetSequenceFacts> sequences) {
            List<String> members = new ArrayList<>();
            checkDefinitions.forEach(definition -> members.addAll(literalsIn(definition)));
            return new TargetColumnFacts(name, catalogType, targetType(catalogType), nullability, defaultExpression,
                    identityText, identity(sequences), List.copyOf(checkDefinitions), members);
        }

        /**
         * {@code attidentity} is empty, {@code d} ({@code GENERATED BY DEFAULT}) or {@code a}
         * ({@code GENERATED ALWAYS}). An empty one on a column that owns a sequence is ADR-0011's owned-sequence
         * identity; {@code a} is a state the approved contract cannot express, so it stays as text.
         */
        Optional<IdentityIntent> identity(List<TargetSequenceFacts> sequences) {
            if (identityText.equals("d")) {
                return Optional.of((IdentityIntent) IdentityIntent.IDENTITY_BY_DEFAULT);
            }
            if (!identityText.isEmpty()) {
                return Optional.empty();
            }
            return sequences.stream()
                    .filter(sequence -> sequence.ownerColumn().equals(name))
                    .<IdentityIntent>map(sequence -> new IdentityIntent.OwnedSequence(sequence.sequence().name()))
                    .findFirst()
                    .or(() -> Optional.of((IdentityIntent) IdentityIntent.NONE));
        }
    }

    /**
     * The {@link TargetType} behind a {@code format_type} rendering, or empty when DBX cannot name it. Empty is
     * not a failure: the catalog's own text is kept and {@code contract.prove} reports the difference.
     */
    static Optional<TargetType> targetType(String catalogType) {
        Matcher matcher = FORMAT_TYPE.matcher(catalogType);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String base = matcher.group(1) + Optional.ofNullable(matcher.group(3)).orElse("");
        TargetTypeName name = switch (base) {
            case "smallint" -> TargetTypeName.SMALLINT;
            case "integer" -> TargetTypeName.INTEGER;
            case "bigint" -> TargetTypeName.BIGINT;
            case "numeric" -> TargetTypeName.NUMERIC;
            case "real" -> TargetTypeName.REAL;
            case "double precision" -> TargetTypeName.DOUBLE_PRECISION;
            case "boolean" -> TargetTypeName.BOOLEAN;
            case "character" -> TargetTypeName.CHAR;
            case "character varying" -> TargetTypeName.VARCHAR;
            case "text" -> TargetTypeName.TEXT;
            case "bytea" -> TargetTypeName.BYTEA;
            case "json" -> TargetTypeName.JSON;
            case "jsonb" -> TargetTypeName.JSONB;
            case "date" -> TargetTypeName.DATE;
            case "time without time zone" -> TargetTypeName.TIME;
            case "timestamp without time zone" -> TargetTypeName.TIMESTAMP;
            case "timestamp with time zone" -> TargetTypeName.TIMESTAMPTZ;
            default -> null;
        };
        if (name == null) {
            return Optional.empty();
        }
        List<Integer> modifiers = matcher.group(2) == null ? List.of()
                : Arrays.stream(matcher.group(2).split(",")).map(Integer::valueOf).toList();
        // TP §6.2–6.4 fixes how many modifiers each target type carries; a rendering without them (bare
        // "numeric", "timestamp without time zone") is a type the approved contract never asks for.
        int expected = switch (name) {
            case NUMERIC -> 2;
            case CHAR, VARCHAR, TIME, TIMESTAMP, TIMESTAMPTZ -> 1;
            default -> 0;
        };
        return modifiers.size() == expected ? Optional.of(new TargetType(name, modifiers)) : Optional.empty();
    }

    /**
     * Every single-quoted constant of a {@code CHECK} definition, in order, unescaped. PostgreSQL re-renders an
     * {@code x IN (…)} check in its own shape, so the members are read as the constants it holds rather than by
     * matching one spelling of the expression. A definition holding none yields none, and the definition text is
     * kept either way.
     */
    static List<String> literalsIn(String definition) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = LITERAL.matcher(definition);
        while (matcher.find()) {
            literals.add(matcher.group(1).replace("''", "'"));
        }
        return literals;
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

        long longValue(Column column) {
            return switch (value(column)) {
                case SqlValue.Int64 number -> number.value();
                case SqlValue.Null n when n.type() == SqlValue.Type.INT64 -> throw isNull(column);
                default -> throw wrongType(column);
            };
        }

        boolean bool(Column column) {
            return switch (value(column)) {
                case SqlValue.Bool flag -> flag.value();
                case SqlValue.Null n when n.type() == SqlValue.Type.BOOLEAN -> throw isNull(column);
                default -> throw wrongType(column);
            };
        }

        private IllegalArgumentException wrongType(Column column) {
            return new IllegalArgumentException(column.label + " is declared " + column.type.valueType
                    + " but the row holds " + value(column));
        }

        private IllegalArgumentException isNull(Column column) {
            return new IllegalArgumentException(column.label + " is NULL on a " + optionalText(Column.FACT).orElse("?")
                    + " row of table " + optionalText(Column.TABLE_NAME).orElse("?") + " in schema "
                    + optionalText(Column.TABLE_SCHEMA).orElse("?"));
        }
    }
}
