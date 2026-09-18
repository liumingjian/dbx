package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.dbx.dialect.api.KeysetCandidate.Origin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * {@code source.keysetCandidates} (obligation 18; ticket #129): ADR-0037 §Choice from {@link SourceTableMetadata}
 * alone, reached through {@code DialectCatalog.compileTime().select(...)}. Minimum and maximum are data facts
 * for slice 6 and are not judged here; filtering out pruned candidates is the caller's job.
 */
class KeysetCandidatesContractTest {

    private static final SourceDialect SOURCE = MappingCase.PAIR.source();
    private static final TableCoordinate TABLE = new TableCoordinate("shop", "orders");

    // --- Fixture: a table as information_schema reports it ----------------------------------------

    private record Col(String name, String dataType, String columnType, Nullability nullability) {
    }

    private static Col notNull(String name, String columnType) {
        return new Col(name, columnType.split("[( ]")[0], columnType, Nullability.NOT_NULL);
    }

    private static Col nullable(String name, String columnType) {
        return new Col(name, columnType.split("[( ]")[0], columnType, Nullability.NULLABLE);
    }

    private static ColumnCoordinate at(String column) {
        return new ColumnCoordinate(TABLE.database(), TABLE.table(), column);
    }

    private static SourceIndex.KeyPart part(int sequence, String column) {
        return new SourceIndex.KeyPart(sequence, new SourceIndex.Subject.Column(at(column)), OptionalLong.empty(),
                SourceIndex.Direction.ASCENDING);
    }

    private static SourceIndex unique(String name, String... columns) {
        return index(name, true, true, columns);
    }

    private static SourceIndex index(String name, boolean unique, boolean visible, String... columns) {
        List<SourceIndex.KeyPart> parts = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            parts.add(part(i + 1, columns[i]));
        }
        return new SourceIndex(name, unique, visible, SourceIndex.IndexType.BTREE, parts);
    }

    private static SourceIndex expression(String name, String text) {
        return new SourceIndex(name, true, true, SourceIndex.IndexType.BTREE, List.of(new SourceIndex.KeyPart(1,
                new SourceIndex.Subject.Expression(text), OptionalLong.empty(), SourceIndex.Direction.ASCENDING)));
    }

    private static SourceTableMetadata table(List<Col> cols, SourceIndex... indexes) {
        List<SourceColumn> columns = new ArrayList<>();
        List<ColumnComment> comments = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Col col = cols.get(i);
            boolean integer = col.columnType().matches("(tiny|small|medium|big)?int.*");
            columns.add(new SourceColumn(at(col.name()), col.dataType(), col.columnType(),
                    col.columnType().contains("unsigned"), OptionalLong.empty(), OptionalLong.empty(),
                    integer ? OptionalInt.of(10) : OptionalInt.empty(), integer ? OptionalInt.of(0) : OptionalInt.empty(),
                    OptionalInt.empty(), Optional.empty(), Optional.empty(), col.nullability(), Optional.empty(), "",
                    i + 1));
            comments.add(new ColumnComment(at(col.name()), ""));
        }
        return new SourceTableMetadata(TABLE, columns, comments, List.of(indexes), List.of(), "", Optional.empty(),
                new TableStatistics(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty()));
    }

    private static KeysetCandidate pk(String column) {
        return new KeysetCandidate(at(column), Origin.PRIMARY_KEY);
    }

    private static KeysetCandidate uk(String column) {
        return new KeysetCandidate(at(column), Origin.UNIQUE_INDEX);
    }

    // --- Order (ADR-0037 §Choice) -----------------------------------------------------------------

    @Test
    void thePrimaryKeyPrecedesUniqueIndexes() {
        SourceTableMetadata orders = table(List.of(notNull("code", "int"), notNull("id", "bigint")),
                unique("a_code", "code"), unique(SourceIndex.PRIMARY, "id"));

        assertEquals(List.of(pk("id"), uk("code")), SOURCE.keysetCandidates(orders),
                "ADR-0037 §Choice: the primary key comes first, whatever the index row order");
    }

    @Test
    void uniqueIndexOrderIsRowOrderNotJavaOrder() {
        List<String> serverOrder = List.of("a_created", "B_customer", "c_lower");
        assertNotEquals(serverOrder, serverOrder.stream().sorted(Comparator.naturalOrder()).toList(),
                "ADR-0037 §Choice: the fixture must be one where String.compareTo disagrees with the server's collation");
        SourceTableMetadata orders = table(
                List.of(notNull("created", "bigint"), notNull("customer", "int"), notNull("lower", "smallint")),
                unique("a_created", "created"), unique("B_customer", "customer"), unique("c_lower", "lower"));

        assertEquals(List.of(uk("created"), uk("customer"), uk("lower")), SOURCE.keysetCandidates(orders),
                "ADR-0037 §Choice: \"lowest name\" is the source server's collation as the rows carry it, never "
                        + "Java's String.compareTo");
    }

    @Test
    void aColumnThatIsBothPrimaryKeyAndUniqueAppearsOnceAsPrimaryKey() {
        SourceTableMetadata orders = table(List.of(notNull("id", "bigint"), notNull("code", "int")),
                unique("a_id", "id"), unique(SourceIndex.PRIMARY, "id"), unique("b_code", "code"),
                unique("c_code_again", "code"));

        assertEquals(List.of(pk("id"), uk("code")), SOURCE.keysetCandidates(orders),
                "ADR-0037 §Choice (obligation 18): a column is offered once, as PRIMARY_KEY when the primary key is on it");
    }

    @Test
    void anInvisibleUniqueIndexQualifies() {
        SourceTableMetadata orders = table(List.of(notNull("code", "int")), index("hidden", true, false, "code"));

        assertEquals(List.of(uk("code")), SOURCE.keysetCandidates(orders),
                "ADR-0037 §Choice: an invisible unique index still enforces uniqueness");
    }

    // --- Inclusion and exclusion ------------------------------------------------------------------

    @Test
    void everyIntegerTypeSignedOrUnsignedQualifies() {
        List<String> types = List.of("tinyint", "tinyint unsigned", "smallint", "smallint unsigned", "mediumint",
                "mediumint unsigned", "int", "int unsigned", "bigint", "bigint unsigned");
        List<Col> cols = new ArrayList<>();
        List<SourceIndex> indexes = new ArrayList<>();
        List<KeysetCandidate> expected = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            cols.add(notNull("c" + i, types.get(i)));
            indexes.add(unique("u" + i, "c" + i));
            expected.add(uk("c" + i));
        }

        assertEquals(expected, SOURCE.keysetCandidates(table(cols, indexes.toArray(SourceIndex[]::new))),
                "ADR-0037 §Choice (obligation 18): tinyint, smallint, mediumint, int and bigint qualify, signed and "
                        + "unsigned");
    }

    @Test
    void compositeNullableNonIntegerBooleanAndExpressionKeysAreExcluded() {
        SourceTableMetadata orders = table(List.of(
                        notNull("a", "int"), notNull("b", "int"), nullable("maybe", "bigint"),
                        notNull("code", "varchar(32)"), notNull("amount", "decimal(20,0)"),
                        notNull("flag", "tinyint(1)"), notNull("uflag", "tinyint(1) unsigned"), notNull("plain", "int")),
                unique(SourceIndex.PRIMARY, "a", "b"),
                unique("u_a_b", "a", "b"),
                unique("u_maybe", "maybe"),
                unique("u_code", "code"),
                unique("u_amount", "amount"),
                unique("u_flag", "flag"),
                unique("u_uflag", "uflag"),
                expression("u_expr", "(`plain` + 1)"),
                index("n_plain", false, true, "plain"));

        assertEquals(List.of(), SOURCE.keysetCandidates(orders),
                "ADR-0037 §Choice (obligation 18): composite, nullable, varchar, decimal(p,0), tinyint(1), expression "
                        + "and non-unique "
                        + "indexes never offer a keyset column");
    }

    @Test
    void aKeyPartOverAColumnPrefixOffersNothing() {
        SourceIndex prefix = new SourceIndex("u_prefix", true, true, SourceIndex.IndexType.BTREE,
                List.of(new SourceIndex.KeyPart(1, new SourceIndex.Subject.Column(at("code")), OptionalLong.of(4),
                        SourceIndex.Direction.ASCENDING)));
        SourceTableMetadata orders = table(List.of(notNull("code", "int")), prefix);

        assertEquals(List.of(), SOURCE.keysetCandidates(orders),
                "ADR-0037 §Choice: a unique index over a prefix of a column does not make the whole column unique, "
                        + "so it offers no keyset column; MySQL allows a prefix only on a string column, but "
                        + "SourceIndex.KeyPart carries SUB_PART whatever the column is");
    }

    @Test
    void aTableWithoutACandidateGetsAnEmptyList() {
        SourceTableMetadata log = table(List.of(nullable("line", "text")));

        assertEquals(List.of(), SOURCE.keysetCandidates(log),
                "ADR-0037 §Bulk path (obligation 18): an empty list is a valid answer; the table goes to the bulk path");
    }
}
