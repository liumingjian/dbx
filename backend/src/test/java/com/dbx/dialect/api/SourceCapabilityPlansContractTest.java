package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code source.capabilityPlans} and MySQL identifier quoting, driven through {@code dialect.api} with
 * values ({@code docs/spec/dialect.md} obligations 5 and 20; ADR-0006 §Capability checks; TP §6.5, §7.1).
 * What L1 cannot prove — that a real partial column revoke fails the probe — belongs to L2/L3.
 */
class SourceCapabilityPlansContractTest {

    private static final Set<RequiredPrivilege> READ_ONLY =
            EnumSet.of(RequiredPrivilege.SOURCE_READ_METADATA, RequiredPrivilege.SOURCE_SELECT);

    private static final String SETTINGS_SQL = "SELECT @@session.time_zone AS `time_zone`, "
            + "@@session.character_set_client AS `character_set_client`, "
            + "@@session.character_set_connection AS `character_set_connection`, "
            + "@@session.character_set_database AS `character_set_database`, "
            + "@@session.character_set_filesystem AS `character_set_filesystem`, "
            + "@@session.character_set_results AS `character_set_results`, "
            + "@@session.character_set_server AS `character_set_server`, "
            + "@@global.character_set_system AS `character_set_system`, "
            + "@@session.collation_connection AS `collation_connection`, "
            + "@@session.sql_mode AS `sql_mode`, "
            + "@@session.information_schema_stats_expiry AS `information_schema_stats_expiry`";

    /** Hostile source names: quoting, comment and statement delimiters, placeholders, forbidden words, limits. */
    private static final List<String> HOSTILE = List.of(
            "order`s", "``", "`", "a\"b", "'single'", "back\\slash\\", "$$body$$", "x; DROP TABLE t; --",
            "a -- b", "a /* b */", "# hash", "new\nline", "cr\rlf", "?", "t FOR UPDATE", "LOCK TABLES t",
            "FLUSH", "SET GLOBAL x", "\u0001adjacent\uFFFF", "订单明细", "emoji😀", " padded ",
            "a".repeat(64), "订".repeat(64), "é".repeat(31) + "a", "é".repeat(32));

    private static List<SqlPlan> plans(String database, String... tables) {
        List<TableCoordinate> coordinates = new ArrayList<>();
        for (String table : tables) {
            coordinates.add(new TableCoordinate(database, table));
        }
        return PAIR.source().capabilityPlans(new MetadataScope(new SchemaCoordinate(database), coordinates));
    }

    private static String sql(SqlPlan plan) {
        assertEquals(1, plan.statements().size(), "ADR-0006 §Capability checks: one statement per capability plan");
        return plan.statements().get(0).sql();
    }

    // --- Plan shape (ADR-0008 §Plans; ADR-0006 §Capability checks) -----------------------------------

    @Test
    void theSettingsPlanComesFirstThenOneProbePerTableInScopeOrder() {
        List<SqlPlan> plans = plans("shop", "orders", "customers");

        assertEquals(3, plans.size(), "ADR-0006: one settings read plus one probe per scoped table");
        assertEquals(SETTINGS_SQL, sql(plans.get(0)), "TP §6.5: the settings read comes first");
        assertEquals("SELECT COUNT(*) AS `probed_rows` FROM (SELECT * FROM `shop`.`orders` LIMIT 0) AS `select_probe`",
                sql(plans.get(1)), "ADR-0006: tables are probed in scope order");
        assertEquals("SELECT COUNT(*) AS `probed_rows` FROM (SELECT * FROM `shop`.`customers` LIMIT 0) AS `select_probe`",
                sql(plans.get(2)),
                "ADR-0006: tables are probed in scope order");
        assertEquals(List.of(plans.get(0)), plans("shop"),
                "ADR-0006: an empty table scope still reads the effective settings");
    }

