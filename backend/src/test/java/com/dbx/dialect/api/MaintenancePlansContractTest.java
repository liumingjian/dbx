package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code target.maintenancePlans} (obligation 28): ADR-0023's guarded drops, TP §7.3's {@code setval} and
 * ADR-0006's exact-object grants. Every assertion below is about the plan DBX would run; that the guards, the
 * drops and {@code setval} behave on a real PostgreSQL 15 is not provable at L1 and is owned by {@code contract}
 * slice 8 at L2 through {@code gateway}, and by the abandonment scenario at L3 (`dialect.md` §Verification —
 * {@code dialect} is pure and has no L2 of its own).
 */
class MaintenancePlansContractTest {

    private static final String GUARD_TABLE = "SELECT c.oid FROM pg_catalog.pg_class c "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = ?::\"char\" AND c.oid = ?::oid";

    private static final String GUARD_SCHEMA = "SELECT n.oid FROM pg_catalog.pg_namespace n "
            + "WHERE n.nspname = ? AND n.oid = ?::oid "
            + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid)";

    private static final ResultSchema OID_ROW = new ResultSchema(
            List.of(new ResultSchema.Column("oid", "oid", Nullability.NOT_NULL)),
            ResultSchema.Cardinality.EXACTLY_ONE_ROW);

    // --- Plan shape (obligation 28; ADR-0008 §Plans) -------------------------------------------------

    @Test
    void everyPlanCarriesTheTargetMaintenanceShape() {
        for (MaintenanceAction action : everyKindOfAction()) {
            SqlPlan plan = plan(action);

            assertEquals(OperationKind.TARGET_MAINTENANCE, plan.operationKind(),
                    "obligation 28: maintenance is one TARGET_MAINTENANCE plan per action: " + action);
            assertEquals(TimeoutClass.MAINTENANCE, plan.timeoutClass(),
                    "obligation 28: maintenance runs under the maintenance timeout class: " + action);
            assertEquals(Set.of(RequiredPrivilege.TARGET_OWN_OBJECT), plan.requiredPrivileges(),
                    "ADR-0023 §Consequences: the management account already owns every DBX-created object, so "
                            + "maintenance needs no privilege beyond owning it: " + action);
        }
    }

    @Test
    void theGuardedDropsDeclareTheOidRowAndTheOtherActionsDeclareTheirOwnResult() {
        assertEquals(OID_ROW, plan(dropTable("shop", "orders", 16384)).resultSchema(),
                "ADR-0023 §Identity, not name: the plan's one result schema describes the guard, the only "
                        + "statement that returns rows; the drop returns none");
        assertEquals(OID_ROW, plan(dropSchema("shop", 2200)).resultSchema(),
                "ADR-0023: the schema guard returns the namespace OID it proved");
        assertEquals(new ResultSchema(List.of(new ResultSchema.Column("setval", "bigint", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.EXACTLY_ONE_ROW), plan(setval("shop", "q", 7, true)).resultSchema(),
                "TP §7.3: setval returns the value it set");
        assertEquals(ResultSchema.NONE, plan(grant("shop", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT))
                        .resultSchema(), "ADR-0006: a GRANT returns no rows");
    }

    /**
     * The spec assigns no evidence policy to maintenance. The guarded drops keep their guard's OID row, because
     * it is the audit record of what authorised an irreversible drop and an OID is catalog metadata, never a
     * customer row value (ADR-0028). {@code setval} and {@code GRANT} keep only their statements: {@code setval}
     * echoes a key value that evidence does not need.
     */
    @Test
    void theEvidencePolicyKeepsTheGuardRowAndNoValue() {
        assertEquals(EvidencePolicy.STATEMENT_AND_RESULT, plan(dropTable("shop", "orders", 16384)).evidencePolicy(),
                "ADR-0023 §Confirmation renders the destruction first: the guard's OID row is the evidence");
        assertEquals(EvidencePolicy.STATEMENT_AND_RESULT, plan(dropSchema("shop", 2200)).evidencePolicy(),
                "ADR-0023: the namespace OID that authorised the schema drop is the evidence");
        assertEquals(EvidencePolicy.STATEMENT_ONLY, plan(setval("shop", "q", 7, true)).evidencePolicy(),
                "ADR-0028: setval echoes a key value, which evidence does not need");
        assertEquals(EvidencePolicy.STATEMENT_ONLY, plan(grant("shop", "orders", "sink",
                        RequiredPrivilege.TARGET_OWN_OBJECT)).evidencePolicy(),
                "ADR-0028: the GRANT statement says everything there is to record");
    }

    @Test
    void plansComeBackInTheOrderTheActionsWereGiven() {
        List<MaintenanceAction> actions = List.of(
                grant("shop", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT),
                setval("shop", "orders_id_seq", 42, true),
                dropTable("shop", "orders", 16384),
                dropSchema("shop", 2200));
        List<SqlPlan> plans = PAIR.target().maintenancePlans(actions);

        assertEquals(4, plans.size(), "obligation 28: one plan per action");
        assertEquals(actions.stream().map(MaintenancePlansContractTest::plan).map(SqlPlan::fingerprint).toList(),
                plans.stream().map(SqlPlan::fingerprint).toList(),
                "obligation 28: plans come back in the order the actions were given");
        assertEquals(List.of("GRANT ", "SELECT", "SELECT", "SELECT"),
                plans.stream().map(plan -> plan.statements().get(0).sql().substring(0, 6)).toList(),
                "obligation 28: the grant asked for first is planned first");
        assertEquals(List.of(), PAIR.target().maintenancePlans(List.of()),
                "obligation 28: nothing to maintain plans nothing; an empty list is not a silent success, "
                        + "because no statement is produced to run");
    }

    // --- Exact statement text (ADR-0023 §What is removed; ADR-0006; TP §7.3) -------------------------

    @Test
    void theStatementsArePinnedForEachAction() {
        assertEquals(List.of(GUARD_TABLE, "DROP TABLE \"shop\".\"orders\" RESTRICT"),
                sql(dropTable("shop", "orders", 16384)),
                "ADR-0023 §Identity, not name: the identity guard precedes the drop, which names RESTRICT");
        assertEquals(List.of(new SqlValue.Text("shop"), new SqlValue.Text("orders"), new SqlValue.Text("r"),
                        new SqlValue.Int64(16384)), plan(dropTable("shop", "orders", 16384)).statements().get(0)
                        .parameters(),
                "ADR-0023 §Identity, not name: name, namespace, relkind and OID are bound, never spliced");

        assertEquals(List.of(GUARD_SCHEMA, "DROP SCHEMA \"shop\" RESTRICT"), sql(dropSchema("shop", 2200)),
                "ADR-0023: a schema goes only when its pg_namespace OID matches and it holds no relation");
        assertEquals(List.of(new SqlValue.Text("shop"), new SqlValue.Int64(2200)),
                plan(dropSchema("shop", 2200)).statements().get(0).parameters(),
                "ADR-0023: the schema name and its recorded namespace OID are bound");

        assertEquals(List.of("SELECT setval(?::regclass, ?, ?)"), sql(setval("shop", "orders_id_seq", 42, true)),
                "TP §7.3: setval takes the sequence, the value and is_called, all bound");
        assertEquals(List.of("GRANT INSERT ON \"shop\".\"orders\" TO \"sink\""),
                sql(grant("shop", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT)),
                "ADR-0006: DBX grants one privilege on one object it created, to one grantee");
    }

    @Test
    void bothDropsRenderRestrictExplicitly() {
        for (MaintenanceAction drop : List.of(dropTable("shop", "orders", 16384), dropSchema("shop", 2200))) {
            List<String> statements = sql(drop);
            assertTrue(statements.get(1).endsWith(" RESTRICT"),
                    "ADR-0023 §What is removed: RESTRICT is PostgreSQL's default but is rendered anyway, so the "
                            + "refusal a DBA relies on is visible in the statement: " + statements.get(1));
        }
    }

    @Test
    void aGuardPrecedesEachDropAndDeclaresExactlyOneRow() {
        for (MaintenanceAction drop : List.of(dropTable("shop", "orders", 16384), dropSchema("shop", 2200))) {
            SqlPlan plan = plan(drop);

            assertEquals(2, plan.statements().size(),
                    "ADR-0023 §Identity, not name: the guard and the drop are one plan, so a caller cannot hold "
                            + "the drop without the guard that authorised it: " + drop);
            assertTrue(plan.statements().get(0).sql().startsWith("SELECT "),
                    "ADR-0023: the guard comes first: " + plan.statements().get(0).sql());
            assertTrue(plan.statements().get(1).sql().startsWith("DROP "),
                    "ADR-0023: the drop comes second: " + plan.statements().get(1).sql());
            assertEquals(ResultSchema.Cardinality.EXACTLY_ONE_ROW, plan.resultSchema().cardinality(),
                    "ADR-0023 §Identity, not name: a replaced object no longer matches, so the guard returns no "
                            + "row and the plan fails instead of dropping it: " + drop);
            assertTrue(plan.statements().get(0).sql().contains("c.oid = ?::oid")
                            || plan.statements().get(0).sql().contains("n.oid = ?::oid"),
                    "ADR-0023 §Identity, not name: the recorded OID is part of the guard, not of the drop");
        }
        assertTrue(plan(dropSchema("shop", 2200)).statements().get(0).sql().contains("NOT EXISTS"),
                "ADR-0023: a schema is dropped only when it is empty");
    }

    // --- No statement may cascade (ADR-0023 §What is removed; ADR-0006) ------------------------------

    private static final Pattern CASCADE = Pattern.compile("\\bCASCADE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The scan is over SQL tokens, so quoted identifiers are blanked first: a customer may legitimately have
     * named a table {@code CASCADE}, and that name inside its quotes is not the keyword. Every value is bound,
     * so a hostile name never reaches the parser as SQL at all.
     */
    @Test
    void noStatementCascadesForAnyActionOrAnyHostileIdentifier() {
        for (MaintenanceAction action : hostileActions()) {
            for (String statement : skeleton(sql(action))) {
                assertFalse(CASCADE.matcher(statement).find(),
                        "ADR-0023 §What is removed: cascading reaches customer objects outside the confirmed "
                                + "scope, so no maintenance statement ever cascades: " + statement);
            }
        }
    }

    // --- setval binds is_called, including false (TP §7.3) -------------------------------------------

    @Test
    void isCalledIsABoundBooleanSoTheEmptyTableCaseIsExpressible() {
        for (boolean isCalled : List.of(true, false)) {
            ParameterizedStatement statement = plan(setval("shop", "orders_id_seq", 1, isCalled))
                    .statements().get(0);

            assertEquals(List.of(new SqlValue.Text("\"shop\".\"orders_id_seq\""), new SqlValue.Int64(1),
                            new SqlValue.Bool(isCalled)), statement.parameters(),
                    "TP §7.3: the sequence is bound as text, the value as INT64 and is_called as a Boolean");
            assertFalse(statement.sql().contains("true") || statement.sql().contains("false"),
                    "TP §7.3: is_called is bound, never spliced into the statement: " + statement.sql());
        }
        assertEquals(new SqlValue.Int64(-1), plan(setval("shop", "q", -1, false)).statements().get(0)
                        .parameters().get(1),
                "TP §7.3: MaintenanceAction.SetOwnedSequence does not guard the value positive, so the "
                        + "empty-table alignment is expressible");
    }

    // --- Grants are exact and closed (ADR-0006 §Capability checks) -----------------------------------

    @Test
    void everyGrantablePrivilegeRendersOneExactObjectPrivilege() {
        Map<RequiredPrivilege, String> granted = new LinkedHashMap<>();
        granted.put(RequiredPrivilege.TARGET_READ_CATALOG, "SELECT");
        granted.put(RequiredPrivilege.TARGET_CREATE_IN_SCHEMA, "USAGE");
        granted.put(RequiredPrivilege.TARGET_OWN_OBJECT, "INSERT");
        for (Map.Entry<RequiredPrivilege, String> entry : granted.entrySet()) {
            assertEquals("GRANT " + entry.getValue() + " ON \"shop\".\"orders\" TO \"sink\"",
                    sql(grant("shop", "orders", "sink", entry.getKey())).get(0),
                    "ADR-0006: DBX grants exact object privileges on objects it created: " + entry.getKey());
        }
        String statement = sql(grant("shop", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT)).get(0);
        assertFalse(statement.contains(" ALL "), "ADR-0006: DBX never grants ALL: " + statement);
        assertFalse(statement.contains("ALL TABLES") || statement.contains("SCHEMA "),
                "ADR-0006: DBX never grants across a whole schema: " + statement);
    }

    @Test
    void aPrivilegeWithNoObjectLevelMeaningThrowsRatherThanRenderingSomethingPlausible() {
        for (RequiredPrivilege privilege : List.of(RequiredPrivilege.TARGET_CREATE_SCHEMA,
                RequiredPrivilege.SOURCE_READ_METADATA, RequiredPrivilege.SOURCE_SELECT)) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> PAIR.target().maintenancePlans(List.of(grant("shop", "orders", "sink", privilege))),
                    "ADR-0006: " + privilege + " has no object-level PostgreSQL privilege, so the mapping refuses "
                            + "it rather than rendering something plausible a DBA would run");
            assertTrue(refusal.getMessage().contains(privilege.name()),
                    "the refusal names the privilege it cannot map: " + refusal.getMessage());
        }
        assertEquals(6, RequiredPrivilege.values().length,
                "ADR-0006: the privilege mapping is closed; a new constant must be mapped or refused on purpose");
    }

    // --- Fingerprints (ADR-0008 §Plans) ---------------------------------------------------------------

    @Test
    void distinctActionsGiveDistinctFingerprints() {
        Map<String, MaintenanceAction> variants = new TreeMap<>();
        variants.put("drop table: schema", dropTable("shop2", "orders", 16384));
        variants.put("drop table: name", dropTable("shop", "order", 16384));
        variants.put("drop table: oid", dropTable("shop", "orders", 16385));
        variants.put("drop schema: name", dropSchema("shop2", 2200));
        variants.put("drop schema: oid", dropSchema("shop", 2201));
        variants.put("drop schema: baseline", dropSchema("shop", 2200));
        variants.put("setval: schema", setval("shop2", "orders_id_seq", 42, true));
        variants.put("setval: sequence", setval("shop", "orders_id_seq2", 42, true));
        variants.put("setval: value", setval("shop", "orders_id_seq", 43, true));
        variants.put("setval: is_called", setval("shop", "orders_id_seq", 42, false));
        variants.put("setval: baseline", setval("shop", "orders_id_seq", 42, true));
        variants.put("grant: schema", grant("shop2", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT));
        variants.put("grant: object", grant("shop", "order", "sink", RequiredPrivilege.TARGET_OWN_OBJECT));
        variants.put("grant: grantee", grant("shop", "orders", "sink2", RequiredPrivilege.TARGET_OWN_OBJECT));
        variants.put("grant: privilege", grant("shop", "orders", "sink", RequiredPrivilege.TARGET_READ_CATALOG));
        variants.put("grant: baseline", grant("shop", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT));

        MaintenanceAction base = dropTable("shop", "orders", 16384);
        assertEquals(plan(base).fingerprint(), plan(dropTable("shop", "orders", 16384)).fingerprint(),
                "ADR-0008 §Plans: an equal action is an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(plan(base).fingerprint()));
        for (Map.Entry<String, MaintenanceAction> variant : variants.entrySet()) {
            assertTrue(seen.add(plan(variant.getValue()).fingerprint()),
                    "ADR-0008 §Plans: changing only the " + variant.getKey() + " must change the fingerprint");
        }
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256) over the literal statements of {@link #theStatementsArePinnedForEachAction} with the
     * representative names below, in a separate Python script quoted on ticket #141. A change here is a change
     * of statement text or of the encoding, never noise.
     */
    @Test
    void theRepresentativeFingerprintsArePinnedFromTheIndependentModel() {
        assertEquals("8fccedc18ac874c864122d0e94d1c4310c113ce81b84e8cb00b9182cabc54284",
                plan(dropTable("客户", "order\"s", 16384)).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a guarded drop's fingerprint must not depend on the JVM, the machine or the run");
        assertEquals("3b4ce65fd1194bcbc4bb6d5ae9ef9e73507a482007722d4a8ddc13ac925c0ead",
                plan(setval("shop", "orders_id_seq", 1, false)).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the empty-table setval pins the bound Boolean in the encoding");
    }

    // --- Hostile identifiers (TP §7.1; ADR-0008 §Plans) ----------------------------------------------

    private static final List<String> HOSTILE = List.of(
            "it's", "back\\slash", "$$dollar$$", "`tick`", "new\nline", "cr\rtab\t", "a\"b", "?", "soh",
            "del", "客户订单", "emoji😀", "x\"; DROP TABLE t; --", "'); DROP TABLE t; --", "a''b", "e\\'",
            "x CASCADE", "CASCADE", "a /* b */ c", "-- comment");

    @Test
    void hostileIdentifiersNeverChangeStatementStructure() {
        Map<String, List<String>> benign = new LinkedHashMap<>();
        benign.put("dropTable", skeleton(sql(dropTable("s", "t", 16384))));
        benign.put("dropSchema", skeleton(sql(dropSchema("s", 2200))));
        benign.put("setval", skeleton(sql(setval("s", "q", 1, true))));
        benign.put("grant", skeleton(sql(grant("s", "t", "g", RequiredPrivilege.TARGET_OWN_OBJECT))));

        for (String hostile : HOSTILE) {
            Map<String, MaintenanceAction> actions = new LinkedHashMap<>();
            actions.put("dropTable", dropTable(hostile, hostile + "t", 16384));
            actions.put("dropSchema", dropSchema(hostile, 2200));
            actions.put("setval", setval(hostile, hostile + "q", 1, true));
            actions.put("grant", grant(hostile, hostile + "t", hostile + "g",
                    RequiredPrivilege.TARGET_OWN_OBJECT));
            for (Map.Entry<String, MaintenanceAction> entry : actions.entrySet()) {
                SqlPlan plan = plan(entry.getValue());
                assertEquals(benign.get(entry.getKey()), skeleton(sql(entry.getValue())),
                        "TP §7.1: a hostile name stays inside its quotes or its bound value: " + hostile);
                for (ParameterizedStatement statement : plan.statements()) {
                    assertEquals(placeholders(statement.sql()), statement.parameters().size(),
                            "ADR-0008 §Plans: parameter count equals placeholder count: " + statement.sql());
                }
            }
        }
    }

    @Test
    void aNameOf63BytesIsKeptAndOf64BytesIsRefused() {
        String bytes63 = "订".repeat(21);
        String bytes64 = "订".repeat(21) + "a";
        assertEquals(63, bytes63.getBytes(StandardCharsets.UTF_8).length,
                "TP §7.1: the fixture name is exactly PostgreSQL's 63-byte limit");

        assertTrue(sql(dropTable(bytes63, bytes63, 16384)).get(1).contains("\"" + bytes63 + "\""),
                "TP §7.1: a 63-byte name is PostgreSQL's limit and is kept exactly");
        for (MaintenanceAction refused : List.of(dropTable(bytes64, "t", 1), dropTable("s", bytes64, 1),
                dropSchema(bytes64, 1), setval(bytes64, "q", 1, true), setval("s", bytes64, 1, true),
                grant(bytes64, "t", "g", RequiredPrivilege.TARGET_OWN_OBJECT),
                grant("s", bytes64, "g", RequiredPrivilege.TARGET_OWN_OBJECT),
                grant("s", "t", bytes64, RequiredPrivilege.TARGET_OWN_OBJECT))) {
            assertThrows(IllegalArgumentException.class, () -> PAIR.target().maintenancePlans(List.of(refused)),
                    "TP §7.1: PostgreSQL truncates a 64-byte name, so it would address a different object");
        }
    }

    // --- Fixtures ------------------------------------------------------------------------------------

    private static List<MaintenanceAction> everyKindOfAction() {
        return List.of(dropTable("shop", "orders", 16384), dropSchema("shop", 2200),
                setval("shop", "orders_id_seq", 42, true),
                grant("shop", "orders", "sink", RequiredPrivilege.TARGET_OWN_OBJECT));
    }

    private static List<MaintenanceAction> hostileActions() {
        List<MaintenanceAction> actions = new ArrayList<>(everyKindOfAction());
        for (String hostile : HOSTILE) {
            actions.add(dropTable(hostile, hostile + "t", 16384));
            actions.add(dropSchema(hostile, 2200));
            actions.add(setval(hostile, hostile + "q", 1, false));
            for (RequiredPrivilege privilege : List.of(RequiredPrivilege.TARGET_READ_CATALOG,
                    RequiredPrivilege.TARGET_CREATE_IN_SCHEMA, RequiredPrivilege.TARGET_OWN_OBJECT)) {
                actions.add(grant(hostile, hostile + "t", hostile + "g", privilege));
            }
        }
        return actions;
    }

    private static MaintenanceAction dropTable(String schema, String name, long oid) {
        return new MaintenanceAction.DropOwnedTable(coordinate(schema, name), oid);
    }

    private static MaintenanceAction dropSchema(String schema, long oid) {
        return new MaintenanceAction.DropEmptySchema(new TargetIdentifier(schema), oid);
    }

    private static MaintenanceAction setval(String schema, String sequence, long value, boolean isCalled) {
        return new MaintenanceAction.SetOwnedSequence(coordinate(schema, sequence), value, isCalled);
    }

    private static MaintenanceAction grant(String schema, String object, String grantee, RequiredPrivilege privilege) {
        return new MaintenanceAction.GrantOnOwnedObject(coordinate(schema, object), new TargetIdentifier(grantee),
                privilege);
    }

    private static TargetTableCoordinate coordinate(String schema, String name) {
        return new TargetTableCoordinate(new TargetIdentifier(schema), new TargetIdentifier(name));
    }

    private static SqlPlan plan(MaintenanceAction action) {
        List<SqlPlan> plans = PAIR.target().maintenancePlans(List.of(action));
        assertEquals(1, plans.size(), "obligation 28: one plan per action");
        return plans.get(0);
    }

    private static List<String> sql(MaintenanceAction action) {
        return plan(action).statements().stream().map(ParameterizedStatement::sql).toList();
    }

    /** Counts {@code ?} outside quoted spans, the way {@link ParameterizedStatement} does. */
    private static int placeholders(String sql) {
        return (int) skeleton(sql).chars().filter(c -> c == '?').count();
    }

    private static List<String> skeleton(List<String> statements) {
        return statements.stream().map(MaintenancePlansContractTest::skeleton).toList();
    }

    /** Replaces every double-quoted identifier and single-quoted literal by a placeholder, keeping structure. */
    private static String skeleton(String sql) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '"' || c == '\'') {
                i++;
                while (!(sql.charAt(i) == c && (i + 1 == sql.length() || sql.charAt(i + 1) != c))) {
                    i += sql.charAt(i) == c ? 2 : 1;
                }
                out.append(c).append('_').append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
