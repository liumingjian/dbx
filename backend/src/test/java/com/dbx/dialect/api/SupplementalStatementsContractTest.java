package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.dialect.api.DeferredStructure.MappedColumn;
import com.dbx.dialect.api.DeferredStructure.ReferencedTable;
import com.dbx.dialect.api.SourceForeignKey.ReferentialAction;
import com.dbx.dialect.api.SourceIndex.Direction;
import com.dbx.dialect.api.SourceIndex.IndexType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * {@code target.supplementalStatements} (obligation 25): ADR-0026's supplemental SQL, executable where
 * PostgreSQL can hold the structure, commented out with one stable {@link SupplementalCommentReason} where not,
 * foreign keys last. The result is text for the DBA, not a {@link SqlPlan}, so it has no fingerprint, kind or
 * privileges to assert. Running the script on PostgreSQL 15 is not provable here; it is the L2 owner's
 * ({@code docs/spec/dialect.md} §Verification).
 */
class SupplementalStatementsContractTest {

    private static final TableCoordinate ORDERS = new TableCoordinate("shop", "orders");
    private static final TableCoordinate CUSTOMERS = new TableCoordinate("shop", "customers");
    private static final TargetTableCoordinate APP_ORDERS = target("app", "orders");
    private static final TargetTableCoordinate APP_CUSTOMERS = target("app", "customers");

    // --- Exact text and order (ADR-0026 §Supplemental SQL) --------------------------------------------

    @Test
    void theScriptIsPinnedForARepresentativeInput() {
        assertEquals(List.of(
                        "-- source: CREATE UNIQUE INDEX `uk_email` ON `shop`.`customers` (`email`)\n"
                                + "ALTER TABLE \"app\".\"customers\" ADD UNIQUE (\"email\")",
                        "-- source: CREATE INDEX `idx_created` ON `shop`.`orders` (`created_at` DESC, `status`)\n"
                                + "CREATE INDEX ON \"app\".\"orders\" (\"created\" DESC, \"status\")",
                        "-- source: CREATE UNIQUE INDEX `uk_orders_code` ON `shop`.`orders` (`code`)\n"
                                + "ALTER TABLE \"app\".\"orders\" ADD UNIQUE (\"code\")",
                        "COMMENT ON TABLE \"app\".\"orders\" IS E'客户''s orders'",
                        "COMMENT ON COLUMN \"app\".\"orders\".\"status\" IS E'state\\u000Aline'",
                        "ALTER TABLE \"app\".\"orders\" ALTER COLUMN \"status\" SET DEFAULT E'new'",
                        "-- requires manual handling (COLLATION): `shop`.`orders`.`code` COLLATE utf8mb4_bin",
                        "-- requires manual handling (ON_UPDATE_CURRENT_TIMESTAMP): `shop`.`orders`.`updated_at` "
                                + "ON UPDATE CURRENT_TIMESTAMP(3)",
                        "-- requires manual handling (PREFIX_KEY_PART): CREATE INDEX `idx_note_prefix` ON "
                                + "`shop`.`orders` (`note`(10))",
                        "-- requires manual handling (EXPRESSION_KEY_PART): CREATE INDEX `idx_expr` ON "
                                + "`shop`.`orders` ((lower(`code`)))",
                        "-- requires manual handling (FULLTEXT_OR_SPATIAL_INDEX): CREATE FULLTEXT INDEX `ft_note` ON "
                                + "`shop`.`orders` (`note`)",
                        "-- requires manual handling (PRUNED_COLUMN): `shop`.`orders`.`legacy` COMMENT E'old'",
                        "-- source: ALTER TABLE `shop`.`customers` ADD CONSTRAINT `fk_referrer` FOREIGN KEY "
                                + "(`referrer_id`) REFERENCES `shop`.`customers` (`id`) ON UPDATE NO ACTION "
                                + "ON DELETE SET NULL\n"
                                + "ALTER TABLE \"app\".\"customers\" ADD FOREIGN KEY (\"referrer_id\") REFERENCES "
                                + "\"app\".\"customers\" (\"id\") ON UPDATE NO ACTION ON DELETE SET NULL",
                        "-- source: ALTER TABLE `shop`.`orders` ADD CONSTRAINT `fk_orders_customer` FOREIGN KEY "
                                + "(`customer_id`) REFERENCES `shop`.`customers` (`id`) ON UPDATE CASCADE "
                                + "ON DELETE RESTRICT\n"
                                + "ALTER TABLE \"app\".\"orders\" ADD FOREIGN KEY (\"customer_id\") REFERENCES "
                                + "\"app\".\"customers\" (\"id\") ON UPDATE CASCADE ON DELETE RESTRICT",
                        "-- requires manual handling (REFERENCED_TABLE_OUT_OF_SCOPE): ALTER TABLE `shop`.`orders` "
                                + "ADD CONSTRAINT `fk_orders_warehouse` FOREIGN KEY (`warehouse_id`) REFERENCES "
                                + "`inv`.`warehouses` (`id`) ON UPDATE RESTRICT ON DELETE RESTRICT"),
                sql(PAIR.target().supplementalStatements(representative())),
                "ADR-0026 §Supplemental SQL: unnamed executable structures under approved names, comment-only "
                        + "source definitions with their reason, ordered by target table with foreign keys last");
    }

