package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code source.metadataPlan} and {@code source.normalizeMetadata} (obligations 15 and 19b; ticket #125),
 * driven through {@code dialect.api} with values. What L1 cannot prove: that MySQL 8.0 accepts the text,
 * types the result as declared, orders index names in its collation and returns fresh statistics after
 * the stats-expiry statement. That is L2/L3's (docs/spec/dialect.md §Verification).
 */
class SourceMetadataContractTest {

    private static final SourceDialect SOURCE = MappingCase.PAIR.source();

    private static MetadataScope scope(String database, String... tables) {
        List<TableCoordinate> coordinates = new ArrayList<>();
        for (String table : tables) {
            coordinates.add(new TableCoordinate(database, table));
        }
        return new MetadataScope(new SchemaCoordinate(database), coordinates);
    }

    // --- Plan shape (ADR-0008 §Plans; ADR-0006) ---------------------------------------------------

    /** The typed result every metadata read returns, column by column. */
    private static final List<ResultSchema.Column> EXPECTED_COLUMNS = List.of(
            notNull("fact", "varchar"), notNull("fact_order", "bigint"),
            notNull("table_schema", "varchar"), notNull("table_name", "varchar"),
            nullable("table_rows", "bigint"), nullable("avg_row_length", "bigint"), nullable("data_length", "bigint"),
            nullable("auto_increment", "bigint"),
            nullable("table_comment", "varchar"), nullable("table_collation", "varchar"),
            nullable("column_name", "varchar"), nullable("data_type", "varchar"), nullable("column_type", "varchar"),
            nullable("character_maximum_length", "bigint"), nullable("character_octet_length", "bigint"),
            nullable("numeric_precision", "bigint"), nullable("numeric_scale", "bigint"),
            nullable("datetime_precision", "bigint"), nullable("character_set_name", "varchar"),
            nullable("collation_name", "varchar"), nullable("is_nullable", "varchar"),
            nullable("column_default", "varchar"), nullable("extra", "varchar"), nullable("ordinal_position", "bigint"),
            nullable("column_comment", "varchar"),
            nullable("index_name", "varchar"), nullable("non_unique", "bigint"), nullable("seq_in_index", "bigint"),
            nullable("index_column_name", "varchar"), nullable("expression", "varchar"), nullable("sub_part", "bigint"),
            nullable("index_collation", "varchar"), nullable("is_visible", "varchar"), nullable("index_type", "varchar"),
            nullable("constraint_name", "varchar"), nullable("fk_column_name", "varchar"),
            nullable("fk_ordinal_position", "bigint"), nullable("referenced_table_schema", "varchar"),
            nullable("referenced_table_name", "varchar"), nullable("referenced_column_name", "varchar"),
            nullable("update_rule", "varchar"), nullable("delete_rule", "varchar"));

    @Test
    void theMetadataPlanIsACatalogReadNeedingOnlyMetadataPrivilege() {
        SqlPlan plan = SOURCE.metadataPlan(scope("shop", "orders", "客户"));

        assertEquals(OperationKind.SOURCE_METADATA_READ, plan.operationKind(), "obligation 15: one metadata read");
        assertEquals(TimeoutClass.CATALOG_READ, plan.timeoutClass(), "ADR-0008 §Plans: a catalog read's timeout");
        assertEquals(Set.of(RequiredPrivilege.SOURCE_READ_METADATA), plan.requiredPrivileges(),
                "ADR-0006: reading metadata needs nothing beyond metadata access — no INSERT for ANALYZE TABLE");
        assertEquals(EvidencePolicy.STATEMENT_AND_RESULT, plan.evidencePolicy(),
                "ADR-0008 §Plans: metadata facts are evidence, and hold no row values");
        assertEquals(new ResultSchema(EXPECTED_COLUMNS, ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS),
                plan.resultSchema(), "ADR-0008 §Plans: the result is typed column by column");
    }

    /** Written by hand from ticket #125, not copied from the implementation's output. */
    private static final String EXPECTED_READ = """
            SELECT 'TABLE' AS fact, CAST(ROW_NUMBER() OVER (ORDER BY t.TABLE_NAME) AS SIGNED) AS fact_order, \
            CONVERT(t.TABLE_SCHEMA USING utf8mb4) COLLATE utf8mb4_bin AS table_schema, \
            CONVERT(t.TABLE_NAME USING utf8mb4) COLLATE utf8mb4_bin AS table_name, \
            CAST(t.TABLE_ROWS AS SIGNED) AS table_rows, \
            CAST(t.AVG_ROW_LENGTH AS SIGNED) AS avg_row_length, \
            CAST(t.DATA_LENGTH AS SIGNED) AS data_length, \
            CAST(t.AUTO_INCREMENT AS SIGNED) AS auto_increment, \
            CONVERT(t.TABLE_COMMENT USING utf8mb4) COLLATE utf8mb4_bin AS table_comment, \
            CONVERT(t.TABLE_COLLATION USING utf8mb4) COLLATE utf8mb4_bin AS table_collation, \
            NULL AS column_name, NULL AS data_type, NULL AS column_type, NULL AS character_maximum_length, \
            NULL AS character_octet_length, NULL AS numeric_precision, NULL AS numeric_scale, \
            NULL AS datetime_precision, NULL AS character_set_name, NULL AS collation_name, NULL AS is_nullable, \
            NULL AS column_default, NULL AS extra, NULL AS ordinal_position, NULL AS column_comment, \
            NULL AS index_name, NULL AS non_unique, NULL AS seq_in_index, NULL AS index_column_name, \
            NULL AS expression, NULL AS sub_part, NULL AS index_collation, NULL AS is_visible, NULL AS index_type, \
            NULL AS constraint_name, NULL AS fk_column_name, NULL AS fk_ordinal_position, \
            NULL AS referenced_table_schema, NULL AS referenced_table_name, NULL AS referenced_column_name, \
            NULL AS update_rule, NULL AS delete_rule \
            FROM information_schema.TABLES t \
            WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME IN (?, ?) AND t.TABLE_TYPE = 'BASE TABLE'
            UNION ALL
            SELECT 'COLUMN', CAST(ROW_NUMBER() OVER (ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION) AS SIGNED), \
            CONVERT(c.TABLE_SCHEMA USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.TABLE_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            NULL, NULL, NULL, NULL, NULL, NULL, \
            CONVERT(c.COLUMN_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.DATA_TYPE USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.COLUMN_TYPE USING utf8mb4) COLLATE utf8mb4_bin, \
            CAST(c.CHARACTER_MAXIMUM_LENGTH AS SIGNED), CAST(c.CHARACTER_OCTET_LENGTH AS SIGNED), \
            CAST(c.NUMERIC_PRECISION AS SIGNED), CAST(c.NUMERIC_SCALE AS SIGNED), \
            CAST(c.DATETIME_PRECISION AS SIGNED), \
            CONVERT(c.CHARACTER_SET_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.COLLATION_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.IS_NULLABLE USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.COLUMN_DEFAULT USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(c.EXTRA USING utf8mb4) COLLATE utf8mb4_bin, \
            CAST(c.ORDINAL_POSITION AS SIGNED), \
            CONVERT(c.COLUMN_COMMENT USING utf8mb4) COLLATE utf8mb4_bin, \
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, \
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL \
            FROM information_schema.COLUMNS c \
            WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME IN (?, ?)
            UNION ALL
            SELECT 'INDEX', CAST(ROW_NUMBER() OVER (ORDER BY s.TABLE_NAME, s.INDEX_NAME, s.SEQ_IN_INDEX) AS SIGNED), \
            CONVERT(s.TABLE_SCHEMA USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(s.TABLE_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            NULL, NULL, NULL, NULL, NULL, NULL, \
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, \
            CONVERT(s.INDEX_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CAST(s.NON_UNIQUE AS SIGNED), CAST(s.SEQ_IN_INDEX AS SIGNED), \
            CONVERT(s.COLUMN_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(s.EXPRESSION USING utf8mb4) COLLATE utf8mb4_bin, \
            CAST(s.SUB_PART AS SIGNED), \
            CONVERT(s.COLLATION USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(s.IS_VISIBLE USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(s.INDEX_TYPE USING utf8mb4) COLLATE utf8mb4_bin, \
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL \
            FROM information_schema.STATISTICS s \
            WHERE s.TABLE_SCHEMA = ? AND s.TABLE_NAME IN (?, ?)
            UNION ALL
            SELECT 'FOREIGN_KEY', \
            CAST(ROW_NUMBER() OVER (ORDER BY r.TABLE_NAME, r.CONSTRAINT_NAME, k.ORDINAL_POSITION) AS SIGNED), \
            CONVERT(r.CONSTRAINT_SCHEMA USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(r.TABLE_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            NULL, NULL, NULL, NULL, NULL, NULL, \
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, \
            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, \
            CONVERT(r.CONSTRAINT_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(k.COLUMN_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CAST(k.ORDINAL_POSITION AS SIGNED), \
            CONVERT(r.UNIQUE_CONSTRAINT_SCHEMA USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(r.REFERENCED_TABLE_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(k.REFERENCED_COLUMN_NAME USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(r.UPDATE_RULE USING utf8mb4) COLLATE utf8mb4_bin, \
            CONVERT(r.DELETE_RULE USING utf8mb4) COLLATE utf8mb4_bin \
            FROM information_schema.REFERENTIAL_CONSTRAINTS r \
            LEFT JOIN information_schema.KEY_COLUMN_USAGE k \
            ON k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA AND k.TABLE_NAME = r.TABLE_NAME \
            AND k.CONSTRAINT_NAME = r.CONSTRAINT_NAME AND k.REFERENCED_TABLE_NAME IS NOT NULL \
            WHERE r.CONSTRAINT_SCHEMA = ? AND r.TABLE_NAME IN (?, ?)
            ORDER BY fact, fact_order""";

    @Test
    void theStatementTextIsPinnedForARepresentativeScope() {
        SqlPlan plan = SOURCE.metadataPlan(scope("shop", "orders", "客户"));

        assertEquals(2, plan.statements().size(), "obligation 19b: the stats-expiry statement, then the read");
        assertEquals("SET SESSION information_schema_stats_expiry = 0", plan.statements().get(0).sql(),
                "obligation 19b: statistics are read fresh, by a session setting that needs no privilege");
        assertEquals(List.of(), plan.statements().get(0).parameters(),
                "obligation 19b: the stats-expiry statement binds nothing");
        assertEquals(EXPECTED_READ, plan.statements().get(1).sql(),
                "obligation 15: the read is exactly COLUMNS, STATISTICS, KEY_COLUMN_USAGE + REFERENTIAL_CONSTRAINTS "
                        + "and TABLES, names bound, rows numbered by the server");
        List<SqlValue> perBranch = List.of(new SqlValue.Text("shop"), new SqlValue.Text("orders"), new SqlValue.Text("客户"));
        List<SqlValue> expected = new ArrayList<>();
        for (int branch = 0; branch < 4; branch++) {
            expected.addAll(perBranch);
        }
        assertEquals(expected, plan.statements().get(1).parameters(),
                "ADR-0008 §Plans: the database and every table name are bound, once per information_schema read");
    }

    @Test
    void theStatsExpiryStatementPrecedesTheTablesRead() {
        List<ParameterizedStatement> statements = SOURCE.metadataPlan(scope("shop", "orders")).statements();

        int expiry = indexOf(statements, "information_schema_stats_expiry = 0");
        int tables = indexOf(statements, "FROM information_schema.TABLES");
        assertTrue(expiry >= 0 && tables >= 0 && expiry < tables,
                "obligation 19b: MySQL 8.0 caches TABLE_ROWS for up to a day, so the session expiry is set to 0 "
                        + "before TABLES is read; expiry at " + expiry + ", TABLES at " + tables);
        for (ParameterizedStatement statement : statements) {
            assertFalse(statement.sql().toUpperCase(java.util.Locale.ROOT).contains("ANALYZE"),
                    "ADR-0006: ANALYZE TABLE needs INSERT, which the read-only account does not hold");
        }
    }

    private static int indexOf(List<ParameterizedStatement> statements, String fragment) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).sql().contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    // --- Fingerprints (ADR-0008 §Plans) -----------------------------------------------------------

    @Test
    void distinctScopesGiveDistinctFingerprints() {
        SqlPlan base = SOURCE.metadataPlan(scope("shop", "orders", "customers"));
        Map<String, SqlPlan> variants = Map.of(
                "database", SOURCE.metadataPlan(scope("shop2", "orders", "customers")),
                "one table name", SOURCE.metadataPlan(scope("shop", "orders", "customer")),
                "a table added", SOURCE.metadataPlan(scope("shop", "orders", "customers", "items")),
                "a table removed", SOURCE.metadataPlan(scope("shop", "orders")),
                "table order", SOURCE.metadataPlan(scope("shop", "customers", "orders")),
                "name case", SOURCE.metadataPlan(scope("shop", "Orders", "customers")));

        assertEquals(base.fingerprint(), SOURCE.metadataPlan(scope("shop", "orders", "customers")).fingerprint(),
                "ADR-0008 §Plans: an equal scope is an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : new TreeMap<>(variants).entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only the " + variant.getKey() + " must change the fingerprint");
        }
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints), with the statement text taken from {@link #EXPECTED_READ} and the schema from
     * {@link #EXPECTED_COLUMNS}, in a separate Python script quoted on ticket #125 and re-run on ticket #135,
     * which added {@code auto_increment} to the {@code TABLES} branch (spec #134 correction 5).
     */
    @Test
    void theRepresentativeFingerprintIsPinned() {
        assertEquals("9e681cdb8298b594af1a8c857d59b98a1c87f235dca13551f08fb4b60ee8342b",
                SOURCE.metadataPlan(scope("shop", "orders", "客户")).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the metadata plan's fingerprint must not depend on the JVM, machine or run");
    }

    // --- Hostile names (ADR-0008 §Plans; TP §7.1) --------------------------------------------------

    @Test
    void hostileNamesAreOnlyEverBoundValues() {
        List<String> hostile = List.of(
                "'", "\\", "$$", "`", "a`b`", "x'); DROP TABLE t; --", "?", "new\nline", "\u0001adjacent\u007F",
                "￿\u0001", "emoji😀", "客户订单", "x".repeat(63), "x".repeat(64), "订".repeat(21),
                "订".repeat(21) + "x");
        SqlPlan benign = SOURCE.metadataPlan(scope("shop", "orders"));
        for (String name : hostile) {
            SqlPlan plan = SOURCE.metadataPlan(scope(name, name));
            for (int i = 0; i < plan.statements().size(); i++) {
                ParameterizedStatement statement = plan.statements().get(i);
                assertEquals(benign.statements().get(i).sql(), statement.sql(),
                        "ADR-0008 §Plans: a hostile name never changes statement structure: " + name);
                assertEquals(placeholders(statement.sql()), statement.parameters().size(),
                        "ADR-0008 §Plans: one bound value per placeholder: " + name);
            }
            ParameterizedStatement read = plan.statements().get(1);
            assertTrue(read.parameters().stream().allMatch(value -> value.equals(new SqlValue.Text(name))),
                    "ADR-0008 §Plans: the hostile name travels only as a bound value: " + name);
            if (!benign.statements().get(1).sql().contains(name)) {
                assertFalse(read.sql().contains(name), "obligation 15: no identifier is spliced into metadata SQL: " + name);
            }
        }
    }

    /** Counts {@code ?} outside quotes, independently of {@link ParameterizedStatement}'s own count. */
    private static int placeholders(String sql) {
        int count = 0;
        boolean quoted = false;
        for (char c : sql.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (c == '?' && !quoted) {
                count++;
            }
        }
        return count;
    }

    @Test
    void aScopeThatCannotBeReadIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SOURCE.metadataPlan(scope("shop")),
                "obligation 15: a metadata read of no table is not a plan: IN () is not SQL");
        assertThrows(IllegalArgumentException.class, () -> SOURCE.metadataPlan(new MetadataScope(
                new SchemaCoordinate("shop"), List.of(new TableCoordinate("other", "orders")))),
                "obligation 15: a scope covers one database; a table of another is a caller error");
        assertThrows(IllegalArgumentException.class, () -> SOURCE.metadataPlan(scope("shop", "orders", "orders")),
                "obligation 15: a table is read once");
    }

    // --- Normalisation (obligations 15, 19b) ------------------------------------------------------

    private static final TableCoordinate ORDERS = new TableCoordinate("shop", "orders");
    private static final TableCoordinate CUSTOMERS = new TableCoordinate("shop", "客户");

    private static ColumnCoordinate orders(String column) {
        return new ColumnCoordinate("shop", "orders", column);
    }

    /** Every fact kind, with names whose server order Java's {@code String.compareTo} would change. */
    private static SourceTableMetadata ordersFixture() {
        List<SourceColumn> columns = List.of(
                new SourceColumn(orders("id"), "bigint", "bigint unsigned", true, OptionalLong.empty(),
                        OptionalLong.empty(), OptionalInt.of(20), OptionalInt.of(0), OptionalInt.empty(),
                        Optional.empty(), Optional.empty(), Nullability.NOT_NULL, Optional.empty(), "auto_increment", 1),
                new SourceColumn(orders("code"), "varchar", "varchar(32)", false, OptionalLong.of(32),
                        OptionalLong.of(128), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                        Optional.of("utf8mb4"), Optional.of("utf8mb4_0900_ai_ci"), Nullability.NULLABLE,
                        Optional.of("it's \\ `x`"), "", 2),
                new SourceColumn(orders("customer_id"), "int", "int", false, OptionalLong.empty(), OptionalLong.empty(),
                        OptionalInt.of(10), OptionalInt.of(0), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                        Nullability.NOT_NULL, Optional.empty(), "", 3),
                new SourceColumn(orders("created"), "datetime", "datetime(3)", false, OptionalLong.empty(),
                        OptionalLong.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.of(3), Optional.empty(),
                        Optional.empty(), Nullability.NOT_NULL, Optional.of("CURRENT_TIMESTAMP(3)"),
                        "DEFAULT_GENERATED on update CURRENT_TIMESTAMP(3)", 4));
        List<ColumnComment> comments = List.of(new ColumnComment(orders("id"), "主键"),
                new ColumnComment(orders("code"), ""), new ColumnComment(orders("customer_id"), "客户'编号"),
                new ColumnComment(orders("created"), ""));
        List<SourceIndex> indexes = List.of(
                new SourceIndex("a_created", false, false, SourceIndex.IndexType.BTREE, List.of(
                        part(1, column("created"), OptionalLong.empty(), SourceIndex.Direction.DESCENDING))),
                new SourceIndex("B_customer", true, true, SourceIndex.IndexType.BTREE, List.of(
                        part(1, column("customer_id"), OptionalLong.empty(), SourceIndex.Direction.ASCENDING),
                        part(2, column("code"), OptionalLong.of(8), SourceIndex.Direction.ASCENDING))),
                new SourceIndex("c_lower", true, true, SourceIndex.IndexType.BTREE, List.of(
                        part(1, new SourceIndex.Subject.Expression("lower(`code`)"), OptionalLong.empty(),
                                SourceIndex.Direction.ASCENDING))),
                new SourceIndex("PRIMARY", true, true, SourceIndex.IndexType.BTREE, List.of(
                        part(1, column("id"), OptionalLong.empty(), SourceIndex.Direction.ASCENDING))),
                new SourceIndex("x_code", false, true, SourceIndex.IndexType.HASH, List.of(
                        part(1, column("code"), OptionalLong.empty(), SourceIndex.Direction.NOT_SORTED))));
        List<SourceForeignKey> foreignKeys = List.of(
                new SourceForeignKey("fk_customer", new TableCoordinate("shop", "customers"), List.of(
                        new SourceForeignKey.Part(orders("customer_id"), new ColumnCoordinate("shop", "customers", "id"))),
                        SourceForeignKey.ReferentialAction.CASCADE, SourceForeignKey.ReferentialAction.NO_ACTION),
                new SourceForeignKey("Fk_code", new TableCoordinate("ref", "codes"), List.of(
                        new SourceForeignKey.Part(orders("code"), new ColumnCoordinate("ref", "codes", "code")),
                        new SourceForeignKey.Part(orders("created"), new ColumnCoordinate("ref", "codes", "at"))),
                        SourceForeignKey.ReferentialAction.SET_NULL, SourceForeignKey.ReferentialAction.RESTRICT));
        return new SourceTableMetadata(ORDERS, columns, comments, indexes, foreignKeys, "订单 'orders'",
                Optional.of("utf8mb4_0900_ai_ci"),
                new TableStatistics(OptionalLong.of(1000), OptionalLong.of(128), OptionalLong.of(16384),
                        OptionalLong.of(2048)));
    }

    /** A table without keys whose statistics MySQL reports as NULL, next to a zero that stays zero. */
    private static SourceTableMetadata customersFixture() {
        ColumnCoordinate name = new ColumnCoordinate("shop", "客户", "名称");
        return new SourceTableMetadata(CUSTOMERS,
                List.of(new SourceColumn(name, "set", "set('a unsigned','b')", false, OptionalLong.of(13),
                        OptionalLong.of(52), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(),
                        Optional.of("utf8mb4"), Optional.of("utf8mb4_bin"), Nullability.NULLABLE, Optional.empty(), "", 1)),
                List.of(new ColumnComment(name, "")), List.of(), List.of(), "", Optional.empty(),
                new TableStatistics(OptionalLong.empty(), OptionalLong.of(0), OptionalLong.empty(), OptionalLong.empty()));
    }

    private static SourceIndex.Subject column(String name) {
        return new SourceIndex.Subject.Column(orders(name));
    }

    private static SourceIndex.KeyPart part(int sequence, SourceIndex.Subject subject, OptionalLong prefix,
            SourceIndex.Direction direction) {
        return new SourceIndex.KeyPart(sequence, subject, prefix, direction);
    }

    @Test
    void factsRoundTripFromTypedRowsUnchanged() {
        List<SourceTableMetadata> expected = List.of(ordersFixture(), customersFixture());

        assertEquals(expected, SOURCE.normalizeMetadata(rowsOf(expected)),
                "obligation 15: normalisation keeps every raw information_schema fact, one metadata per table");
    }

    @Test
    void nullStatisticsStayAbsentNeverZero() {
        SourceTableMetadata customers = SOURCE.normalizeMetadata(rowsOf(List.of(customersFixture()))).get(0);

        assertEquals(OptionalLong.empty(), customers.statistics().tableRows(),
                "ADR-0002 ¶4: a NULL TABLE_ROWS is absent, never 0; judging it is preflight's");
        assertEquals(OptionalLong.empty(), customers.statistics().dataLength(),
                "ADR-0002 ¶4: a NULL DATA_LENGTH is absent, never 0");
        assertEquals(OptionalLong.of(0), customers.statistics().averageRowLength(),
                "ADR-0002 ¶4: a reported 0 is a fact and stays 0");
    }

    @Test
    void indexOrderFollowsRowOrderNotJavaOrder() {
        List<String> serverOrder = List.of("a_created", "B_customer", "c_lower", "PRIMARY", "x_code");
        List<String> javaOrder = serverOrder.stream().sorted(Comparator.naturalOrder()).toList();
        assertNotEquals(serverOrder, javaOrder, "ADR-0037 §Choice: the fixture must be one where Java's order disagrees");

        SourceTableMetadata orders = SOURCE.normalizeMetadata(rowsOf(List.of(ordersFixture()))).get(0);

        assertEquals(serverOrder, orders.indexes().stream().map(SourceIndex::name).toList(),
                "ADR-0037 §Choice: index order is the server's collation order as the rows carry it, never "
                        + "re-sorted in Java");
        assertEquals(List.of("fk_customer", "Fk_code"), orders.foreignKeys().stream().map(SourceForeignKey::name).toList(),
                "obligation 15: foreign keys keep row order too");
        assertEquals(Optional.of("PRIMARY"), orders.primaryKey().map(SourceIndex::name),
                "obligation 15: the primary key is the index MySQL names PRIMARY");
    }

    @Test
    void tablesComeOutInRowOrder() {
        List<SourceTableMetadata> reversed = List.of(customersFixture(), ordersFixture());

        assertEquals(List.of(CUSTOMERS, ORDERS),
                SOURCE.normalizeMetadata(rowsOf(reversed)).stream().map(SourceTableMetadata::table).toList(),
                "obligation 15: normalizeMetadata never re-sorts tables; their order is the server's");
    }

    // --- Contradictory rows (ticket #125) ---------------------------------------------------------

    @Test
    void aKeyPartNamingAnUnknownColumnIsRefusedNamingIt() {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.stream().filter(row -> text("B_customer").equals(row.get("index_name")))
                .findFirst().orElseThrow().put("index_column_name", text("ghost"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.normalizeMetadata(rows(rows)),
                "obligation 15: a key part naming an unknown column is a broken read, not an Unsupported");
        assertTrue(failure.getMessage().contains(orders("ghost").toString()),
                "obligation 15: a contradictory row is refused naming its coordinate: " + failure.getMessage());
    }

    @Test
    void aForeignKeyWithoutColumnsIsRefusedNamingIt() {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.removeIf(row -> text("fk_customer").equals(row.get("constraint_name")));
        Map<String, SqlValue> orphan = row("FOREIGN_KEY", 9, ORDERS);
        orphan.put("constraint_name", text("fk_orphan"));
        orphan.put("referenced_table_schema", text("shop"));
        orphan.put("referenced_table_name", text("customers"));
        orphan.put("update_rule", text("CASCADE"));
        orphan.put("delete_rule", text("CASCADE"));
        rows.add(orphan);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.normalizeMetadata(rows(rows)),
                "obligation 15: a foreign key without its columns is a broken read, not an Unsupported");
        assertTrue(failure.getMessage().contains("fk_orphan") && failure.getMessage().contains(ORDERS.toString()),
                "obligation 15: a contradictory row is refused naming its coordinate: " + failure.getMessage());
    }

    @Test
    void aForeignKeyColumnThatIsNotInTheTableIsRefusedNamingIt() {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.stream().filter(row -> text("fk_customer").equals(row.get("constraint_name")))
                .findFirst().orElseThrow().put("fk_column_name", text("ghost"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.normalizeMetadata(rows(rows)),
                "obligation 15: a foreign key column outside its table is a broken read, not an Unsupported");
        assertTrue(failure.getMessage().contains(orders("ghost").toString()),
                "obligation 15: a contradictory row is refused naming its coordinate: " + failure.getMessage());
    }

    @Test
    void aFactForATableWithoutItsTablesRowIsRefusedNamingIt() {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.removeIf(row -> text("TABLE").equals(row.get("fact")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.normalizeMetadata(rows(rows)),
                "obligation 15: facts of a table without its TABLES row are a broken read, not an Unsupported");
        assertTrue(failure.getMessage().contains(ORDERS.toString()),
                "obligation 15: a contradictory row is refused naming its coordinate: " + failure.getMessage());
    }

    @Test
    void anUnknownIndexTypeIsRefusedNamingIt() {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.stream().filter(row -> text("B_customer").equals(row.get("index_name")))
                .forEach(row -> row.put("index_type", text("RTREE")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.normalizeMetadata(rows(rows)),
                "obligation 15: an INDEX_TYPE outside BTREE, HASH, FULLTEXT and SPATIAL is a broken read");
        assertTrue(failure.getMessage().contains("B_customer") && failure.getMessage().contains(ORDERS.toString())
                        && failure.getMessage().contains("RTREE"),
                "obligation 15: the refusal names the index, its table and the type: " + failure.getMessage());
    }

    /** The one row whose {@code column} holds {@code value}; the fixture has exactly one. */
    private static Map<String, SqlValue> first(List<Map<String, SqlValue>> rows, String column, String value) {
        return rows.stream().filter(row -> text(value).equals(row.get(column))).findFirst().orElseThrow();
    }

    /** Every row whose {@code column} holds {@code value}, in row order. */
    private static List<Map<String, SqlValue>> all(List<Map<String, SqlValue>> rows, String column, String value) {
        return rows.stream().filter(row -> text(value).equals(row.get(column))).toList();
    }

    private static IllegalArgumentException refused(List<Map<String, SqlValue>> rows, String why) {
        return assertThrows(IllegalArgumentException.class, () -> SOURCE.normalizeMetadata(rows(rows)), why);
    }

    @Test
    void aValueOfTheWrongSqlTypeIsRefusedNamingTheColumn() {
        List<Map<String, SqlValue>> textIsANumber = rowMaps(List.of(ordersFixture()));
        first(textIsANumber, "fact", "TABLE").put("table_comment", integer(7));
        List<Map<String, SqlValue>> numberIsText = rowMaps(List.of(ordersFixture()));
        first(numberIsText, "fact", "TABLE").put("table_rows", text("1000"));

        assertTrue(refused(textIsANumber, "obligation 15: a varchar column holding a number is a broken read, "
                        + "not an Unsupported").getMessage().contains("table_comment"),
                "obligation 15: the refusal names the column whose declared type the value contradicts");
        assertTrue(refused(numberIsText, "obligation 15: a bigint column holding text is a broken read")
                        .getMessage().contains("table_rows"),
                "obligation 15: the refusal names the column whose declared type the value contradicts");
    }

    @Test
    void aTypedNullInARequiredColumnIsRefusedNamingTheFactAndTheTable() {
        List<Map<String, SqlValue>> nullComment = rowMaps(List.of(ordersFixture()));
        first(nullComment, "fact", "TABLE").put("table_comment", new SqlValue.Null(SqlValue.Type.TEXT));
        List<Map<String, SqlValue>> nullOrdinal = rowMaps(List.of(ordersFixture()));
        first(nullOrdinal, "column_name", "code").put("ordinal_position", new SqlValue.Null(SqlValue.Type.INT64));

        String comment = refused(nullComment, "obligation 15: TABLE_COMMENT is '' when empty, never NULL, so a NULL "
                + "is a broken read").getMessage();
        assertTrue(comment.contains("table_comment") && comment.contains("TABLE") && comment.contains("orders")
                        && comment.contains("shop"),
                "obligation 15: a NULL in a required column is refused naming the column, the fact and the table: "
                        + comment);
        assertTrue(refused(nullOrdinal, "obligation 15: ORDINAL_POSITION is never NULL on a COLUMNS row")
                        .getMessage().contains("ordinal_position"),
                "obligation 15: the refusal names the required column that was NULL");
    }

    @Test
    void anIntegerFactOutsideIntRangeIsRefusedAndTheEdgesAreKept() {
        List<Map<String, SqlValue>> edges = rowMaps(List.of(ordersFixture()));
        first(edges, "column_name", "id").put("numeric_precision", integer(Integer.MAX_VALUE));
        first(edges, "column_name", "id").put("numeric_scale", integer(Integer.MIN_VALUE));
        List<Map<String, SqlValue>> tooHigh = rowMaps(List.of(ordersFixture()));
        first(tooHigh, "column_name", "id").put("numeric_precision", integer(Integer.MAX_VALUE + 1L));
        List<Map<String, SqlValue>> tooLow = rowMaps(List.of(ordersFixture()));
        first(tooLow, "column_name", "id").put("numeric_scale", integer(Integer.MIN_VALUE - 1L));

        SourceColumn id = SOURCE.normalizeMetadata(rows(edges)).get(0).columns().get(0);
        assertEquals(OptionalInt.of(Integer.MAX_VALUE), id.numericPrecision(),
                "obligation 15: a fact at the int edge is a fact and is kept; only what cannot be held is refused");
        assertEquals(OptionalInt.of(Integer.MIN_VALUE), id.numericScale(),
                "obligation 15: a fact at the other int edge is kept too");
        assertTrue(refused(tooHigh, "obligation 15: a SIGNED value above int range does not fit the fact it describes")
                        .getMessage().contains("numeric_precision"),
                "obligation 15: the refusal names the column that does not fit");
        assertTrue(refused(tooLow, "obligation 15: a SIGNED value below int range does not fit either")
                        .getMessage().contains("numeric_scale"),
                "obligation 15: the refusal names the column that does not fit");
    }

    @Test
    void keyPartsOfOneIndexMustAgreeOnUniquenessVisibilityAndType() {
        Map<String, SqlValue> uniqueness = second("B_customer");
        uniqueness.put("non_unique", integer(1));
        Map<String, SqlValue> visibility = second("B_customer");
        visibility.put("is_visible", text("NO"));
        Map<String, SqlValue> type = second("B_customer");
        type.put("index_type", text("HASH"));

        for (Map<String, SqlValue> broken : List.of(uniqueness, visibility, type)) {
            List<Map<String, SqlValue>> rows = rowsWith(broken, "B_customer", 2);
            assertTrue(refused(rows, "obligation 15: STATISTICS reports one index's uniqueness, visibility and type "
                            + "on every key-part row, so rows that disagree are a broken read")
                            .getMessage().contains("B_customer"),
                    "obligation 15: the refusal names the index whose key parts disagree");
        }
    }

    @Test
    void aKeyPartNamesExactlyOneOfAColumnOrAnExpression() {
        Map<String, SqlValue> both = second("B_customer");
        both.put("expression", text("lower(`code`)"));
        Map<String, SqlValue> neither = second("B_customer");
        neither.put("index_column_name", new SqlValue.Null(SqlValue.Type.TEXT));

        assertTrue(refused(rowsWith(both, "B_customer", 2), "obligation 15: STATISTICS fills COLUMN_NAME or "
                        + "EXPRESSION, never both").getMessage().contains("both"),
                "obligation 15: the refusal says the key part names both");
        assertTrue(refused(rowsWith(neither, "B_customer", 2), "obligation 15: a key part with neither is a broken "
                        + "read").getMessage().contains("neither"),
                "obligation 15: the refusal says the key part names neither");
    }

    @Test
    void foreignKeyRowsMustAgreeOnTheReferencedTableAndTheRules() {
        Map<String, SqlValue> table = secondForeignKey();
        table.put("referenced_table_name", text("others"));
        Map<String, SqlValue> update = secondForeignKey();
        update.put("update_rule", text("CASCADE"));
        Map<String, SqlValue> delete = secondForeignKey();
        delete.put("delete_rule", text("CASCADE"));

        for (Map<String, SqlValue> broken : List.of(table, update, delete)) {
            List<Map<String, SqlValue>> rows = foreignKeyRowsWith(broken);
            assertTrue(refused(rows, "obligation 15: one constraint's referenced table and referential rules repeat "
                            + "on every KEY_COLUMN_USAGE row, so rows that disagree are a broken read")
                            .getMessage().contains("Fk_code"),
                    "obligation 15: the refusal names the foreign key whose rows disagree");
        }
    }

    @Test
    void foreignKeyColumnsMustArriveInOrdinalPositionOrder() {
        Map<String, SqlValue> outOfOrder = secondForeignKey();
        outOfOrder.put("fk_ordinal_position", integer(3));

        String failure = refused(foreignKeyRowsWith(outOfOrder), "obligation 15: a foreign key's columns arrive one "
                + "per ORDINAL_POSITION from 1; a gap would silently reorder the constraint").getMessage();
        assertTrue(failure.contains("ORDINAL_POSITION 3") && failure.contains("2 comes next"),
                "obligation 15: the refusal names the position it got and the one it expected: " + failure);
    }

    @Test
    void aForeignKeyPartReferencingNoColumnIsRefusedNamingTheColumn() {
        Map<String, SqlValue> noReference = secondForeignKey();
        noReference.put("referenced_column_name", new SqlValue.Null(SqlValue.Type.TEXT));

        assertTrue(refused(foreignKeyRowsWith(noReference), "obligation 15: a KEY_COLUMN_USAGE row of a foreign key "
                        + "always names the referenced column").getMessage().contains("created"),
                "obligation 15: the refusal names the referencing column that references nothing");
    }

    @Test
    void aNegativeStatisticIsRefusedNamingIt() {
        for (String statistic : List.of("table_rows", "avg_row_length", "data_length", "auto_increment")) {
            List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
            first(rows, "fact", "TABLE").put(statistic, integer(-1));

            assertTrue(refused(rows, "ADR-0002 ¶4: a statistic is a count of rows or bytes, so a negative one is a "
                            + "broken read rather than a fact preflight could judge")
                            .getMessage().contains("cannot be negative"),
                    "ADR-0002 ¶4: the refusal says a count cannot be negative: " + statistic);
        }
    }

    @Test
    void aTableWithTwoTablesRowsIsRefusedNamingIt() {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.add(new HashMap<>(first(rows, "fact", "TABLE")));

        assertTrue(refused(rows, "obligation 15: TABLES has one row per table, so two are a broken read")
                        .getMessage().contains(ORDERS.toString()),
                "obligation 15: the refusal names the table read twice");
    }

    /** The second key-part row of {@code index}, detached from the fixture so a test can break it. */
    private static Map<String, SqlValue> second(String index) {
        return new HashMap<>(all(rowMaps(List.of(ordersFixture())), "index_name", index).get(1));
    }

    /** The second row of foreign key {@code Fk_code}, detached from the fixture. */
    private static Map<String, SqlValue> secondForeignKey() {
        return new HashMap<>(all(rowMaps(List.of(ordersFixture())), "constraint_name", "Fk_code").get(1));
    }

    /** The fixture's rows with {@code index}'s key part number {@code sequence} replaced by {@code broken}. */
    private static List<Map<String, SqlValue>> rowsWith(Map<String, SqlValue> broken, String index, int sequence) {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.set(rows.indexOf(all(rows, "index_name", index).get(sequence - 1)), broken);
        return rows;
    }

    /** The fixture's rows with {@code Fk_code}'s second row replaced by {@code broken}. */
    private static List<Map<String, SqlValue>> foreignKeyRowsWith(Map<String, SqlValue> broken) {
        List<Map<String, SqlValue>> rows = rowMaps(List.of(ordersFixture()));
        rows.set(rows.indexOf(all(rows, "constraint_name", "Fk_code").get(1)), broken);
        return rows;
    }

    @Test
    void rowsOfAnotherShapeAreRefused() {
        ResultRows foreign = new ResultRows(new ResultSchema(List.of(notNull("fact", "varchar")),
                ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS), List.of());

        assertThrows(IllegalArgumentException.class, () -> SOURCE.normalizeMetadata(foreign),
                "obligation 15: only rows read by source.metadataPlan can be normalised: positions are the plan's");
    }

    // --- Rows as the gateway would hand them back -------------------------------------------------

    private static ResultSchema.Column notNull(String label, String type) {
        return new ResultSchema.Column(label, type, Nullability.NOT_NULL);
    }

    private static ResultSchema.Column nullable(String label, String type) {
        return new ResultSchema.Column(label, type, Nullability.NULLABLE);
    }

    private static SqlValue text(String value) {
        return new SqlValue.Text(value);
    }

    private static SqlValue integer(long value) {
        return new SqlValue.Int64(value);
    }

    private static SqlValue text(Optional<String> value) {
        return value.<SqlValue>map(SqlValue.Text::new).orElse(new SqlValue.Null(SqlValue.Type.TEXT));
    }

    private static SqlValue integer(OptionalLong value) {
        return value.isPresent() ? integer(value.getAsLong()) : new SqlValue.Null(SqlValue.Type.INT64);
    }

    private static SqlValue integer(OptionalInt value) {
        return value.isPresent() ? integer(value.getAsInt()) : new SqlValue.Null(SqlValue.Type.INT64);
    }

    private static Map<String, SqlValue> row(String fact, long order, TableCoordinate table) {
        Map<String, SqlValue> row = new HashMap<>();
        row.put("fact", text(fact));
        row.put("fact_order", integer(order));
        row.put("table_schema", text(table.database()));
        row.put("table_name", text(table.table()));
        return row;
    }

    /** The rows the plan's ORDER BY fact, fact_order returns: COLUMN, FOREIGN_KEY, INDEX, then TABLE. */
    private static List<Map<String, SqlValue>> rowMaps(List<SourceTableMetadata> tables) {
        List<Map<String, SqlValue>> columns = new ArrayList<>();
        List<Map<String, SqlValue>> foreignKeys = new ArrayList<>();
        List<Map<String, SqlValue>> indexes = new ArrayList<>();
        List<Map<String, SqlValue>> tableRows = new ArrayList<>();
        for (SourceTableMetadata table : tables) {
            Map<String, SqlValue> t = row("TABLE", tableRows.size() + 1, table.table());
            t.put("table_rows", integer(table.statistics().tableRows()));
            t.put("avg_row_length", integer(table.statistics().averageRowLength()));
            t.put("data_length", integer(table.statistics().dataLength()));
            t.put("auto_increment", integer(table.statistics().autoIncrement()));
            t.put("table_comment", text(table.comment()));
            t.put("table_collation", text(table.collation()));
            tableRows.add(t);
            for (int i = 0; i < table.columns().size(); i++) {
                SourceColumn c = table.columns().get(i);
                Map<String, SqlValue> r = row("COLUMN", columns.size() + 1, table.table());
                r.put("column_name", text(c.coordinate().column()));
                r.put("data_type", text(c.dataType()));
                r.put("column_type", text(c.columnType()));
                r.put("character_maximum_length", integer(c.characterMaximumLength()));
                r.put("character_octet_length", integer(c.characterOctetLength()));
                r.put("numeric_precision", integer(c.numericPrecision()));
                r.put("numeric_scale", integer(c.numericScale()));
                r.put("datetime_precision", integer(c.datetimePrecision()));
                r.put("character_set_name", text(c.characterSetName()));
                r.put("collation_name", text(c.collationName()));
                r.put("is_nullable", text(c.nullability() == Nullability.NULLABLE ? "YES" : "NO"));
                r.put("column_default", text(c.columnDefault()));
                r.put("extra", text(c.extra()));
                r.put("ordinal_position", integer(c.ordinalPosition()));
                r.put("column_comment", text(table.columnComments().get(i).comment()));
                columns.add(r);
            }
            for (SourceIndex index : table.indexes()) {
                for (SourceIndex.KeyPart part : index.keyParts()) {
                    Map<String, SqlValue> r = row("INDEX", indexes.size() + 1, table.table());
                    r.put("index_name", text(index.name()));
                    r.put("non_unique", integer(index.unique() ? 0 : 1));
                    r.put("seq_in_index", integer(part.sequence()));
                    if (part.subject() instanceof SourceIndex.Subject.Column column) {
                        r.put("index_column_name", text(column.column().column()));
                    } else if (part.subject() instanceof SourceIndex.Subject.Expression expression) {
                        r.put("expression", text(expression.expression()));
                    }
                    r.put("sub_part", integer(part.prefixLength()));
                    switch (part.direction()) {
                        case ASCENDING -> r.put("index_collation", text("A"));
                        case DESCENDING -> r.put("index_collation", text("D"));
                        case NOT_SORTED -> { }
                    }
                    r.put("is_visible", text(index.visible() ? "YES" : "NO"));
                    r.put("index_type", text(index.indexType().name()));
                    indexes.add(r);
                }
            }
            for (SourceForeignKey foreignKey : table.foreignKeys()) {
                for (int i = 0; i < foreignKey.parts().size(); i++) {
                    SourceForeignKey.Part part = foreignKey.parts().get(i);
                    Map<String, SqlValue> r = row("FOREIGN_KEY", foreignKeys.size() + 1, table.table());
                    r.put("constraint_name", text(foreignKey.name()));
                    r.put("fk_column_name", text(part.column().column()));
                    r.put("fk_ordinal_position", integer(i + 1));
                    r.put("referenced_table_schema", text(foreignKey.referencedTable().database()));
                    r.put("referenced_table_name", text(foreignKey.referencedTable().table()));
                    r.put("referenced_column_name", text(part.referencedColumn().column()));
                    r.put("update_rule", text(foreignKey.onUpdate().name().replace('_', ' ')));
                    r.put("delete_rule", text(foreignKey.onDelete().name().replace('_', ' ')));
                    foreignKeys.add(r);
                }
            }
        }
        List<Map<String, SqlValue>> all = new ArrayList<>(columns);
        all.addAll(foreignKeys);
        all.addAll(indexes);
        all.addAll(tableRows);
        return all;
    }

    private static ResultRows rowsOf(List<SourceTableMetadata> tables) {
        return rows(rowMaps(tables));
    }

    /** Positions each value by the plan's own result schema; an unset column is its typed NULL. */
    private static ResultRows rows(List<Map<String, SqlValue>> maps) {
        ResultSchema schema = SOURCE.metadataPlan(scope("shop", "orders")).resultSchema();
        List<ResultRows.Row> rows = new ArrayList<>();
        for (Map<String, SqlValue> map : maps) {
            List<SqlValue> values = new ArrayList<>();
            for (ResultSchema.Column column : schema.columns()) {
                SqlValue.Type type = column.databaseType().equals("bigint") ? SqlValue.Type.INT64 : SqlValue.Type.TEXT;
                values.add(map.getOrDefault(column.label(), new SqlValue.Null(type)));
            }
            rows.add(new ResultRows.Row(values));
        }
        return new ResultRows(schema, rows);
    }
}
