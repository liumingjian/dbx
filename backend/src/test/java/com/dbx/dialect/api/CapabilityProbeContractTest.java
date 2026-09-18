package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code target.capabilityProbePlans} and {@code target.leastPrivilegeSql} (obligation 27): ADR-0006
 * §Capability checks proven on an isolated, uniquely named probe object, and the text a DBA gets when a
 * privilege is missing.
 *
 * <p>That the five plans actually succeed on a real PostgreSQL 15 is not provable at L1 and is not
 * approximated here: it runs at L2 in {@code contract} slice 8 through {@code gateway}
 * (docs/spec/dialect.md §Verification; ADR-0022).
 */
class CapabilityProbeContractTest {

    private static final TargetIdentifier SCHEMA = new TargetIdentifier("shop");
    private static final TargetIdentifier PROBE = new TargetIdentifier("dbx_probe_1");

    /** ADR-0006: the probe may need these three and nothing else; creating the schema is not probed. */
    private static final Set<RequiredPrivilege> PROBEABLE = Set.of(RequiredPrivilege.TARGET_CREATE_IN_SCHEMA,
            RequiredPrivilege.TARGET_OWN_OBJECT, RequiredPrivilege.TARGET_READ_CATALOG);

    /** Target-side hostile names, as {@code TargetDialectContractTest} uses them, plus a production-looking pair. */
    private static final List<String> HOSTILE = List.of(
            "it's", "back\\slash", "$$dollar$$", "`tick`", "new\nline", "cr\rtab\t", "a\"b", "?", "\u0001soh",
            "\u007Fdel", "客户订单", "emoji😀", "x\"; DROP TABLE t; --", "'); DROP TABLE t; --", "a''b", "e\\'",
            "a /* b */", "orders");

    // --- Plan shape and order (ADR-0006 §Capability checks; ADR-0008 §Plans) -------------------------

    @Test
    void theFivePlansExerciseCreateInsertReadTruncateAndDropInThatOrder() {
        List<SqlPlan> plans = PAIR.target().capabilityProbePlans(SCHEMA, PROBE);

        assertEquals(5, plans.size(), "ADR-0006 §Capability checks: create, insert, read, truncate and drop");
        List<String> opening = List.of("CREATE TABLE ", "INSERT INTO ", "SELECT ", "TRUNCATE TABLE ", "DROP TABLE ");
        for (int i = 0; i < plans.size(); i++) {
            assertTrue(plans.get(i).statements().get(0).sql().startsWith(opening.get(i)),
                    "ADR-0006 §Capability checks: step " + i + " is " + opening.get(i).trim()
                            + ", in that order: " + plans.get(i).statements().get(0).sql());
        }
    }

    @Test
    void everyPlanHasTheCapabilityProbeShape() {
        for (SqlPlan plan : PAIR.target().capabilityProbePlans(SCHEMA, PROBE)) {
            assertEquals(OperationKind.TARGET_CAPABILITY_PROBE, plan.operationKind(),
                    "obligation 27: every probe plan is a TARGET_CAPABILITY_PROBE");
            assertEquals(TimeoutClass.CAPABILITY_PROBE, plan.timeoutClass(),
                    "ADR-0006 §Capability checks: a probe runs under the capability-probe timeout class");
            assertEquals(EvidencePolicy.STATEMENT_ONLY, plan.evidencePolicy(),
                    "ADR-0028: a probe proves a privilege, so the only row it reads — the one it fabricated in "
                            + "its own disposable object — is never kept as evidence");
            assertTrue(PROBEABLE.containsAll(plan.requiredPrivileges()),
                    "ADR-0006 §Capability checks: a probe needs at most CREATE in the schema, ownership of its own "
                            + "object and catalog read; TARGET_CREATE_SCHEMA is orchestration's authorized step, "
                            + "not a disposable object: " + plan.requiredPrivileges());
            assertFalse(plan.requiredPrivileges().isEmpty(),
                    "ADR-0006 §Capability checks: a probe that demonstrates no privilege proves nothing");
        }
    }