    @Test
    void theTableProbeIsATypedZeroRowSelectOfEveryColumn() {
        SqlPlan probe = plans("shop", "order`s").get(1);

        assertEquals(OperationKind.SOURCE_CAPABILITY_CHECK, probe.operationKind(), "obligation 20");
        assertEquals(TimeoutClass.CAPABILITY_PROBE, probe.timeoutClass(), "obligation 20");
        assertEquals(Set.of(RequiredPrivilege.SOURCE_SELECT), probe.requiredPrivileges(),
                "ADR-0006: the probe exercises SELECT on the chosen table and needs nothing else");
        assertEquals(EvidencePolicy.STATEMENT_AND_RESULT, probe.evidencePolicy(),
                "ADR-0006 §Capability checks: a probe keeps its statement and result as evidence");
        assertEquals("SELECT COUNT(*) AS `probed_rows` FROM (SELECT * FROM `shop`.`order``s` LIMIT 0) AS `select_probe`",
                sql(probe), "ADR-0006: `*` demands SELECT on every column, LIMIT 0 reads no row, and LIMIT keeps "
                        + "the derived table from being merged away");
        assertEquals(List.of(), probe.statements().get(0).parameters(),
                "ADR-0008 §Plans: the probe binds nothing; its only names are quoted identifiers");
        assertEquals(new ResultSchema(List.of(new ResultSchema.Column("probed_rows", "bigint", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.EXACTLY_ONE_ROW), probe.resultSchema(),
                "ADR-0008 §Plans: the result is typed without knowing the table's columns, by counting the "
                        + "zero-row read");
    }

    @Test
    void theSettingsPlanExposesEachEffectiveSettingAsATypedColumn() {
        SqlPlan settings = plans("shop", "orders").get(0);

        assertEquals(OperationKind.SOURCE_CAPABILITY_CHECK, settings.operationKind(), "obligation 20");
        assertEquals(TimeoutClass.CAPABILITY_PROBE, settings.timeoutClass(), "obligation 20");
        assertEquals(Set.of(), settings.requiredPrivileges(), "TP §6.5: reading system variables needs no privilege");
        assertEquals(EvidencePolicy.STATEMENT_AND_RESULT, settings.evidencePolicy(),
                "TP §6.5: the settings read keeps its statement and result as evidence");
        assertEquals(SETTINGS_SQL, sql(settings), "TP §6.5: settings are read as facts, not trusted from configuration");
        assertEquals(ResultSchema.Cardinality.EXACTLY_ONE_ROW, settings.resultSchema().cardinality(),
                "TP §6.5 last paragraph: the settings are one row of facts");

        Map<String, String> typed = new TreeMap<>();
        settings.resultSchema().columns().forEach(c -> typed.put(c.label(), c.databaseType() + " " + c.nullability()));
        assertEquals(new TreeMap<>(Map.ofEntries(
                Map.entry("time_zone", "varchar NOT_NULL"),
                Map.entry("character_set_client", "varchar NOT_NULL"),
                Map.entry("character_set_connection", "varchar NOT_NULL"),
                Map.entry("character_set_database", "varchar NOT_NULL"),
                Map.entry("character_set_filesystem", "varchar NOT_NULL"),
                Map.entry("character_set_results", "varchar NULLABLE"),
                Map.entry("character_set_server", "varchar NOT_NULL"),
                Map.entry("character_set_system", "varchar NOT_NULL"),
                Map.entry("collation_connection", "varchar NOT_NULL"),
                Map.entry("sql_mode", "varchar NOT_NULL"),
                Map.entry("information_schema_stats_expiry", "bigint unsigned NOT_NULL"))), typed,
                "TP §6.5 last paragraph: every named setting is a typed result column");
        assertEquals(11, settings.resultSchema().columns().size(), "TP §6.5 last paragraph: no setting is read twice or left untyped");
    }

    // --- Fingerprints (ADR-0008 §Plans) --------------------------------------------------------------

    /**
     * Pinned from an independent Python model of {@code SqlPlan/1} (length-prefixed UTF-8, big-endian ints,
     * SHA-256), not from the implementation.
     */
    @Test
    void theProbeFingerprintIsPinnedFromTheIndependentModel() {
        assertEquals("03ec8064ff2d9e2767ca0fdabf8f37515b9643dc3107b123e1340284539f5520", plans("shop", "order`s").get(1).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a capability probe's fingerprint must not depend on the JVM, machine or run");
    }

    @Test
    void distinctScopesGiveDistinctFingerprints() {
        List<PlanFingerprint> base = fingerprints(plans("shop", "orders", "customers"));
        Map<String, List<PlanFingerprint>> variants = Map.of(
                "database", fingerprints(plans("shop2", "orders", "customers")),
                "table name", fingerprints(plans("shop", "orders2", "customers")),
                "table order", fingerprints(plans("shop", "customers", "orders")),
                "table removed", fingerprints(plans("shop", "orders")),
                "table added", fingerprints(plans("shop", "orders", "customers", "items")),
                "quoted name", fingerprints(plans("shop", "orders`", "customers")));

        for (Map.Entry<String, List<PlanFingerprint>> variant : new TreeMap<>(variants).entrySet()) {
            assertNotEquals(base, variant.getValue(),
                    "ADR-0008 §Plans: changing only the scope's " + variant.getKey() + " must change the fingerprints");
        }
        assertEquals(base, fingerprints(plans("shop", "orders", "customers")), "ADR-0008 §Plans: equal scopes plan equally");
    }

    private static List<PlanFingerprint> fingerprints(List<SqlPlan> plans) {
        return plans.stream().map(SqlPlan::fingerprint).toList();
    }

    @Test
    void aScopeThatMixesDatabasesOrRepeatsATableIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PAIR.source().capabilityPlans(new MetadataScope(
                        new SchemaCoordinate("shop"), List.of(new TableCoordinate("other", "orders")))),
                "a capability scope covers the one selected database (ADR-0006)");
        assertThrows(IllegalArgumentException.class, () -> plans("shop", "orders", "orders"),
                "ADR-0006: a repeated table is a caller error, not a second probe");
    }

    // --- Read-only (obligation 20; ADR-0006 §Capability checks) --------------------------------------

    @Test
    void everyCapabilityPlanNeedsOnlyReadOnlyPrivileges() {
        for (SqlPlan plan : allPlansForHostileScope()) {
            assertTrue(READ_ONLY.containsAll(plan.requiredPrivileges()),
                    "ADR-0006: the MySQL account is read-only, but a capability plan asks for "
                            + plan.requiredPrivileges() + ": " + sql(plan));
        }
    }

    /** Statement words that write, lock, change settings, parse grants or leave the single statement. */
    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "FOR", "UPDATE", "SHARE", "LOCK", "UNLOCK", "FLUSH", "SET", "INSERT", "DELETE", "REPLACE", "MERGE",
            "CALL", "DO", "LOAD", "HANDLER", "INTO", "OUTFILE", "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
            "GRANT", "REVOKE", "SHOW", "GRANTS", "PRIVILEGES", "ANALYZE", "OPTIMIZE", "REPAIR", "KILL", "SHUTDOWN");

