package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.dialect.api.MappingRule.ColumnPrune;
import com.dbx.dialect.api.MappingRule.ColumnRename;
import com.dbx.dialect.api.MappingRule.TableRename;
import com.dbx.dialect.api.MappingRule.TargetTypeOverride;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The source dialect's L1 contract, driven through {@code dialect.api} with values
 * ({@code docs/spec/dialect.md} §Verification, obligations 15–23). This ticket holds the
 * {@code queryProjection*} behaviour (obligation 19a; TP §7.1; #92). That {@code connector.deriveBox}
 * places this exact text is proven by {@code connector}'s own contract (connector obligation 6a).
 */
class SourceDialectContractTest {

    private static final MappingRule.RuleOrigin AUTO = MappingRule.RuleOrigin.AUTO;
    private static final MappingRule.RuleOrigin USER = MappingRule.RuleOrigin.USER;

    private static ColumnCoordinate column(String name) {
        return new ColumnCoordinate("shop", "orders", name);
    }

    private static ApprovedColumn approved(String source, String target) {
        return new ApprovedColumn(column(source), new TargetIdentifier(target));
    }

    private static String projection(List<ApprovedColumn> columns, List<MappingRule> rules) {
        return PAIR.source().queryProjection(columns, rules).text();
    }

    // --- Prune and rename (obligation 19a; ADR-0011 §Contract assembly) --------------------------------

    @Test
    void queryProjectionOmitsAPrunedColumn() {
        assertEquals("SELECT `id` AS `id`, `total` AS `total` FROM `shop`.`orders`",
                projection(List.of(approved("id", "id"), approved("total", "total")),
                        List.of(new ColumnPrune(column("secret"), USER))),
                "TP §7.1: a pruned column is absent from the Source query, not only from the DDL");
    }

    @Test
    void queryProjectionAliasesARenamedColumnToItsApprovedTarget() {
        assertEquals("SELECT `total` AS `id`, `id` AS `order_id` FROM `shop`.`orders`",
                projection(List.of(approved("total", "id"), approved("id", "order_id")),
                        List.of(new ColumnRename(column("id"), new TargetIdentifier("order_id"), USER),
                                new ColumnRename(column("total"), new TargetIdentifier("id"), AUTO))),
                "ADR-0011 §Contract assembly: the Connect field name already equals the approved target column, "
                        + "in approvedColumns order");
    }

    @Test
    void queryProjectionTakesTheUserRenameOverAnOverriddenAutoRename() {
        assertEquals("SELECT `amt` AS `amount` FROM `shop`.`orders`",
                projection(List.of(approved("amt", "amount")),
                        List.of(new ColumnRename(column("amt"), new TargetIdentifier("amt"), AUTO),
                                new ColumnRename(column("amt"), new TargetIdentifier("amount"), USER))),
                "contract obligation 8: USER overrides AUTO, and approvedColumns carries the USER result");
    }

    @Test
    void queryProjectionIsNotChangedByTableRenameOrTargetTypeOverride() {
        List<ApprovedColumn> columns = List.of(approved("id", "id"));
        String plain = projection(columns, List.of());
        assertEquals("SELECT `id` AS `id` FROM `shop`.`orders`", plain);
        assertEquals(plain, projection(columns, List.of(
                        new TableRename(new TableCoordinate("shop", "orders"), new TargetIdentifier("orders_v2"), USER),
                        new TargetTypeOverride(column("id"), new TargetType(TargetTypeName.TEXT, List.of()), USER))),
                "obligation 19a: the projection reads the source table and applies no value transform");
    }

    // --- Quoting (obligation 5; TP §7.1) --------------------------------------------------------------

    @Test
    void queryProjectionQuotesHostileAndChineseNamesLiterally() {
        assertEquals("SELECT `订单编号` AS `订单编号`, `金额` AS `amount` FROM `商城`.`订单明细`",
                PAIR.source().queryProjection(List.of(
                        new ApprovedColumn(new ColumnCoordinate("商城", "订单明细", "订单编号"), new TargetIdentifier("订单编号")),
                        new ApprovedColumn(new ColumnCoordinate("商城", "订单明细", "金额"), new TargetIdentifier("amount"))),
                        List.of()).text(),
                "TP §7.1: Chinese names are kept character for character");
        assertEquals("SELECT `a``b` AS `c````d`, `select` AS `x\"y;--/*\nz` FROM `db``; DROP TABLE t; --`.`order``s`",
                PAIR.source().queryProjection(List.of(
                        new ApprovedColumn(new ColumnCoordinate("db`; DROP TABLE t; --", "order`s", "a`b"),
                                new TargetIdentifier("c``d")),
                        new ApprovedColumn(new ColumnCoordinate("db`; DROP TABLE t; --", "order`s", "select"),
                                new TargetIdentifier("x\"y;--/*\nz"))),
                        List.of()).text(),
                "TP §7.1: backtick-quoted with embedded backticks doubled, source and alias alike; a double quote, "
                        + ";, --, /* and newline stay literal");
    }

    @Test
    void queryProjectionIsByteIdenticalAcrossCalls() {
        List<ApprovedColumn> columns = List.of(approved("订单", "order"), approved("İd", "ı"));
        List<MappingRule> rules = List.of(new ColumnRename(column("订单"), new TargetIdentifier("order"), USER));
        byte[] first = projection(columns, rules).getBytes(StandardCharsets.UTF_8);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertArrayEquals(first, projection(columns, rules).getBytes(StandardCharsets.UTF_8),
                    "connector obligation 6a: deriveBox fingerprints this text, so it cannot vary between calls or "
                            + "with the machine's locale");
        } finally {
            Locale.setDefault(previous);
        }
        assertArrayEquals("SELECT `订单` AS `order`, `İd` AS `ı` FROM `shop`.`orders`".getBytes(StandardCharsets.UTF_8),
                first, "connector obligation 6a: the text is pinned, not merely self-consistent");
    }

    // --- The projection is the whole query's meaning (obligation 19a) ---------------------------------

    @Test
    void queryProjectionCarriesNoFilterOrderLimitOrTerminator() {
        List<String> hostile = List.of("WHERE 1=0", "ORDER BY x", "LIMIT 1", "x;", "`", "a -- b", "/* c */");
        List<ApprovedColumn> columns = new ArrayList<>();
        for (String name : hostile) {
            columns.add(new ApprovedColumn(new ColumnCoordinate("LIMIT 0", "WHERE", name), new TargetIdentifier(name)));
        }
        String text = PAIR.source().queryProjection(columns, List.of()).text();
        String outside = skeleton(text);

        assertEquals("SELECT " + String.join(", ", Collections.nCopies(hostile.size(), "<id> AS <id>"))
                        + " FROM <id>.<id>", outside,
                "obligation 19a: hostile names never change the projection's structure: " + text);
        Matcher words = Pattern.compile("[A-Za-z]+|;").matcher(outside);
        while (words.find()) {
            String token = words.group().toUpperCase(Locale.ROOT);
            assertTrue(List.of("SELECT", "AS", "FROM", "ID").contains(token),
                    "obligation 19a: no WHERE, ORDER BY, LIMIT, ; or value transform outside a quoted identifier; "
                            + "found " + token + " in: " + text);
        }
    }

    // --- Rules must agree with the approved columns (contract obligation 8) ---------------------------

    @Test
    void queryProjectionRejectsAPruneOfAProjectedColumn() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> projection(List.of(approved("id", "id"), approved("secret", "secret")),
                        List.of(new ColumnPrune(column("secret"), AUTO))));
        assertTrue(failure.getMessage().contains("ColumnPrune") && failure.getMessage().contains("secret"),
                "contract obligation 8: a prune of a projected column names the disagreement: " + failure.getMessage());
    }

    @Test
    void queryProjectionRejectsARenameToAnotherTarget() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> projection(List.of(approved("id", "order_id")),
                        List.of(new ColumnRename(column("id"), new TargetIdentifier("orderid"), USER))));
        assertTrue(failure.getMessage().contains("ColumnRename") && failure.getMessage().contains("orderid")
                        && failure.getMessage().contains("order_id"),
                "contract obligation 8: a rename disagreeing with the approved target names both: " + failure.getMessage());
    }

    @Test
    void queryProjectionRejectsColumnsSpanningTwoTables() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PAIR.source().queryProjection(List.of(approved("id", "id"),
                        new ApprovedColumn(new ColumnCoordinate("shop", "customers", "name"), new TargetIdentifier("name"))),
                        List.of()));
        assertTrue(failure.getMessage().contains("orders") && failure.getMessage().contains("customers"),
                "obligation 19a: a projection reads one table; the message names both: " + failure.getMessage());
    }

    @Test
    void queryProjectionRejectsAnEmptyColumnList() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> projection(List.of(), List.of()));
        assertTrue(failure.getMessage().contains("at least one approved column"),
                "obligation 19a: an empty projection is not a query: " + failure.getMessage());
    }

    /** The text with every backtick-quoted identifier replaced by {@code <id>}; an independent reader. */
    private static String skeleton(String sql) {
        StringBuilder outside = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '`') {
                i = endOfQuoted(sql, i);
                outside.append("<id>");
            } else {
                outside.append(sql.charAt(i));
            }
        }
        return outside.toString();
    }

    private static int endOfQuoted(String sql, int open) {
        for (int i = open + 1; i < sql.length(); i++) {
            if (sql.charAt(i) == '`') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        throw new AssertionError("unterminated backtick-quoted identifier in: " + sql);
    }
}