    @Test
    void eachStepDeclaresTheOnePrivilegeItProvesAndTheReadDeclaresItsTypedResult() {
        List<SqlPlan> plans = PAIR.target().capabilityProbePlans(SCHEMA, PROBE);

        assertEquals(Set.of(RequiredPrivilege.TARGET_CREATE_IN_SCHEMA), plans.get(0).requiredPrivileges(),
                "ADR-0006 §Capability checks: creating the probe object needs CREATE in the schema, nothing more");
        for (int step : List.of(1, 3, 4)) {
            assertEquals(Set.of(RequiredPrivilege.TARGET_OWN_OBJECT), plans.get(step).requiredPrivileges(),
                    "ADR-0006 §Capability checks: writing, truncating and dropping touch only the object DBX owns");
        }
        assertEquals(Set.of(RequiredPrivilege.TARGET_OWN_OBJECT, RequiredPrivilege.TARGET_READ_CATALOG),
                plans.get(2).requiredPrivileges(),
                "ADR-0006 §Capability checks: the management account introspects the object it created and reads it");
        assertEquals(Set.of(RequiredPrivilege.TARGET_CREATE_IN_SCHEMA, RequiredPrivilege.TARGET_OWN_OBJECT,
                        RequiredPrivilege.TARGET_READ_CATALOG),
                plans.stream().flatMap(plan -> plan.requiredPrivileges().stream()).collect(HashSet::new,
                        Set::add, Set::addAll),
                "ADR-0006 §Capability checks: the probe proves every target privilege a run needs before approval");

        for (int step : List.of(0, 1, 3, 4)) {
            assertEquals(ResultSchema.NONE, plans.get(step).resultSchema(),
                    "ADR-0008 §Plans: a statement that returns no rows declares no result columns");
        }
        assertEquals(new ResultSchema(List.of(new ResultSchema.Column("count", "bigint", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.EXACTLY_ONE_ROW), plans.get(2).resultSchema(),
                "ADR-0008 §Plans: both read statements return one COUNT(*), so one typed result schema describes "
                        + "the whole read plan");
    }

    @Test
    void theDropPlanIsAlwaysPresentAndAlwaysLast() {
        List<TargetIdentifier> names = new ArrayList<>(List.of(PROBE));
        HOSTILE.forEach(hostile -> names.add(new TargetIdentifier(hostile)));
        for (TargetIdentifier name : names) {
            List<SqlPlan> plans = PAIR.target().capabilityProbePlans(SCHEMA, name);
            List<String> drops = statements(plans).stream().filter(sql -> sql.startsWith("DROP ")).toList();

            assertEquals(1, drops.size(),
                    "ADR-0006 §Capability checks: every probe has exactly one durable cleanup request: " + name.name());
            assertEquals(drops.get(0), plans.get(plans.size() - 1).statements().get(0).sql(),
                    "ADR-0006 §Capability checks: the drop is last, so the caller holds the cleanup request "
                            + "whatever an earlier step did: " + name.name());
        }
    }

    // --- Exact text (ADR-0008 §Plans) ---------------------------------------------------------------

    @Test
    void theStatementsArePinnedForARepresentativeProbe() {
        assertEquals(List.of(
                        "CREATE TABLE \"shop\".\"dbx_probe_1\" (\"dbx_probe_1\" bigint NOT NULL)",
                        "INSERT INTO \"shop\".\"dbx_probe_1\" (\"dbx_probe_1\") VALUES (?)",
                        "SELECT COUNT(*) FROM pg_catalog.pg_class c "
                                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                                + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = ?",
                        "SELECT COUNT(*) FROM \"shop\".\"dbx_probe_1\"",
                        "TRUNCATE TABLE \"shop\".\"dbx_probe_1\"",
                        "DROP TABLE \"shop\".\"dbx_probe_1\" RESTRICT"),
                statements(PAIR.target().capabilityProbePlans(SCHEMA, PROBE)),
                "ADR-0006 §Capability checks: the probe creates, writes, introspects and reads back, truncates and "
                        + "drops one object of its own, and does nothing else");
    }

    @Test
    void theProbeBindsItsOwnRowAndAddressesItselfInTheCatalogByValue() {
        List<SqlPlan> plans = PAIR.target().capabilityProbePlans(SCHEMA, PROBE);

        assertEquals(List.of(new SqlValue.Int64(1)), plans.get(1).statements().get(0).parameters(),
                "ADR-0008 §Plans: the fabricated probe row is bound, never spliced");
        assertEquals(List.of(new SqlValue.Text("shop"), new SqlValue.Text("dbx_probe_1"), new SqlValue.Text("r")),
                plans.get(2).statements().get(0).parameters(),
                "ADR-0011 §DDL and structural proof: catalog lookups carry names as values, so no name reaches "
                        + "catalog SQL as text");
    }

    @Test
    void noProbeStatementDropsOrTruncatesBeyondItsOwnObject() {
        List<String> tables = new ArrayList<>(List.of("shop"));
        HOSTILE.forEach(tables::add);
        for (String name : tables) {
            for (String sql : statements(PAIR.target().capabilityProbePlans(SCHEMA, new TargetIdentifier(name)))) {
                assertFalse(Pattern.compile("\\bCASCADE\\b", Pattern.CASE_INSENSITIVE).matcher(skeleton(sql)).find(),
                        "ADR-0023 §What is removed: a probe never reaches past its own object: " + sql);
                assertTrue(skeleton(sql).endsWith("RESTRICT") || !skeleton(sql).startsWith("DROP "),
                        "ADR-0006: the cleanup request refuses dependents explicitly rather than by default: " + sql);
            }
        }
    }

    // --- Identifier confinement: no production table can be named (ADR-0006; ADR-0011) ---------------

    /**
     * The statement skeletons with every quoted identifier replaced. Nothing here varies with the caller's
     * input, and the only relations it names are {@code pg_catalog}'s, so the probe cannot address a
     * production object however it is called.
     */
    private static final List<String> SKELETON = List.of(
            "CREATE TABLE <id>.<id> (<id> bigint NOT NULL)",
            "INSERT INTO <id>.<id> (<id>) VALUES (?)",
            "SELECT COUNT(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = ?",
            "SELECT COUNT(*) FROM <id>.<id>",
            "TRUNCATE TABLE <id>.<id>",
            "DROP TABLE <id>.<id> RESTRICT");

    @Test
    void theOnlyIdentifiersInAnyProbeStatementAreTheProbesOwnTwo() {
        List<TargetIdentifier> names = new ArrayList<>(List.of(PROBE));
        HOSTILE.forEach(hostile -> names.add(new TargetIdentifier(hostile)));
        for (TargetIdentifier schema : names) {
            for (TargetIdentifier probe : names) {
                List<String> sql = statements(PAIR.target().capabilityProbePlans(schema, probe));
                Set<String> identifiers = new LinkedHashSet<>();
                sql.forEach(statement -> identifiers.addAll(quotedIdentifiers(statement)));

                assertEquals(new LinkedHashSet<>(List.of(schema.quoted(), probe.quoted())), identifiers,
                        "ADR-0006 §Capability checks: the probe names its own schema and object and nothing else, "
                                + "so no production table can be named by construction — ADR-0011 forbids a "
                                + "fabricated row anywhere else: " + schema.name() + " / " + probe.name());
                assertEquals(SKELETON, sql.stream().map(CapabilityProbeContractTest::skeleton).toList(),
                        "ADR-0006 §Capability checks: outside those two quoted names the text is fixed and names "
                                + "only pg_catalog, so caller input cannot become SQL: " + probe.name());
            }
        }
    }

    @Test
    void hostileNamesNeverChangeStatementStructureAndEveryPlaceholderIsBound() {
        for (String hostile : HOSTILE) {
            List<SqlPlan> plans = PAIR.target().capabilityProbePlans(
                    new TargetIdentifier(hostile), new TargetIdentifier(hostile + "_probe"));

            assertEquals(SKELETON, statements(plans).stream().map(CapabilityProbeContractTest::skeleton).toList(),
                    "TP §7.1: a hostile name stays inside its quotes: " + hostile);
            for (SqlPlan plan : plans) {
                for (ParameterizedStatement statement : plan.statements()) {
                    assertEquals(placeholders(statement.sql()), statement.parameters().size(),
                            "ADR-0008 §Plans: one bound value per placeholder, whatever the name contains: "
                                    + statement.sql());
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

        assertTrue(statements(PAIR.target().capabilityProbePlans(
                        new TargetIdentifier(bytes63), new TargetIdentifier(bytes63))).get(0)
                        .contains("\"" + bytes63 + "\"" + "." + "\"" + bytes63 + "\""),
                "TP §7.1: a 63-byte name is PostgreSQL's limit and is kept exactly");
        for (List<String> pair : List.of(List.of(bytes64, "p"), List.of("s", bytes64))) {
            assertThrows(IllegalArgumentException.class,
                    () -> PAIR.target().capabilityProbePlans(new TargetIdentifier(pair.get(0)),
                            new TargetIdentifier(pair.get(1))),
                    "TP §7.1: PostgreSQL truncates a 64-byte name, so the probe would create or drop a different "
                            + "object than the one the caller named");
        }
    }

    // --- Fingerprints (ADR-0008 §Plans) -------------------------------------------------------------

    @Test
    void distinctProbesGiveDistinctFingerprints() {
        List<PlanFingerprint> base = fingerprints(SCHEMA, PROBE);
        Map<String, List<PlanFingerprint>> variants = new TreeMap<>();
        variants.put("schema", fingerprints(new TargetIdentifier("shop2"), PROBE));
        variants.put("schema case", fingerprints(new TargetIdentifier("Shop"), PROBE));
        variants.put("probe name", fingerprints(SCHEMA, new TargetIdentifier("dbx_probe_2")));
        variants.put("probe case", fingerprints(SCHEMA, new TargetIdentifier("dbx_Probe_1")));
        variants.put("names swapped", fingerprints(PROBE, SCHEMA));

        assertEquals(base, fingerprints(SCHEMA, PROBE),
                "ADR-0008 §Plans: an equal probe is an equal plan, so a rerun of the same check fingerprints alike");
        Set<List<PlanFingerprint>> seen = new HashSet<>();
        seen.add(base);
        for (Map.Entry<String, List<PlanFingerprint>> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue()),
                    "ADR-0008 §Plans: changing only the " + variant.getKey() + " must change the fingerprint");
        }
    }

    /**
     * Pinned from an independent Python model of the documented {@code SqlPlan/1} encoding (length-prefixed
     * UTF-8, big-endian ints, SHA-256), fed with the literal statement text and result schema written here, not
     * with {@code plan.fingerprint()}. The script is quoted on ticket #140.
     */
    @Test
    void theRepresentativeFingerprintsArePinnedFromTheIndependentModel() {
        List<SqlPlan> plans = PAIR.target().capabilityProbePlans(SCHEMA, new TargetIdentifier("dbx\"probe_客户"));

        assertEquals(List.of(
                        "CREATE TABLE \"shop\".\"dbx\"\"probe_客户\" (\"dbx\"\"probe_客户\" bigint NOT NULL)",
                        "SELECT COUNT(*) FROM \"shop\".\"dbx\"\"probe_客户\""),
                List.of(plans.get(0).statements().get(0).sql(), plans.get(2).statements().get(1).sql()),
                "TP §7.1: an embedded double quote is doubled, and the pinned hashes below are taken from this text");
        assertEquals("8165523691a98506b62107debf87b78c0361e18c1fee18f07a1b81c4fa17c7f1",
                plans.get(0).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the create plan's fingerprint must not depend on the JVM, the machine or the run");
        assertEquals("ba792e1a79e944f2d223663420b16aec42caff76dc7729e61587d675131c7169",
                plans.get(2).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the read plan's fingerprint must not depend on the JVM, the machine or the run");
    }

    // --- leastPrivilegeSql: text for a DBA, never a plan (ADR-0006 §Capability checks) ---------------

    /** ADR-0006: DBX grants exact object privileges and does none of these, so its text proposes none either. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(SUPERUSER|ALL\\s+PRIVILEGES|CREATE\\s+ROLE|ALTER\\s+DEFAULT\\s+PRIVILEGES|REVOKE"
                    + "|GRANT\\s+OPTION|ON\\s+ALL\\s+\\w+\\s+IN\\s+SCHEMA)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void anEmptyMissingSetGivesTheEmptyString() {
        assertEquals("", PAIR.target().leastPrivilegeSql(Set.of()),
                "ADR-0006 §Capability checks: a check that found no missing privilege gives the DBA nothing to run, "
                        + "not an empty statement");
    }

    @Test
    void theTextIsPinnedForARepresentativeGapAndIsOrderedByPrivilegeName() {
        String expected = """
                -- Least-privilege SQL for a DBA to review and run. DBX renders it and never runs it, and never
                -- invents an account name (ADR-0006 §Capability checks).
                -- Replace <role> with the account DBX connects with, <schema> with the target schema, <database>
                -- with the target database, and <table> or <sequence> with one DBX-owned object; repeat an object
                -- line once per object.
                -- TARGET_CREATE_IN_SCHEMA: create DBX-owned tables, sequences and the capability probe
                -- object in the target schema. This is a privilege on the schema itself, not on the
                -- objects it already holds.
                GRANT USAGE, CREATE ON SCHEMA <schema> TO <role>;
                -- TARGET_OWN_OBJECT: read, insert into and truncate the objects DBX created, one statement
                -- per object. Dropping one needs ownership, which the account that created it already has.
                GRANT SELECT, INSERT, TRUNCATE ON TABLE <schema>.<table> TO <role>;
                GRANT USAGE, SELECT, UPDATE ON SEQUENCE <schema>.<sequence> TO <role>;
                -- TARGET_READ_CATALOG: look DBX-owned objects up in pg_catalog, which needs USAGE on the
                -- schema that holds them.
                GRANT USAGE ON SCHEMA <schema> TO <role>;
                """;

        assertEquals(expected, PAIR.target().leastPrivilegeSql(PROBEABLE),
                "ADR-0006 §Capability checks: a failed check hands the DBA exact object privileges with every "
                        + "account, schema and object left as a placeholder to fill in");
    }

    @Test
    void theTextIsDeterministicWhateverOrderTheGapArrivedIn() {
        Set<RequiredPrivilege> forwards = new LinkedHashSet<>();
        Set<RequiredPrivilege> backwards = new LinkedHashSet<>();
        List<RequiredPrivilege> all = List.of(RequiredPrivilege.values());
        all.forEach(forwards::add);
        all.reversed().forEach(backwards::add);

        assertEquals(PAIR.target().leastPrivilegeSql(forwards), PAIR.target().leastPrivilegeSql(backwards),
                "ADR-0006 §Capability checks: the same gap gives the same text, so a DBA can diff two checks");
        assertEquals(PAIR.target().leastPrivilegeSql(forwards),
                PAIR.target().leastPrivilegeSql(EnumSet.allOf(RequiredPrivilege.class)),
                "ADR-0006 §Capability checks: the text depends on the gap, never on the set implementation");
        String text = PAIR.target().leastPrivilegeSql(forwards);
        int previous = -1;
        for (RequiredPrivilege privilege : all.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList()) {
            int at = text.indexOf("-- " + privilege.name() + ":");
            assertTrue(at > previous,
                    "ADR-0006 §Capability checks: the clauses are ordered by privilege name: " + privilege);
            previous = at;
        }
    }

    @Test
    void noSubsetOfTheGapEverProposesAPrivilegeAdr0006Refuses() {
        for (Set<RequiredPrivilege> missing : powerSet()) {
            String text = PAIR.target().leastPrivilegeSql(missing);

            assertFalse(FORBIDDEN.matcher(text).find(),
                    "ADR-0006 §Capability checks: DBX does not create roles, alter default privileges, grant across "
                            + "a whole schema's objects, ask for superuser or take a privilege back: " + missing);
            for (String line : text.lines().toList()) {
                assertTrue(line.startsWith("--") || line.endsWith(" TO <role>;"),
                        "ADR-0006 §Capability checks: every statement is a grant to the marked role placeholder, "
                                + "and the rest is comment: " + line);
                assertFalse(line.startsWith("GRANT") && !line.contains("<schema>") && !line.contains("<database>"),
                        "ADR-0006 §Capability checks: a grant names a marked schema or database placeholder, never "
                                + "a name DBX invented: " + line);
            }
        }
    }

    @Test
    void everyGapIsAnsweredAndASourcePrivilegeGetsNoPostgresGrant() {
        for (Set<RequiredPrivilege> missing : powerSet()) {
            String text = PAIR.target().leastPrivilegeSql(missing);
            assertEquals(missing.isEmpty(), text.isEmpty(),
                    "ADR-0006 §Capability checks: a non-empty gap is answered, an empty one is not: " + missing);
            for (RequiredPrivilege privilege : missing) {
                assertTrue(text.contains("-- " + privilege.name() + ":"),
                        "ADR-0006 §Capability checks: a failed check names the missing capability: " + privilege);
            }
            for (RequiredPrivilege privilege : RequiredPrivilege.values()) {
                assertEquals(missing.contains(privilege), text.contains("-- " + privilege.name() + ":"),
                        "ADR-0006 §Capability checks: the text answers the gap and nothing else: " + privilege);
            }
        }
        String source = PAIR.target().leastPrivilegeSql(
                Set.of(RequiredPrivilege.SOURCE_SELECT, RequiredPrivilege.SOURCE_READ_METADATA));
        assertEquals(List.of(), source.lines().filter(line -> line.startsWith("GRANT")).toList(),
                "ADR-0006 §Capability checks: the MySQL account is read-only and PostgreSQL has no statement for its "
                        + "privileges, so the target dialect renders none rather than inventing a plausible one");
    }

    // --- Fixtures and scanners ----------------------------------------------------------------------

    private static List<String> statements(List<SqlPlan> plans) {
        return plans.stream().flatMap(plan -> plan.statements().stream())
                .map(ParameterizedStatement::sql).toList();
    }

    private static List<PlanFingerprint> fingerprints(TargetIdentifier schema, TargetIdentifier probe) {
        return PAIR.target().capabilityProbePlans(schema, probe).stream().map(SqlPlan::fingerprint).toList();
    }

    /** Every subset of {@link RequiredPrivilege}, built by a bit loop: 2^6 = 64 gaps. */
    private static List<Set<RequiredPrivilege>> powerSet() {
        RequiredPrivilege[] values = RequiredPrivilege.values();
        List<Set<RequiredPrivilege>> subsets = new ArrayList<>();
        for (int bits = 0; bits < 1 << values.length; bits++) {
            Set<RequiredPrivilege> subset = new LinkedHashSet<>();
            for (int i = 0; i < values.length; i++) {
                if ((bits & 1 << i) != 0) {
                    subset.add(values[i]);
                }
            }
            subsets.add(subset);
        }
        return subsets;
    }

    /** Every {@code "…"} span of a statement, quotes included; a doubled {@code ""} stays inside its span. */
    private static List<String> quotedIdentifiers(String sql) {
        return scan(sql).identifiers();
    }

    /** The statement with every quoted identifier replaced by {@code <id>}, so only fixed text remains. */
    private static String skeleton(String sql) {
        return scan(sql).skeleton();
    }

    private record Scanned(List<String> identifiers, String skeleton) {
    }

    private static Scanned scan(String sql) {
        List<String> identifiers = new ArrayList<>();
        StringBuilder skeleton = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) != '"') {
                skeleton.append(sql.charAt(i));
                continue;
            }
            int start = i;
            for (i++; i < sql.length(); i++) {
                if (sql.charAt(i) == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                        i++;
                        continue;
                    }
                    break;
                }
            }
            assertTrue(i < sql.length(), "a quoted identifier is closed: " + sql);
            identifiers.add(sql.substring(start, i + 1));
            skeleton.append("<id>");
        }
        return new Scanned(identifiers, skeleton.toString());
    }

    /** {@code ?} outside any quoted span, counted the way {@link ParameterizedStatement} counts it. */
    private static int placeholders(String sql) {
        int count = 0;
        for (char c : skeleton(sql).toCharArray()) {
            if (c == '?') {
                count++;
            }
        }
        return count;
    }
}