    @Test
    void noCapabilityStatementWritesLocksOrChangesSettingsEvenForAHostileScope() {
        Pattern word = Pattern.compile("[A-Za-z_@.]+");
        for (SqlPlan plan : allPlansForHostileScope()) {
            String outsideIdentifiers = skeleton(sql(plan));
            for (String delimiter : List.of(";", "--", "/*", "#", "'", "\"", "\\", "\n")) {
                assertTrue(!outsideIdentifiers.contains(delimiter),
                        "ADR-0006: a capability statement is one plain read; " + delimiter + " outside a quoted "
                                + "identifier in: " + sql(plan));
            }
            Matcher words = word.matcher(outsideIdentifiers);
            while (words.find()) {
                String token = words.group().toUpperCase(Locale.ROOT);
                assertTrue(!FORBIDDEN_WORDS.contains(token),
                        "ADR-0006 §Capability checks: no FOR UPDATE, FOR SHARE, LOCK, FLUSH, SET GLOBAL, DML or DDL; "
                                + "found " + token + " in: " + sql(plan));
            }
        }
    }

    private static List<SqlPlan> allPlansForHostileScope() {
        return plans("x`; LOCK TABLES t WRITE; --", HOSTILE.toArray(String[]::new));
    }

    // --- Quoting (obligation 5; TP §7.1) -------------------------------------------------------------

