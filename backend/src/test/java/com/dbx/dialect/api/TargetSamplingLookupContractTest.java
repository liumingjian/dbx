package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code target.samplingLookupPlan}: TP §9.3's typed key lookups, driven through {@code dialect.api} with
 * values ({@code docs/spec/dialect.md} obligation 23; {@code validation} obligation 27).
 *
 * <p>One plan per key tuple (spec #134 correction 4), typed parameterized equality on every component, and
 * {@link EvidencePolicy#STATEMENT_ONLY} because ADR-0028 lets no record or primary-key value be persisted
 * as evidence. This module compares nothing: TP §9.3's value semantics are {@code validation}'s
 * (obligation 28), and so is the judgement that a lookup found the one row it had to find.
 *
 * <p>What L1 cannot prove: that any of these statements runs on a real PostgreSQL 15 — {@code dialect.md}
 * §Verification puts that at L2 in {@code contract} slice 8 through {@code gateway}.
 */
class TargetSamplingLookupContractTest {

    private static final TargetTableCoordinate TABLE =
            new TargetTableCoordinate(new TargetIdentifier("public"), new TargetIdentifier("订单"));

    private static final List<TargetIdentifier> KEY_COLUMNS =
            List.of(new TargetIdentifier("id"), new TargetIdentifier("line no"));

    private static final String LOOKUP_SQL = "SELECT count(*) AS \"matched_rows\" FROM \"public\".\"订单\" "
            + "WHERE \"id\" = ? AND \"line no\" = ?";

    /** Hostile target names PostgreSQL accepts once quoted; the two oversized ones it must refuse. */
    private static final List<String> HOSTILE = List.of(
            "it's", "back\\slash", "$$dollar$$", "`tick`", "new\nline", "cr\rtab\t", "a\"b", "?",
            "x\"; DROP TABLE t; --", "'); DROP TABLE t; --", "a''b", "e\\'", "a -- b", "a /* b */",
            "客户订单", "emoji😀", "a".repeat(63), "订".repeat(21));

    private static final List<String> OVERSIZED = List.of("a".repeat(64), "订".repeat(22));

    /** A double-quoted PostgreSQL identifier: a doubled quote stays inside, so the span ends at a lone one. */
    private static final Pattern QUOTED = Pattern.compile("\"(?:[^\"]|\"\")*?\"(?!\")");

    /** Hostile values: every {@link SqlValue} variant, including a typed {@code NULL} and raw bytes. */
    private static final List<SqlValue> HOSTILE_VALUES = List.of(
            new SqlValue.Text("it's; DROP TABLE t; --"),
            new SqlValue.Text("$$body$$"),
            new SqlValue.Text("a\"b"),
            new SqlValue.Text("?"),
            new SqlValue.Text("订单\n"),
            new SqlValue.Int64(Long.MIN_VALUE),
            new SqlValue.Decimal(new BigDecimal("1.50")),
            new SqlValue.Bool(true),
            new SqlValue.Bytes(new byte[] {0x00, (byte) 0xFF, 0x7F}),
            new SqlValue.Null(SqlValue.Type.BYTES));

    // --- Fixture ------------------------------------------------------------------------------------

    private static ResultRows.Row key(SqlValue... values) {
        return new ResultRows.Row(List.of(values));
    }

    private static List<SqlPlan> plans(SamplingLookupKeys keys) {
        return PAIR.target().samplingLookupPlan(keys);
    }

    private static SamplingLookupKeys sample(List<ResultRows.Row> keys) {
        return new SamplingLookupKeys(TABLE, KEY_COLUMNS, keys);
    }

    private static ParameterizedStatement statement(SqlPlan plan) {
        assertEquals(1, plan.statements().size(), "TP §9.3: one lookup is one statement");
        return plan.statements().get(0);
    }

    private static String skeleton(String statement) {
        return QUOTED.matcher(statement).replaceAll(Matcher.quoteReplacement("<id>"));
    }

    // --- Plan shape (ADR-0008 §Plans) ---------------------------------------------------------------

    @Test
    void eachKeyTupleGetsItsOwnPlanInKeyOrder() {
        List<SqlPlan> plans = plans(sample(List.of(
                key(new SqlValue.Int64(7), new SqlValue.Text("a")),
                key(new SqlValue.Int64(8), new SqlValue.Text("b")))));

        assertEquals(2, plans.size(), "spec #134 correction 4: one lookup plan per sampled key tuple");
        assertEquals(List.of(new SqlValue.Int64(7), new SqlValue.Text("a")), statement(plans.get(0)).parameters(),
                "TP §9.3: the keys are bound in the order they were sampled");
        assertEquals(List.of(new SqlValue.Int64(8), new SqlValue.Text("b")), statement(plans.get(1)).parameters(),
                "TP §9.3: the second plan is the second key");
        assertNotEquals(plans.get(0).fingerprint(), plans.get(1).fingerprint(),
                "ADR-0008 §Plans: two lookups of two keys are two different plans");
    }

    @Test
    void everyLookupCarriesTheTargetKindTimeoutPrivilegesEvidenceAndCardinality() {
        SqlPlan plan = plans(sample(List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0);

        assertEquals(OperationKind.TARGET_SAMPLING_LOOKUP, plan.operationKind(),
                "docs/spec/dialect.md obligation 23: a target key lookup is its own operation kind");
        assertEquals(TimeoutClass.EXACT_SCAN, plan.timeoutClass(),
                "TP §9.3: a sampled lookup is bounded by the data it reads, like the sample it answers");
        assertEquals(Set.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT),
                plan.requiredPrivileges(),
                "ADR-0006: reading a DBX-owned table needs USAGE on its schema and the object privilege");
        assertEquals(EvidencePolicy.STATEMENT_ONLY, plan.evidencePolicy(),
                "ADR-0028 §No data values: a lookup binds primary-key values, and no row value or key value "
                        + "may be persisted as evidence, not even on request");
        assertEquals(ResultSchema.Cardinality.EXACTLY_ONE_ROW, plan.resultSchema().cardinality(),
                "TP §9.3: the lookup answers exactly one row for every key, so validation reads a finding "
                        + "rather than an execution error when a sampled key is missing");
        assertEquals(List.of(new ResultSchema.Column("matched_rows", "bigint", Nullability.NOT_NULL)),
                plan.resultSchema().columns(),
                "validation obligation 27: how many rows a key matched is the fact; the grading that it must "
                        + "be exactly one is validation's");
    }

    @Test
    void theRepresentativeStatementIsPinned() {
        assertEquals(LOOKUP_SQL,
                statement(plans(sample(List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0)).sql(),
                "validation obligation 27: typed parameterized equality on every key component, and the key "
                        + "columns quoted under their approved target names");
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256) over the literal {@link #LOOKUP_SQL} and the declared result column, in a separate
     * Python script quoted on ticket #142 — never copied out of the implementation.
     */
    @Test
    void theRepresentativeLookupFingerprintIsPinnedFromTheIndependentModel() {
        assertEquals("7fb870889c6697b1abdf45448880188ef0b500cc770c72b25a59f36fc2266b92",
                plans(sample(List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0)
                        .fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a lookup's fingerprint must not depend on the JVM, the machine or the run");
    }

    @Test
    void distinctLookupsGiveDistinctFingerprints() {
        SqlPlan base = plans(sample(List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0);
        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "the schema", PAIR.target().samplingLookupPlan(new SamplingLookupKeys(
                        new TargetTableCoordinate(new TargetIdentifier("dbx"), new TargetIdentifier("订单")),
                        KEY_COLUMNS, List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0),
                "the table", PAIR.target().samplingLookupPlan(new SamplingLookupKeys(
                        new TargetTableCoordinate(new TargetIdentifier("public"), new TargetIdentifier("orders")),
                        KEY_COLUMNS, List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0),
                "a key column name", PAIR.target().samplingLookupPlan(new SamplingLookupKeys(TABLE,
                        List.of(new TargetIdentifier("id"), new TargetIdentifier("line")),
                        List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0),
                "the key column order", PAIR.target().samplingLookupPlan(new SamplingLookupKeys(TABLE,
                        List.of(new TargetIdentifier("line no"), new TargetIdentifier("id")),
                        List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0),
                "one bound value", plans(sample(List.of(key(new SqlValue.Int64(8), new SqlValue.Text("a")))))
                        .get(0),
                "the type of a bound value", plans(sample(List.of(
                        key(new SqlValue.Decimal(new BigDecimal("7")), new SqlValue.Text("a"))))).get(0)));

        assertEquals(base.fingerprint(),
                plans(sample(List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0).fingerprint(),
                "ADR-0008 §Plans: an equal sample is an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only " + variant.getKey() + " must change the fingerprint");
        }
    }

    // --- Typed equality on every component (validation obligation 27) --------------------------------

    @Test
    void everyKeyComponentIsComparedAndEveryValueIsBound() {
        for (int arity = 1; arity <= 4; arity++) {
            List<TargetIdentifier> columns = new ArrayList<>();
            List<SqlValue> values = new ArrayList<>();
            for (int i = 1; i <= arity; i++) {
                columns.add(new TargetIdentifier("k" + i));
                values.add(new SqlValue.Int64(i));
            }
            ParameterizedStatement statement = statement(PAIR.target().samplingLookupPlan(
                    new SamplingLookupKeys(TABLE, columns, List.of(new ResultRows.Row(values)))).get(0));

            assertEquals(arity, statement.parameters().size(),
                    "validation obligation 27: every key component is compared, so every component is bound");
            assertEquals(values, statement.parameters(),
                    "ADR-0008 §Plans: the values are bound as they were handed over, never converted");
            for (int i = 1; i <= arity; i++) {
                assertTrue(statement.sql().contains("\"k" + i + "\" = ?"),
                        "validation obligation 27: typed parameterized equality on component k" + i + " — "
                                + statement.sql());
            }
        }
    }

    @Test
    void aKeyTupleOfTheWrongArityIsRefused() {
        for (List<SqlValue> wrong : List.of(
                List.<SqlValue>of(new SqlValue.Int64(7)),
                List.<SqlValue>of(),
                List.<SqlValue>of(new SqlValue.Int64(7), new SqlValue.Text("a"), new SqlValue.Text("b")))) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> plans(sample(List.of(new ResultRows.Row(wrong)))),
                    "TP §9.3: a tuple that does not match the key columns cannot be compared on every component");
            assertTrue(refused.getMessage().contains(String.valueOf(wrong.size())),
                    "the refusal names the arity it got — " + refused.getMessage());
        }
    }

    @Test
    void noValueIsEverRenderedIntoTheStatement() {
        for (SqlValue value : HOSTILE_VALUES) {
            SqlPlan plan = plans(sample(List.of(key(value, value)))).get(0);
            ParameterizedStatement statement = statement(plan);

            assertEquals(LOOKUP_SQL, statement.sql(),
                    "ADR-0008 §Plans: a value never reaches the statement text, whatever it holds — " + value);
            assertEquals(List.of(value, value), statement.parameters(),
                    "ADR-0008 §Plans: the value is bound exactly as it was handed over");
        }
    }

    // --- Names (obligation 5; TP §7.1) --------------------------------------------------------------

    @Test
    void aHostileTargetNameNeverChangesTheStatementStructureOrTheBindings() {
        String expected = null;
        for (String name : HOSTILE) {
            TargetIdentifier identifier = new TargetIdentifier(name);
            ParameterizedStatement statement = statement(PAIR.target().samplingLookupPlan(new SamplingLookupKeys(
                    new TargetTableCoordinate(new TargetIdentifier("public"), identifier),
                    List.of(identifier, identifier),
                    List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0));

            if (expected == null) {
                expected = skeleton(statement.sql());
            }
            assertEquals(expected, skeleton(statement.sql()),
                    "TP §7.1: a hostile name is quoted, never able to change the statement's structure — " + name);
            assertEquals(statement.sql().length() - statement.sql().replace("?", "").length()
                            - quotedPlaceholders(statement.sql()), statement.parameters().size(),
                    "ADR-0008 §Plans: every placeholder outside a quoted identifier is bound — " + statement.sql());
        }
    }

    @Test
    void aTargetNameOverSixtyThreeBytesIsRefusedRatherThanTruncated() {
        for (String name : OVERSIZED) {
            TargetIdentifier identifier = new TargetIdentifier(name);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> PAIR.target().samplingLookupPlan(new SamplingLookupKeys(TABLE, List.of(identifier),
                            List.of(key(new SqlValue.Int64(7))))),
                    "TP §7.1: PostgreSQL truncates a longer identifier into a different object");
            assertTrue(refused.getMessage().contains("63 bytes"),
                    "TP §7.1: the refusal names the limit — " + refused.getMessage());
        }
    }

    private static int quotedPlaceholders(String statement) {
        int inside = 0;
        Matcher matcher = QUOTED.matcher(statement);
        while (matcher.find()) {
            inside += matcher.group().length() - matcher.group().replace("?", "").length();
        }
        return inside;
    }

    // --- Boundaries and refusals ---------------------------------------------------------------------

    @Test
    void anEmptySamplePlansNothingAndNoKeysAtAllAreRefused() {
        assertEquals(List.of(), plans(sample(List.of())),
                "TP §9.3: a sample with no keys is a fact validation reports, not a statement to send");
        assertThrows(NullPointerException.class, () -> PAIR.target().samplingLookupPlan(null),
                "ADR-0008 §Ownership: a missing argument is a caller bug, not an empty answer");
        assertThrows(IllegalArgumentException.class,
                () -> new SamplingLookupKeys(TABLE, List.of(), List.of()),
                "TP §9.3: a lookup compares key components, so there has to be at least one");
    }

    /**
     * ADR-0028 again, from the other side: the statement projects a count and no column, so there is no
     * customer value for a caller to persist, log or compare by accident. Every value semantics rule of
     * TP §9.3 is {@code validation}'s ({@code validation} obligation 28).
     */
    @Test
    void theLookupReadsACountAndNoCustomerValue() {
        SqlPlan plan = plans(sample(List.of(key(new SqlValue.Int64(7), new SqlValue.Text("a"))))).get(0);
        String sql = statement(plan).sql();

        assertTrue(sql.startsWith("SELECT count(*) AS "),
                "ADR-0028 §No data values: a lookup counts matches and selects no column — " + sql);
        assertFalse(sql.contains("SELECT *"), "ADR-0028 §No data values: no row is fetched — " + sql);
        assertEquals(1, plan.resultSchema().columns().size(),
                "validation obligation 27: the one fact a lookup answers is how many rows the key matched");
    }
}