    @Test
    void theOutputIsIdenticalAcrossCalls() {
        assertEquals(PAIR.target().supplementalStatements(representative()),
                PAIR.target().supplementalStatements(representative()),
                "ADR-0026 §Timing: the script is frozen per run, so the same snapshot renders the same statements");
    }

    @Test
    void everyReasonIsProducedByAFixture() {
        Set<SupplementalCommentReason> produced = EnumSet.noneOf(SupplementalCommentReason.class);
        PAIR.target().supplementalStatements(representative()).forEach(s -> s.reason().ifPresent(produced::add));
        PAIR.target().supplementalStatements(List.of(index(ORDERS, APP_ORDERS,
                sourceIndex("idx_legacy", false, IndexType.BTREE, columnPart(1, ORDERS, "legacy", Direction.ASCENDING)),
                List.of(pruned(ORDERS, "legacy"))))).forEach(s -> s.reason().ifPresent(produced::add));
        assertEquals(EnumSet.allOf(SupplementalCommentReason.class), produced,
                "#123 obligation 25: every stable reason code is reachable from a real input");
    }

    @Test
    void aReasonIsPresentExactlyWhenTheStatementIsCommentOnly() {
        for (Statement statement : PAIR.target().supplementalStatements(representative())) {
            assertEquals(statement.disposition() == Statement.Disposition.COMMENT_ONLY, statement.reason().isPresent(),
                    "ADR-0026: a reason exactly when commented out: " + statement);
            if (statement.disposition() == Statement.Disposition.COMMENT_ONLY) {
                statement.sql().lines().forEach(line -> assertTrue(line.startsWith("-- "),
                        "ADR-0026: a comment-only statement executes nothing: " + statement.sql()));
                assertTrue(statement.sql().contains("requires manual handling (" + statement.reason().get() + "): "),
                        "ADR-0026: the comment says it requires manual handling and why: " + statement.sql());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Statement("-- x", Statement.Disposition.COMMENT_ONLY,
                Optional.empty()), "ADR-0026: a comment-only statement without its reason is not expressible");
        assertThrows(IllegalArgumentException.class, () -> new Statement("SELECT 1", Statement.Disposition.EXECUTABLE,
                Optional.of(SupplementalCommentReason.COLLATION)), "ADR-0026: an executable statement has no reason");
    }

    @Test
    void pruningDecidesTheReasonBeforeAnyOtherCause() {
        TableCoordinate t = ORDERS;
        List<DeferredStructure> structures = List.of(
                index(t, APP_ORDERS, sourceIndex("ft", false, IndexType.FULLTEXT,
                        new SourceIndex.KeyPart(1, col(t, "legacy"), OptionalLong.of(4), Direction.NOT_SORTED)),
                        List.of(pruned(t, "legacy"))),
                new DeferredStructure.Collation(t, APP_ORDERS, Optional.of(pruned(t, "legacy")), "utf8mb4_bin"),
                new DeferredStructure.OnUpdate(t, APP_ORDERS, pruned(t, "legacy"), "CURRENT_TIMESTAMP"),
                new DeferredStructure.ColumnDefault(t, APP_ORDERS, pruned(t, "legacy"), new SqlValue.Int64(5)),
                foreignKey(t, APP_ORDERS, "fk", "legacy", CUSTOMERS, "id", List.of(pruned(t, "legacy")),
                        new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "id", "id")))),
                foreignKey(t, APP_ORDERS, "fk2", "customer_id", CUSTOMERS, "id",
                        List.of(approved(t, "customer_id", "customer_id")),
                        new ReferencedTable.InScope(APP_CUSTOMERS, List.of(pruned(CUSTOMERS, "id")))));
        for (Statement statement : PAIR.target().supplementalStatements(structures)) {
            assertEquals(Optional.of(SupplementalCommentReason.PRUNED_COLUMN), statement.reason(),
                    "ADR-0026 §Follows mapping rules: a statement touching a pruned column is commented out: "
                            + statement.sql());
        }
    }

    @Test
    void foreignKeysComeLastAcrossTables() {
        TargetTableCoordinate first = target("a", "a");
        TargetTableCoordinate last = target("z", "z");
        List<DeferredStructure> structures = List.of(
                foreignKey(ORDERS, first, "fk_a", "customer_id", CUSTOMERS, "id",
                        List.of(approved(ORDERS, "customer_id", "customer_id")),
                        new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "id", "id")))),
                foreignKey(ORDERS, first, "fk_out", "warehouse_id", new TableCoordinate("inv", "w"), "id",
                        List.of(approved(ORDERS, "warehouse_id", "warehouse_id")), new ReferencedTable.OutOfScope()),
                new DeferredStructure.TableComment(CUSTOMERS, last, "late table"),
                index(ORDERS, first, sourceIndex("i", false, IndexType.BTREE, columnPart(1, ORDERS, "code", Direction.ASCENDING)),
                        List.of(approved(ORDERS, "code", "code"))),
                foreignKey(CUSTOMERS, last, "fk_z", "referrer_id", CUSTOMERS, "id",
                        List.of(approved(CUSTOMERS, "referrer_id", "referrer_id")),
                        new ReferencedTable.InScope(last, List.of(approved(CUSTOMERS, "id", "id")))),
                new DeferredStructure.TableComment(ORDERS, first, "early table"));

        List<String> sql = sql(PAIR.target().supplementalStatements(structures));

        assertEquals(6, sql.size(), "ADR-0026 §Supplemental SQL: one statement per deferred structure");
        assertTrue(sql.get(0).endsWith("CREATE INDEX ON \"a\".\"a\" (\"code\")")
                        && sql.get(1).startsWith("COMMENT ON TABLE \"a\".\"a\"")
                        && sql.get(2).startsWith("COMMENT ON TABLE \"z\".\"z\""),
                "ADR-0026: non-FK statements by target table, then input order: " + sql);
        for (int i = 3; i < 6; i++) {
            assertTrue(sql.get(i).contains("FOREIGN KEY"), "ADR-0026: all foreign keys come last: " + sql);
        }
        assertTrue(sql.get(3).contains("`fk_a`") && sql.get(4).contains("`fk_out`") && sql.get(5).contains("`fk_z`"),
                "ADR-0026: foreign keys by target table, then input order, commented-out ones included: " + sql);
    }

    // --- Indexes (ADR-0026; TP §7.1) -----------------------------------------------------------------

    @Test
    void keyPartOrderAndDescAreKeptInExecutableIndexes() {
        SourceIndex.KeyPart b = columnPart(1, ORDERS, "b", Direction.DESCENDING);
        SourceIndex.KeyPart a = columnPart(2, ORDERS, "a", Direction.ASCENDING);
        List<MappedColumn> mapped = List.of(approved(ORDERS, "b", "tb"), approved(ORDERS, "a", "ta"));

        List<String> sql = sql(PAIR.target().supplementalStatements(List.of(
                index(ORDERS, APP_ORDERS, sourceIndex("ord", false, IndexType.BTREE, b, a), mapped),
                index(ORDERS, APP_ORDERS, sourceIndex("uniq_desc", true, IndexType.BTREE, b, a), mapped),
                index(ORDERS, APP_ORDERS, sourceIndex("uniq_asc", true, IndexType.BTREE,
                        columnPart(1, ORDERS, "b", Direction.ASCENDING), a), mapped))));

        assertEquals(List.of(
                        "-- source: CREATE INDEX `ord` ON `shop`.`orders` (`b` DESC, `a`)\n"
                                + "CREATE INDEX ON \"app\".\"orders\" (\"tb\" DESC, \"ta\")",
                        "-- source: CREATE UNIQUE INDEX `uniq_desc` ON `shop`.`orders` (`b` DESC, `a`)\n"
                                + "CREATE UNIQUE INDEX ON \"app\".\"orders\" (\"tb\" DESC, \"ta\")",
                        "-- source: CREATE UNIQUE INDEX `uniq_asc` ON `shop`.`orders` (`b`, `a`)\n"
                                + "ALTER TABLE \"app\".\"orders\" ADD UNIQUE (\"tb\", \"ta\")"),
                sql, "ADR-0026: key-part order and DESC survive; a unique constraint cannot hold DESC, so a unique "
                        + "index does");
    }

    @Test
    void noSourceIndexOrForeignKeyNameAppearsOutsideAComment() {
        for (String hostile : HOSTILE) {
            String name = "src_" + hostile;
            List<DeferredStructure> structures = List.of(
                    index(ORDERS, APP_ORDERS, sourceIndex(name, true, IndexType.BTREE,
                            columnPart(1, ORDERS, "code", Direction.ASCENDING)), List.of(approved(ORDERS, "code", "c"))),
                    index(ORDERS, APP_ORDERS, sourceIndex(name + "2", false, IndexType.BTREE,
                            columnPart(1, ORDERS, "code", Direction.DESCENDING)), List.of(approved(ORDERS, "code", "c"))),
                    foreignKey(ORDERS, APP_ORDERS, name, "customer_id", CUSTOMERS, "id",
                            List.of(approved(ORDERS, "customer_id", "customer_id")),
                            new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "id", "id")))));
            for (Statement statement : PAIR.target().supplementalStatements(structures)) {
                String executable = executablePart(statement.sql());
                assertFalse(executable.contains(name),
                        "ADR-0026; #123 story 26: index names are schema-wide in PostgreSQL, so a source name is "
                                + "only ever in a comment: " + statement.sql());
                assertTrue(statement.sql().contains(("`" + name.replace("`", "``")).lines().findFirst().orElseThrow()),
                        "ADR-0026: the source name is kept in the preceding comment line: " + statement.sql());
            }
        }
    }

    // --- Distinct inputs, distinct text ---------------------------------------------------------------

    @Test
    void everyInputFieldChangesTheText() {
        List<DeferredStructure> base = representative();
        Map<String, Function<List<DeferredStructure>, List<DeferredStructure>>> variants = new LinkedHashMap<>();
        variants.put("target schema", s -> set(s, 13, new DeferredStructure.TableComment(CUSTOMERS,
                target("app2", "customers"), "x")));
        variants.put("index direction", s -> set(s, 1, index(ORDERS, APP_ORDERS, sourceIndex("idx_created", false,
                IndexType.BTREE, columnPart(1, ORDERS, "created_at", Direction.ASCENDING),
                columnPart(2, ORDERS, "status", Direction.ASCENDING)),
                List.of(approved(ORDERS, "created_at", "created"), approved(ORDERS, "status", "status")))));
        variants.put("index key order", s -> set(s, 1, index(ORDERS, APP_ORDERS, sourceIndex("idx_created", false,
                IndexType.BTREE, columnPart(1, ORDERS, "status", Direction.ASCENDING),
                columnPart(2, ORDERS, "created_at", Direction.DESCENDING)),
                List.of(approved(ORDERS, "status", "status"), approved(ORDERS, "created_at", "created")))));
        variants.put("uniqueness", s -> set(s, 2, index(ORDERS, APP_ORDERS, sourceIndex("uk_orders_code", false,
                IndexType.BTREE, columnPart(1, ORDERS, "code", Direction.ASCENDING)), List.of(approved(ORDERS, "code", "code")))));
        variants.put("approved column name", s -> set(s, 2, index(ORDERS, APP_ORDERS, sourceIndex("uk_orders_code",
                true, IndexType.BTREE, columnPart(1, ORDERS, "code", Direction.ASCENDING)),
                List.of(approved(ORDERS, "code", "code2")))));
        variants.put("comment text", s -> set(s, 3, new DeferredStructure.TableComment(ORDERS, APP_ORDERS, "other")));
        variants.put("default value", s -> set(s, 5, new DeferredStructure.ColumnDefault(ORDERS, APP_ORDERS,
                approved(ORDERS, "status", "status"), new SqlValue.Text("old"))));
        variants.put("collation", s -> set(s, 6, new DeferredStructure.Collation(ORDERS, APP_ORDERS,
                Optional.of(approved(ORDERS, "code", "code")), "utf8mb4_0900_ai_ci")));
        variants.put("table collation", s -> set(s, 6, new DeferredStructure.Collation(ORDERS, APP_ORDERS,
                Optional.empty(), "utf8mb4_bin")));
        variants.put("on update definition", s -> set(s, 7, new DeferredStructure.OnUpdate(ORDERS, APP_ORDERS,
                approved(ORDERS, "updated_at", "updated_at"), "CURRENT_TIMESTAMP")));
        variants.put("prefix length", s -> set(s, 8, index(ORDERS, APP_ORDERS, sourceIndex("idx_note_prefix", false,
                IndexType.BTREE, new SourceIndex.KeyPart(1, col(ORDERS, "note"), OptionalLong.of(11), Direction.ASCENDING)),
                List.of(approved(ORDERS, "note", "note")))));
        variants.put("visibility", s -> set(s, 8, index(ORDERS, APP_ORDERS, new SourceIndex("idx_note_prefix", false,
                false, IndexType.BTREE, List.of(new SourceIndex.KeyPart(1, col(ORDERS, "note"), OptionalLong.of(10),
                Direction.ASCENDING))), List.of(approved(ORDERS, "note", "note")))));
        variants.put("delete rule", s -> set(s, 0, foreignKey(ORDERS, APP_ORDERS, "fk_orders_customer", "customer_id",
                CUSTOMERS, "id", List.of(approved(ORDERS, "customer_id", "customer_id")),
                new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "id", "id"))),
                ReferentialAction.CASCADE, ReferentialAction.CASCADE)));
        variants.put("referenced target", s -> set(s, 0, foreignKey(ORDERS, APP_ORDERS, "fk_orders_customer",
                "customer_id", CUSTOMERS, "id", List.of(approved(ORDERS, "customer_id", "customer_id")),
                new ReferencedTable.InScope(target("crm", "customers"), List.of(approved(CUSTOMERS, "id", "id"))))));
        variants.put("input order", s -> {
            List<DeferredStructure> swapped = new ArrayList<>(s);
            swapped.set(1, s.get(2));
            swapped.set(2, s.get(1));
            return swapped;
        });

        List<Statement> expected = PAIR.target().supplementalStatements(base);
        for (Map.Entry<String, Function<List<DeferredStructure>, List<DeferredStructure>>> variant : variants.entrySet()) {
            assertNotEquals(expected, PAIR.target().supplementalStatements(variant.getValue().apply(base)),
                    "ADR-0026: changing only the " + variant.getKey() + " must change the script");
        }
    }

    // --- Hostile identifiers and values (ADR-0008 §Plans; TP §7.1) -----------------------------------

    private static final List<String> HOSTILE = List.of(
            "it's", "back\\slash", "$$dollar$$", "`tick`", "new\nline", "cr\rtab\t", "crlf\r\n-- x", "a\"b", "?",
            "\u0001soh", "del\u007F", "客户订单", "emoji😀", "x\"; DROP TABLE t; --", "'); DROP TABLE t; --", "*/ /*",
            "e\\'", "a".repeat(60));

    @Test
    void hostileIdentifiersAndValuesNeverChangeStatementStructure() {
        List<String> benign = skeleton(PAIR.target().supplementalStatements(everyPosition("n")));
        for (String hostile : HOSTILE) {
            List<Statement> statements = PAIR.target().supplementalStatements(everyPosition(hostile));
            assertEquals(benign, skeleton(statements),
                    "TP §7.1; ADR-0008 §Plans: a hostile name or value stays inside its comment, quotes or literal: "
                            + hostile.replace("\n", "\\n").replace("\r", "\\r"));
        }
    }

    @Test
    void aTargetNameOf63BytesIsKeptAndOf64BytesIsRefused() {
        String bytes63 = "订".repeat(21);
        String bytes64 = bytes63 + "a";
        assertEquals(63, bytes63.getBytes(StandardCharsets.UTF_8).length,
                "TP §7.1: the fixture name is exactly PostgreSQL's 63-byte limit");

        String kept = sql(PAIR.target().supplementalStatements(List.of(new DeferredStructure.ColumnCommentText(ORDERS,
                target(bytes63, bytes63), approved(ORDERS, "c", bytes63), "x")))).get(0);
        assertEquals("COMMENT ON COLUMN \"" + bytes63 + "\".\"" + bytes63 + "\".\"" + bytes63 + "\" IS E'x'", kept,
                "TP §7.1: a 63-byte name is PostgreSQL's limit and is kept exactly");
        for (DeferredStructure structure : List.of(
                new DeferredStructure.TableComment(ORDERS, target(bytes64, "t"), "x"),
                new DeferredStructure.TableComment(ORDERS, target("s", bytes64), "x"),
                new DeferredStructure.ColumnCommentText(ORDERS, APP_ORDERS, approved(ORDERS, "c", bytes64), "x"))) {
            assertThrows(IllegalArgumentException.class, () -> PAIR.target().supplementalStatements(List.of(structure)),
                    "TP §7.1: PostgreSQL truncates a 64-byte name, so the statement would touch a different object");
        }
    }

    @Test
    void aValuePostgresCannotHoldIsRefusedRatherThanAltered() {
        for (String unstorable : List.of("nul\u0000inside", "lone\uD800surrogate")) {
            assertThrows(IllegalArgumentException.class, () -> PAIR.target().supplementalStatements(List.of(
                            new DeferredStructure.TableComment(ORDERS, APP_ORDERS, unstorable))),
                    "ADR-0008 §Plans: PostgreSQL text holds no U+0000 or lone surrogate, so the renderer refuses it");
        }
    }

    // --- Inconsistent structures are refused ----------------------------------------------------------

    @Test
    void anInconsistentStructureCannotBeConstructed() {
        SourceIndex.KeyPart code = columnPart(1, ORDERS, "code", Direction.ASCENDING);
        Map<String, Supplier<Object>> inconsistent = new LinkedHashMap<>();
        inconsistent.put("the primary key", () -> index(ORDERS, APP_ORDERS,
                sourceIndex(SourceIndex.PRIMARY, true, IndexType.BTREE, code), List.of(approved(ORDERS, "code", "code"))));
        inconsistent.put("an index column without its mapping", () -> index(ORDERS, APP_ORDERS,
                sourceIndex("i", false, IndexType.BTREE, code), List.of()));
        inconsistent.put("an index mapping another column", () -> index(ORDERS, APP_ORDERS,
                sourceIndex("i", false, IndexType.BTREE, code), List.of(approved(ORDERS, "note", "note"))));
        inconsistent.put("a column of another table", () -> new DeferredStructure.OnUpdate(ORDERS, APP_ORDERS,
                approved(CUSTOMERS, "code", "code"), "CURRENT_TIMESTAMP"));
        inconsistent.put("a referenced column mapping of another column", () -> foreignKey(ORDERS, APP_ORDERS, "fk",
                "customer_id", CUSTOMERS, "id", List.of(approved(ORDERS, "customer_id", "customer_id")),
                new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "email", "email")))));
        inconsistent.put("a NULL default", () -> new DeferredStructure.ColumnDefault(ORDERS, APP_ORDERS,
                approved(ORDERS, "code", "code"), new SqlValue.Null(SqlValue.Type.TEXT)));
        inconsistent.put("an empty comment", () -> new DeferredStructure.TableComment(ORDERS, APP_ORDERS, ""));
        for (Map.Entry<String, Supplier<Object>> entry : inconsistent.entrySet()) {
            assertThrows(IllegalArgumentException.class, entry.getValue()::get,
                    "ADR-0026 §Timing: a deferred structure contradicting its own metadata is refused: "
                            + entry.getKey());
        }
    }

    // --- Fixtures -------------------------------------------------------------------------------------

    /** Two tables, every executable kind, every comment-only reason but PRUNED on an index, inputs out of order. */
    private static List<DeferredStructure> representative() {
        return List.of(
                foreignKey(ORDERS, APP_ORDERS, "fk_orders_customer", "customer_id", CUSTOMERS, "id",
                        List.of(approved(ORDERS, "customer_id", "customer_id")),
                        new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "id", "id"))),
                        ReferentialAction.CASCADE, ReferentialAction.RESTRICT),
                index(ORDERS, APP_ORDERS, sourceIndex("idx_created", false, IndexType.BTREE,
                                columnPart(1, ORDERS, "created_at", Direction.DESCENDING),
                                columnPart(2, ORDERS, "status", Direction.ASCENDING)),
                        List.of(approved(ORDERS, "created_at", "created"), approved(ORDERS, "status", "status"))),
                index(ORDERS, APP_ORDERS, sourceIndex("uk_orders_code", true, IndexType.BTREE,
                        columnPart(1, ORDERS, "code", Direction.ASCENDING)), List.of(approved(ORDERS, "code", "code"))),
                new DeferredStructure.TableComment(ORDERS, APP_ORDERS, "客户's orders"),
                new DeferredStructure.ColumnCommentText(ORDERS, APP_ORDERS, approved(ORDERS, "status", "status"),
                        "state\nline"),
                new DeferredStructure.ColumnDefault(ORDERS, APP_ORDERS, approved(ORDERS, "status", "status"),
                        new SqlValue.Text("new")),
                new DeferredStructure.Collation(ORDERS, APP_ORDERS, Optional.of(approved(ORDERS, "code", "code")),
                        "utf8mb4_bin"),
                new DeferredStructure.OnUpdate(ORDERS, APP_ORDERS, approved(ORDERS, "updated_at", "updated_at"),
                        "CURRENT_TIMESTAMP(3)"),
                index(ORDERS, APP_ORDERS, sourceIndex("idx_note_prefix", false, IndexType.BTREE,
                                new SourceIndex.KeyPart(1, col(ORDERS, "note"), OptionalLong.of(10), Direction.ASCENDING)),
                        List.of(approved(ORDERS, "note", "note"))),
                index(ORDERS, APP_ORDERS, sourceIndex("idx_expr", false, IndexType.BTREE, new SourceIndex.KeyPart(1,
                        new SourceIndex.Subject.Expression("lower(`code`)"), OptionalLong.empty(), Direction.ASCENDING)),
                        List.of()),
                index(ORDERS, APP_ORDERS, sourceIndex("ft_note", false, IndexType.FULLTEXT,
                        columnPart(1, ORDERS, "note", Direction.NOT_SORTED)), List.of(approved(ORDERS, "note", "note"))),
                new DeferredStructure.ColumnCommentText(ORDERS, APP_ORDERS, pruned(ORDERS, "legacy"), "old"),
                foreignKey(ORDERS, APP_ORDERS, "fk_orders_warehouse", "warehouse_id",
                        new TableCoordinate("inv", "warehouses"), "id",
                        List.of(approved(ORDERS, "warehouse_id", "warehouse_id")), new ReferencedTable.OutOfScope()),
                index(CUSTOMERS, APP_CUSTOMERS, sourceIndex("uk_email", true, IndexType.BTREE,
                        columnPart(1, CUSTOMERS, "email", Direction.ASCENDING)),
                        List.of(approved(CUSTOMERS, "email", "email"))),
                foreignKey(CUSTOMERS, APP_CUSTOMERS, "fk_referrer", "referrer_id", CUSTOMERS, "id",
                        List.of(approved(CUSTOMERS, "referrer_id", "referrer_id")),
                        new ReferencedTable.InScope(APP_CUSTOMERS, List.of(approved(CUSTOMERS, "id", "id"))),
                        ReferentialAction.NO_ACTION, ReferentialAction.SET_NULL));
    }

    /** {@code h} in every source name, target name and value position, one structure of each rendering. */
    private static List<DeferredStructure> everyPosition(String h) {
        TableCoordinate source = new TableCoordinate("db" + h, "t" + h);
        TableCoordinate referenced = new TableCoordinate("db" + h, "r" + h);
        TargetTableCoordinate tgt = target("s" + h, "t" + h);
        TargetTableCoordinate refTgt = target("s" + h, "r" + h);
        MappedColumn c = approved(source, "c" + h, "tc" + h);
        return List.of(
                index(source, tgt, sourceIndex("i" + h, false, IndexType.BTREE, columnPart(1, source, "c" + h, Direction.DESCENDING)),
                        List.of(c)),
                index(source, tgt, sourceIndex("x" + h, false, IndexType.BTREE, new SourceIndex.KeyPart(1,
                        new SourceIndex.Subject.Expression("f(" + h + ")"), OptionalLong.empty(), Direction.ASCENDING)),
                        List.of()),
                new DeferredStructure.TableComment(source, tgt, "comment " + h),
                new DeferredStructure.ColumnCommentText(source, tgt, c, "comment " + h),
                new DeferredStructure.ColumnCommentText(source, tgt, pruned(source, "p" + h), "comment " + h),
                new DeferredStructure.ColumnDefault(source, tgt, c, new SqlValue.Text(h)),
                new DeferredStructure.Collation(source, tgt, Optional.of(c), "coll" + h),
                new DeferredStructure.OnUpdate(source, tgt, c, "CURRENT_TIMESTAMP " + h),
                foreignKey(source, tgt, "fk" + h, "c" + h, referenced, "id" + h, List.of(c),
                        new ReferencedTable.InScope(refTgt, List.of(approved(referenced, "id" + h, "tid" + h)))),
                foreignKey(source, tgt, "fo" + h, "c" + h, referenced, "id" + h, List.of(c),
                        new ReferencedTable.OutOfScope()));
    }

    private static TargetTableCoordinate target(String schema, String name) {
        return new TargetTableCoordinate(new TargetIdentifier(schema), new TargetIdentifier(name));
    }

    private static SourceIndex.Subject.Column col(TableCoordinate table, String column) {
        return new SourceIndex.Subject.Column(new ColumnCoordinate(table.database(), table.table(), column));
    }

    private static SourceIndex.KeyPart columnPart(int sequence, TableCoordinate table, String column, Direction direction) {
        return new SourceIndex.KeyPart(sequence, col(table, column), OptionalLong.empty(), direction);
    }

    private static SourceIndex sourceIndex(String name, boolean unique, SourceIndex.IndexType type, SourceIndex.KeyPart... parts) {
        return new SourceIndex(name, unique, true, type, List.of(parts));
    }

    private static MappedColumn approved(TableCoordinate table, String source, String target) {
        return new MappedColumn.Approved(new ColumnCoordinate(table.database(), table.table(), source),
                new TargetIdentifier(target));
    }

    private static MappedColumn pruned(TableCoordinate table, String source) {
        return new MappedColumn.Pruned(new ColumnCoordinate(table.database(), table.table(), source));
    }

    private static DeferredStructure.Index index(TableCoordinate source, TargetTableCoordinate target, SourceIndex index,
            List<MappedColumn> columns) {
        return new DeferredStructure.Index(source, target, index, columns);
    }

    private static DeferredStructure.ForeignKey foreignKey(TableCoordinate source, TargetTableCoordinate target,
            String name, String column, TableCoordinate referenced, String referencedColumn, List<MappedColumn> columns,
            ReferencedTable scope) {
        return foreignKey(source, target, name, column, referenced, referencedColumn, columns, scope,
                ReferentialAction.RESTRICT, ReferentialAction.RESTRICT);
    }

    private static DeferredStructure.ForeignKey foreignKey(TableCoordinate source, TargetTableCoordinate target,
            String name, String column, TableCoordinate referenced, String referencedColumn, List<MappedColumn> columns,
            ReferencedTable scope, ReferentialAction onUpdate, ReferentialAction onDelete) {
        SourceForeignKey foreignKey = new SourceForeignKey(name, referenced, List.of(new SourceForeignKey.Part(
                new ColumnCoordinate(source.database(), source.table(), column),
                new ColumnCoordinate(referenced.database(), referenced.table(), referencedColumn))), onUpdate, onDelete);
        return new DeferredStructure.ForeignKey(source, target, foreignKey, columns, scope);
    }

    private static List<DeferredStructure> set(List<DeferredStructure> structures, int index, DeferredStructure value) {
        List<DeferredStructure> copy = new ArrayList<>(structures);
        copy.set(index, value);
        return copy;
    }

    private static List<String> sql(List<Statement> statements) {
        return statements.stream().map(Statement::sql).toList();
    }

    /** What follows the leading {@code -- } comment lines; empty for a comment-only statement. */
    private static String executablePart(String sql) {
        String rest = sql;
        while (rest.startsWith("-- ")) {
            int end = rest.indexOf('\n');
            if (end < 0) {
                return "";
            }
            rest = rest.substring(end + 1);
        }
        return rest;
    }

    /**
     * Each statement with its leading comment lines collapsed to one {@code -- _} per line-run, every double-quoted
     * identifier replaced by {@code "_"} and every escape-string literal by {@code '_'}, plus its disposition and
     * reason. A comment line holding a CR (which ends a PostgreSQL comment) fails the skeleton outright.
     */
    private static List<String> skeleton(List<Statement> statements) {
        return statements.stream().map(statement -> {
            String sql = statement.sql();
            String executable = executablePart(sql);
            String comments = sql.substring(0, sql.length() - executable.length());
            assertFalse(comments.contains("\r"),
                    "ADR-0026 §Supplemental SQL: a CR would end a PostgreSQL comment early: " + sql);
            return statement.disposition() + " " + statement.reason() + " "
                    + (comments.isEmpty() ? "" : "-- _\n") + literals(executable);
        }).toList();
    }

    private static String literals(String sql) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '"') {
                i++;
                while (!(sql.charAt(i) == '"' && (i + 1 == sql.length() || sql.charAt(i + 1) != '"'))) {
                    i += sql.charAt(i) == '"' ? 2 : 1;
                }
                out.append("\"_\"");
            } else if (c == '\'') {
                i++;
                while (!(sql.charAt(i) == '\'' && (i + 1 == sql.length() || sql.charAt(i + 1) != '\''))) {
                    i += sql.charAt(i) == '\'' || sql.charAt(i) == '\\' ? 2 : 1;
                }
                out.append("'_'");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