    @Test
    void hostileNamesNeverChangeStatementStructureAndRoundTripExactly() {
        String benign = sql(plans("shop", "orders").get(1));
        for (String database : List.of("shop", "x`; LOCK TABLES t WRITE; --")) {
            for (String table : HOSTILE) {
                ParameterizedStatement statement = plans(database, table).get(1).statements().get(0);

                assertEquals(skeleton(benign), skeleton(statement.sql()),
                        "ADR-0008 §Plans: a hostile name must not change the statement's structure: " + statement.sql());
                assertEquals(List.of("probed_rows", database, table, "select_probe"), identifiers(statement.sql()),
                        "TP §7.1: backtick quoting round-trips the exact source name: " + statement.sql());
                assertEquals(0, statement.parameters().size(),
                        "ADR-0008 §Plans: no placeholder, no parameter — a quoted ? is not a binding");
            }
        }
    }

    @Test
    void quotingBoundariesArePinnedLiterally() {
        assertEquals("SELECT COUNT(*) AS `probed_rows` FROM (SELECT * FROM `a``b`.`select` LIMIT 0) AS `select_probe`",
                sql(plans("a`b", "select").get(1)),
                "TP §7.1: always backtick-quoted, so a reserved word needs no special path; an embedded backtick is doubled");
        assertEquals("SELECT COUNT(*) AS `probed_rows` FROM (SELECT * FROM `s`.`a\"b\\c;--/*\nd` LIMIT 0) AS `select_probe`",
                sql(plans("s", "a\"b\\c;--/*\nd").get(1)),
                "TP §7.1: MySQL quoting is not PostgreSQL's — a double quote, backslash, ;, --, /* and newline stay literal");
        assertEquals(64, "é".repeat(32).getBytes(StandardCharsets.UTF_8).length,
                "TP §7.1: the fixture name is 64 bytes, MySQL's limit in characters and more than PostgreSQL's");
        assertTrue(sql(plans("s", "é".repeat(32)).get(1)).contains("`s`.`" + "é".repeat(32) + "`"),
                "TP §7.1: a 64-byte name is quoted whole, never cut");
        for (String nul : List.of("nul\u0000name", "\u0000leading", "trailing\u0000")) {
            assertThrows(IllegalArgumentException.class, () -> plans("s", nul),
                    "TP §7.1: MySQL permits no U+0000 anywhere in an identifier — first, middle or last character "
                            + "— so such a name is refused rather than quoted");
        }
    }

    /** The statement with every backtick-quoted identifier replaced by a marker; an independent reader. */
    private static String skeleton(String sql) {
        StringBuilder outside = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '`') {
                i = endOfQuoted(sql, i);
                outside.append("<id>");
            } else {
                outside.append(c);
            }
        }
        return outside.toString();
    }

    private static List<String> identifiers(String sql) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '`') {
                int end = endOfQuoted(sql, i);
                names.add(sql.substring(i + 1, end).replace("``", "`"));
                i = end;
            }
        }
        return names;
    }

    /** Index of the closing backtick of the identifier opened at {@code open}; a doubled backtick stays inside. */
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
