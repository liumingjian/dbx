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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code target.catalogReadPlan} and {@code target.normalizeCatalog} (obligation 26): everything structural
 * proof compares, read back from the PostgreSQL catalogs (TP §7.4; ADR-0011 §DDL and structural proof;
 * ADR-0023). The dialect compares nothing here — {@code prove} is {@code contract}'s ({@code contract.md}
 * obligation 20).
 *
 * <p>That the query returns what the normalisation parses is <strong>not provable at L1</strong>: it needs a
 * real PostgreSQL 15 and is owned by {@code contract} slice 8 at L2, through {@code gateway}
 * ({@code dialect.md} §Verification). What is proven here is the plan's shape, its exact text, that no
 * coordinate ever becomes SQL text, and that the facts round-trip from typed rows.
 */
class TargetCatalogContractTest {

    private static final TargetTableCoordinate ORDERS = coordinate("shop", "orders");
    private static final TargetTableCoordinate HOSTILE_TABLE = coordinate("客户", "order's");
    private static final List<TargetTableCoordinate> REPRESENTATIVE = List.of(ORDERS, HOSTILE_TABLE);

    /** The typed result the plan declares, written from TP §7.4's fact list rather than from the builder. */
    private static final List<ResultSchema.Column> EXPECTED_COLUMNS = List.of(
            new ResultSchema.Column("fact", "text", Nullability.NOT_NULL),
            new ResultSchema.Column("fact_order", "bigint", Nullability.NOT_NULL),
            new ResultSchema.Column("table_schema", "text", Nullability.NOT_NULL),
            new ResultSchema.Column("table_name", "text", Nullability.NOT_NULL),
            new ResultSchema.Column("relkind", "text", Nullability.NULLABLE),
            new ResultSchema.Column("pg_class_oid", "bigint", Nullability.NULLABLE),
            new ResultSchema.Column("column_name", "text", Nullability.NULLABLE),
            new ResultSchema.Column("catalog_type", "text", Nullability.NULLABLE),
            new ResultSchema.Column("not_null", "boolean", Nullability.NULLABLE),
            new ResultSchema.Column("identity", "text", Nullability.NULLABLE),
            new ResultSchema.Column("column_default", "text", Nullability.NULLABLE),
            new ResultSchema.Column("key_column_name", "text", Nullability.NULLABLE),
            new ResultSchema.Column("check_column_name", "text", Nullability.NULLABLE),
            new ResultSchema.Column("check_definition", "text", Nullability.NULLABLE),
            new ResultSchema.Column("sequence_schema", "text", Nullability.NULLABLE),
            new ResultSchema.Column("sequence_name", "text", Nullability.NULLABLE),
            new ResultSchema.Column("sequence_owner_column", "text", Nullability.NULLABLE),
            new ResultSchema.Column("sequence_data_type", "text", Nullability.NULLABLE));

    private static final ResultSchema SCHEMA =
            new ResultSchema(EXPECTED_COLUMNS, ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS);

    /** Written by hand from TP §7.4 and ticket #139, not copied from the implementation's output. */
    private static final String EXPECTED_READ = """
            SELECT 'TABLE'::text AS fact, 0::bigint AS fact_order, n.nspname::text AS table_schema, \
            c.relname::text AS table_name, c.relkind::text AS relkind, c.oid::bigint AS pg_class_oid, NULL::text \
            AS column_name, NULL::text AS catalog_type, NULL::boolean AS not_null, NULL::text AS identity, \
            NULL::text AS column_default, NULL::text AS key_column_name, NULL::text AS check_column_name, \
            NULL::text AS check_definition, NULL::text AS sequence_schema, NULL::text AS sequence_name, \
            NULL::text AS sequence_owner_column, NULL::text AS sequence_data_type FROM pg_catalog.pg_class c \
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE ((n.nspname = ? AND c.relname = ?) OR \
            (n.nspname = ? AND c.relname = ?)) UNION ALL SELECT 'COLUMN'::text, a.attnum::bigint, \
            n.nspname::text, c.relname::text, NULL::text, NULL::bigint, a.attname::text, \
            pg_catalog.format_type(a.atttypid, a.atttypmod), a.attnotnull, a.attidentity::text, \
            pg_catalog.pg_get_expr(d.adbin, d.adrelid), NULL::text, NULL::text, NULL::text, NULL::text, \
            NULL::text, NULL::text, NULL::text FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON \
            n.oid = c.relnamespace JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid LEFT JOIN \
            pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum WHERE ((n.nspname = ? AND \
            c.relname = ?) OR (n.nspname = ? AND c.relname = ?)) AND a.attnum > 0 AND NOT a.attisdropped UNION \
            ALL SELECT 'PRIMARY_KEY'::text, kc.ord::bigint, n.nspname::text, c.relname::text, NULL::text, \
            NULL::bigint, NULL::text, NULL::text, NULL::boolean, NULL::text, NULL::text, a.attname::text, \
            NULL::text, NULL::text, NULL::text, NULL::text, NULL::text, NULL::text FROM pg_catalog.pg_class c \
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace JOIN pg_catalog.pg_constraint k ON \
            k.conrelid = c.oid AND k.contype = 'p' JOIN pg_catalog.pg_index i ON i.indexrelid = k.conindid JOIN \
            LATERAL unnest(k.conkey) WITH ORDINALITY AS kc(attnum, ord) ON true JOIN pg_catalog.pg_attribute a \
            ON a.attrelid = c.oid AND a.attnum = kc.attnum WHERE ((n.nspname = ? AND c.relname = ?) OR \
            (n.nspname = ? AND c.relname = ?)) UNION ALL SELECT 'ENUM_CHECK'::text, a.attnum::bigint, \
            n.nspname::text, c.relname::text, NULL::text, NULL::bigint, NULL::text, NULL::text, NULL::boolean, \
            NULL::text, NULL::text, NULL::text, a.attname::text, pg_catalog.pg_get_constraintdef(k.oid), \
            NULL::text, NULL::text, NULL::text, NULL::text FROM pg_catalog.pg_class c JOIN \
            pg_catalog.pg_namespace n ON n.oid = c.relnamespace JOIN pg_catalog.pg_constraint k ON k.conrelid = \
            c.oid AND k.contype = 'c' JOIN LATERAL unnest(k.conkey) WITH ORDINALITY AS kc(attnum, ord) ON true \
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = kc.attnum WHERE ((n.nspname = ? \
            AND c.relname = ?) OR (n.nspname = ? AND c.relname = ?)) UNION ALL SELECT 'SEQUENCE'::text, \
            dep.refobjsubid::bigint, n.nspname::text, c.relname::text, NULL::text, NULL::bigint, NULL::text, \
            NULL::text, NULL::boolean, NULL::text, NULL::text, NULL::text, NULL::text, NULL::text, \
            sn.nspname::text, s.relname::text, a.attname::text, pg_catalog.format_type(q.seqtypid, NULL) FROM \
            pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace JOIN \
            pg_catalog.pg_depend dep ON dep.refclassid = 'pg_catalog.pg_class'::regclass AND dep.refobjid = \
            c.oid AND dep.classid = 'pg_catalog.pg_class'::regclass AND dep.deptype = 'a' JOIN \
            pg_catalog.pg_class s ON s.oid = dep.objid AND s.relkind = 'S' JOIN pg_catalog.pg_namespace sn ON \
            sn.oid = s.relnamespace JOIN pg_catalog.pg_sequence q ON q.seqrelid = s.oid JOIN \
            pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = dep.refobjsubid WHERE ((n.nspname = ? \
            AND c.relname = ?) OR (n.nspname = ? AND c.relname = ?)) ORDER BY fact, table_schema, table_name, \
            fact_order, check_definition, sequence_name""";

    // --- Plan shape and exact text (ADR-0008 §Plans; TP §7.4) ---------------------------------------

    @Test
    void theCatalogReadPlanHasTheTargetCatalogReadShape() {
        SqlPlan plan = PAIR.target().catalogReadPlan(REPRESENTATIVE);

        assertEquals(OperationKind.TARGET_CATALOG_READ, plan.operationKind(),
                "obligation 26: one TARGET_CATALOG_READ plan for the whole coordinate list");
        assertEquals(1, plan.statements().size(),
                "obligation 26: one plan and one statement for the whole list, not one read per table");
        assertEquals(TimeoutClass.CATALOG_READ, plan.timeoutClass(),
                "ADR-0008 §Plans: a catalog read runs under the catalog-read timeout class");
        assertEquals(Set.of(RequiredPrivilege.TARGET_READ_CATALOG), plan.requiredPrivileges(),
                "ADR-0006: reading the catalogs needs catalog read and nothing more");
        assertEquals(EvidencePolicy.STATEMENT_AND_RESULT, plan.evidencePolicy(),
                "ADR-0011 §DDL and structural proof: the catalog facts are the evidence a difference is shown as");
        assertEquals(SCHEMA, plan.resultSchema(),
                "ADR-0008 §Plans: the result is typed column by column, with ANY_NUMBER_OF_ROWS rows");
    }

    @Test
    void theStatementIsPinnedForARepresentativeCoordinateList() {
        ParameterizedStatement statement = PAIR.target().catalogReadPlan(REPRESENTATIVE).statements().get(0);

        assertEquals(EXPECTED_READ, statement.sql(),
                "TP §7.4: relkind and the pg_class OID, columns in attnum order with format_type, attnotnull, "
                        + "attidentity and the pg_attrdef default, the primary key's column order, the ENUM CHECK "
                        + "definitions and the owned sequence with its ownership");
        assertEquals(List.of(
                        new SqlValue.Text("shop"), new SqlValue.Text("orders"),
                        new SqlValue.Text("客户"), new SqlValue.Text("order's"),
                        new SqlValue.Text("shop"), new SqlValue.Text("orders"),
                        new SqlValue.Text("客户"), new SqlValue.Text("order's"),
                        new SqlValue.Text("shop"), new SqlValue.Text("orders"),
                        new SqlValue.Text("客户"), new SqlValue.Text("order's"),
                        new SqlValue.Text("shop"), new SqlValue.Text("orders"),
                        new SqlValue.Text("客户"), new SqlValue.Text("order's"),
                        new SqlValue.Text("shop"), new SqlValue.Text("orders"),
                        new SqlValue.Text("客户"), new SqlValue.Text("order's")),
                statement.parameters(),
                "obligation 26: every coordinate is a bound value, one pair per coordinate per fact branch");
    }

    @Test
    void everyTp74FactIsNamedByTheStatement() {
        String sql = sql(PAIR.target().catalogReadPlan(REPRESENTATIVE));
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("the object kind", "c.relkind");
        facts.put("the pg_class OID (ADR-0023)", "c.oid");
        facts.put("columns in attnum order", "a.attnum");
        facts.put("the column's exact type", "pg_catalog.format_type(a.atttypid, a.atttypmod)");
        facts.put("nullability", "a.attnotnull");
        facts.put("identity", "a.attidentity");
        facts.put("the default expression", "pg_catalog.pg_get_expr(d.adbin, d.adrelid)");
        facts.put("the primary key from pg_constraint", "k.contype = 'p'");
        facts.put("the primary key's index", "pg_catalog.pg_index");
        facts.put("the primary key's column order", "unnest(k.conkey) WITH ORDINALITY");
        facts.put("the ENUM CHECK members", "pg_catalog.pg_get_constraintdef(k.oid)");
        facts.put("sequence ownership from pg_depend", "pg_catalog.pg_depend");
        facts.put("the owned sequence from pg_sequence", "pg_catalog.pg_sequence");
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            assertTrue(sql.contains(fact.getValue()),
                    "TP §7.4: structural proof compares " + fact.getKey() + ", so the read must select it");
        }
    }

    // --- No identifier is quoted into a catalog query (obligation 26; ADR-0008 §Plans) ---------------

    private static final List<String> HOSTILE = List.of(
            "it's", "back\\slash", "$$dollar$$", "`tick`", "new\nline", "cr\rtab\t", "a\"b", "?", "soh",
            "del", "客户订单", "emoji😀", "x\"; DROP TABLE t; --", "'); DROP TABLE t; --", "a''b", "e\\'",
            "a".repeat(63), "订".repeat(21), "订".repeat(21) + "a", "a".repeat(64), " padded ");

    @Test
    void noCoordinateEverBecomesSqlTextHoweverHostileItIs() {
        String benign = sql(PAIR.target().catalogReadPlan(List.of(ORDERS)));
        assertFalse(benign.contains("\""),
                "obligation 26: a catalog query quotes no identifier at all, so it holds no double quote: " + benign);
        for (String hostile : HOSTILE) {
            TargetTableCoordinate coordinate = coordinate(hostile, hostile + "t");
            SqlPlan plan = PAIR.target().catalogReadPlan(List.of(coordinate));
            ParameterizedStatement statement = plan.statements().get(0);

            assertEquals(benign, statement.sql(),
                    "obligation 26: schema and table names are bound values, so a hostile name cannot change one "
                            + "character of the statement: " + hostile);
            // "?" is the placeholder itself, so only a name the catalog query does not already spell can be
            // looked for: for every other name, absence from the text is the ruling being defended.
            if (!benign.contains(hostile)) {
                assertFalse(statement.sql().contains(hostile),
                        "obligation 26: the coordinate appears only as a bound value, never as text: " + hostile);
            }
            assertEquals(10, statement.parameters().size(),
                    "ADR-0008 §Plans: one bound pair per coordinate per fact branch, five branches");
            assertEquals(statement.sql().chars().filter(character -> character == '?').count(),
                    statement.parameters().size(),
                    "ADR-0008 §Plans: the parameter count equals the placeholder count, whatever the name holds");
            assertEquals(List.of(new SqlValue.Text(hostile), new SqlValue.Text(hostile + "t")),
                    statement.parameters().subList(0, 2),
                    "TP §7.1: the name is bound exactly as approved, neither trimmed nor escaped");
        }
    }

    @Test
    void anIdentifierBeyondPostgresqlsLimitIsStillOnlyABoundValue() {
        String bytes64 = "订".repeat(21) + "a";
        assertEquals(64, bytes64.getBytes(StandardCharsets.UTF_8).length,
                "TP §7.1: the fixture name is one byte past PostgreSQL's 63-byte identifier limit");

        SqlPlan plan = PAIR.target().catalogReadPlan(List.of(coordinate(bytes64, bytes64)));

        assertEquals(sql(PAIR.target().catalogReadPlan(List.of(ORDERS))), sql(plan),
                "TP §7.1: the 63-byte limit governs identifiers in SQL text; a bound value is not an identifier, "
                        + "so an overlong name is read back and reported as a difference rather than refused here");
    }

    // --- Fingerprints (ADR-0008 §Plans) --------------------------------------------------------------

    @Test
    void distinctCoordinateListsGiveDistinctFingerprints() {
        SqlPlan base = PAIR.target().catalogReadPlan(REPRESENTATIVE);
        Map<String, SqlPlan> variants = new TreeMap<>();
        variants.put("schema", plan(coordinate("shop2", "orders"), HOSTILE_TABLE));
        variants.put("table name", plan(ORDERS, coordinate("客户", "order")));
        variants.put("name case", plan(coordinate("Shop", "orders"), HOSTILE_TABLE));
        variants.put("a table added", plan(ORDERS, HOSTILE_TABLE, coordinate("shop", "items")));
        variants.put("a table removed", plan(ORDERS));
        variants.put("coordinate order", plan(HOSTILE_TABLE, ORDERS));

        assertEquals(base.fingerprint(), PAIR.target().catalogReadPlan(REPRESENTATIVE).fingerprint(),
                "ADR-0008 §Plans: an equal coordinate list is an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only the " + variant.getKey() + " must change the fingerprint");
        }
    }

    /**
     * Pinned from an independent Python model of the documented {@code SqlPlan/1} encoding (length-prefixed
     * UTF-8, big-endian ints, SHA-256), fed with {@link #EXPECTED_READ}, the bound coordinates and
     * {@link #EXPECTED_COLUMNS} — not with {@code plan.fingerprint()}. The script is quoted on ticket #139.
     */
    @Test
    void theRepresentativeFingerprintIsPinnedFromTheIndependentModel() {
        assertEquals("395f90281831703578ab5e13bde6092ac1cb1e1f8695ee18fa97ba70bc70c109",
                PAIR.target().catalogReadPlan(REPRESENTATIVE).fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a catalog read's fingerprint must not depend on the JVM, the machine or the run");
    }

    // --- A coordinate list the read cannot serve (obligation 26) ------------------------------------

    @Test
    void anEmptyOrRepeatedCoordinateListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PAIR.target().catalogReadPlan(List.of()),
                "TP §7.4: a catalog read names the tables proof compares; an empty list is a caller bug");
        assertThrows(IllegalArgumentException.class,
                () -> PAIR.target().catalogReadPlan(List.of(ORDERS, HOSTILE_TABLE, ORDERS)),
                "TP §7.4: a repeated coordinate would prove one table against two row sets");
    }

    // --- Normalisation: every TP §7.4 fact round-trips (obligation 26) ------------------------------

    @Test
    void everyFactRoundTripsFromTypedRows() {
        List<TargetTableFacts> facts = PAIR.target().normalizeCatalog(List.of(ORDERS), ordersRows());

        assertEquals(1, facts.size(), "obligation 26: one TargetTableFacts per table read");
        TargetTableFacts orders = facts.get(0);
        assertEquals(ORDERS, orders.table(), "TP §7.4: the facts name the table they describe");
        assertEquals("r", orders.relkind(), "TP §7.4: structural proof compares the object kind");
        assertEquals(16384L, orders.pgClassOid(), "ADR-0023: the target generation records the pg_class OID");
        assertEquals(List.of(new TargetIdentifier("id")), orders.primaryKey(),
                "ADR-0011 §DDL: proof requires the primary key's column order");
        assertEquals(List.of(new TargetSequenceFacts(coordinate("shop", "orders_id_seq"),
                        new TargetIdentifier("id"), "bigint")), orders.ownedSequences(),
                "TP §7.4: the owned sequence and its ownership come from pg_depend and pg_sequence");
        assertEquals(List.of("id", "status", "total", "created_at", "tags", "always"),
                orders.columns().stream().map(column -> column.name().name()).toList(),
                "TP §7.4: columns come back in attnum order");

        TargetColumnFacts id = orders.columns().get(0);
        assertEquals("numeric(20,0)", id.catalogType(), "TP §7.4: the catalog's own format_type text is kept");
        assertEquals(Optional.of(new TargetType(TargetTypeName.NUMERIC, List.of(20, 0))), id.type(),
                "TP §6.2: DBX can name numeric(20,0), so the parsed type is present");
        assertEquals(Nullability.NOT_NULL, id.nullability(), "TP §7.4: attnotnull is nullability");
        assertEquals(Optional.of("nextval('shop.orders_id_seq'::regclass)"), id.defaultExpression(),
                "TP §7.4: the pg_attrdef default expression is kept as the catalog renders it");
        assertEquals(Optional.of(new IdentityIntent.OwnedSequence(new TargetIdentifier("orders_id_seq"))),
                id.identity(), "TP §7.3: a column that owns a sequence carries the owned-sequence identity");

        TargetColumnFacts status = orders.columns().get(1);
        assertEquals(List.of("new", "paid"), status.enumMembers(),
                "TP §6.3: the ENUM CHECK's members are read back from pg_constraint, in order");
        assertEquals(1, status.checkDefinitions().size(),
                "TP §7.4: the constraint's own definition is kept alongside its members");
        assertEquals(Optional.of(new TargetType(TargetTypeName.TEXT, List.of())), status.type(),
                "TP §6.3: an ENUM maps to text, which DBX can name");

        TargetColumnFacts total = orders.columns().get(2);
        assertEquals(Nullability.NULLABLE, total.nullability(), "TP §7.4: attnotnull false is NULLABLE");
        assertEquals(Optional.empty(), total.defaultExpression(),
                "TP §7.4: a column with no pg_attrdef row has no default");
        assertEquals(List.of(), total.enumMembers(), "TP §6.3: a column with no CHECK has no ENUM members");

        assertEquals(Optional.of(new TargetType(TargetTypeName.TIMESTAMP, List.of(3))),
                orders.columns().get(3).type(),
                "TP §6.4: timestamp(3) without time zone is the mapped temporal type");
        assertEquals(Optional.of(IdentityIntent.NONE), orders.columns().get(3).identity(),
                "TP §7.3: an empty attidentity on a column owning no sequence is no identity");
    }

    @Test
    void aCatalogTypeDbxCannotNameStaysAsTextInsteadOfThrowing() {
        TargetColumnFacts tags = PAIR.target().normalizeCatalog(List.of(ORDERS), ordersRows()).get(0).columns().get(4);

        assertEquals("integer[]", tags.catalogType(),
                "TP §7.4: a catalog can hold what a contract cannot express, so the type text survives");
        assertEquals(Optional.empty(), tags.type(),
                "spec #134: a type DBX cannot name is a difference for contract.prove to report, never an exception");
    }

    @Test
    void anIdentityDbxCannotNameStaysAsTextInsteadOfThrowing() {
        TargetColumnFacts always =
                PAIR.target().normalizeCatalog(List.of(ORDERS), ordersRows()).get(0).columns().get(5);

        assertEquals("a", always.identityText(),
                "TP §7.4: GENERATED ALWAYS is a catalog state, so attidentity survives as the catalog spells it");
        assertEquals(Optional.empty(), always.identity(),
                "ADR-0011 §DDL: the contract expresses GENERATED BY DEFAULT only, so ALWAYS is a difference");
    }

    @Test
    void everyTargetTypeIsNamedBackFromItsFormatTypeRendering() {
        Map<String, TargetType> expected = new LinkedHashMap<>();
        expected.put("smallint", new TargetType(TargetTypeName.SMALLINT, List.of()));
        expected.put("integer", new TargetType(TargetTypeName.INTEGER, List.of()));
        expected.put("bigint", new TargetType(TargetTypeName.BIGINT, List.of()));
        expected.put("numeric(10,2)", new TargetType(TargetTypeName.NUMERIC, List.of(10, 2)));
        expected.put("real", new TargetType(TargetTypeName.REAL, List.of()));
        expected.put("double precision", new TargetType(TargetTypeName.DOUBLE_PRECISION, List.of()));
        expected.put("boolean", new TargetType(TargetTypeName.BOOLEAN, List.of()));
        expected.put("character(8)", new TargetType(TargetTypeName.CHAR, List.of(8)));
        expected.put("character varying(64)", new TargetType(TargetTypeName.VARCHAR, List.of(64)));
        expected.put("text", new TargetType(TargetTypeName.TEXT, List.of()));
        expected.put("bytea", new TargetType(TargetTypeName.BYTEA, List.of()));
        expected.put("json", new TargetType(TargetTypeName.JSON, List.of()));
        expected.put("jsonb", new TargetType(TargetTypeName.JSONB, List.of()));
        expected.put("date", new TargetType(TargetTypeName.DATE, List.of()));
        expected.put("time(3) without time zone", new TargetType(TargetTypeName.TIME, List.of(3)));
        expected.put("timestamp(3) without time zone", new TargetType(TargetTypeName.TIMESTAMP, List.of(3)));
        expected.put("timestamp(3) with time zone", new TargetType(TargetTypeName.TIMESTAMPTZ, List.of(3)));
        assertEquals(Set.of(TargetTypeName.values()),
                expected.values().stream().map(TargetType::name).collect(Collectors.toSet()),
                "TP §6.2–6.4: every target type slice 3 can map to is named back from its catalog rendering");

        for (Map.Entry<String, TargetType> entry : expected.entrySet()) {
            assertEquals(Optional.of(entry.getValue()), typeOf(entry.getKey()),
                    "TP §6.2–6.4: " + entry.getKey() + " is the catalog rendering of " + entry.getValue());
        }
        for (String unnameable : List.of("numeric", "timestamp without time zone", "time without time zone",
                "integer[]", "hstore", "public.money_domain", "character varying", "interval", "uuid", "xml")) {
            assertEquals(Optional.empty(), typeOf(unnameable),
                    "spec #134: " + unnameable + " is outside TP §6.2–6.4, so it stays as text and is a difference");
        }
    }

    // --- A broken read throws naming the coordinate (obligation 26) ----------------------------------

    @Test
    void aRowForATableNobodyAskedForThrowsNamingTheCoordinate() {
        ResultRows rows = rows(tableRow(coordinate("shop", "other"), "r", 16400));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PAIR.target().normalizeCatalog(List.of(ORDERS), rows),
                "obligation 26: a row set holding a table nobody asked for is a broken read, not a difference");
        assertTrue(failure.getMessage().contains("\"shop\".\"other\""),
                "obligation 26: the refusal names the coordinate: " + failure.getMessage());
    }

    @Test
    void aRowWhoseTableHasNoTableRowThrowsNamingTheCoordinate() {
        List<ResultRows.Row> broken = new ArrayList<>(ordersRows().rows());
        broken.add(columnRow(coordinate("shop", "absent"), 1, "id", "bigint", true, "", Optional.empty()));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> PAIR.target().normalizeCatalog(List.of(ORDERS, coordinate("shop", "absent")),
                        new ResultRows(SCHEMA, broken)),
                "obligation 26: a column whose table is absent is a broken read, not a difference");
        assertTrue(failure.getMessage().contains("\"shop\".\"absent\""),
                "obligation 26: the refusal names the coordinate: " + failure.getMessage());
    }

    @Test
    void twoTableRowsForOneCoordinateAreABrokenRead() {
        List<ResultRows.Row> doubled = new ArrayList<>(ordersRows().rows());
        doubled.add(tableRow(ORDERS, "r", 16384));

        assertThrows(IllegalArgumentException.class,
                () -> PAIR.target().normalizeCatalog(List.of(ORDERS), new ResultRows(SCHEMA, doubled)),
                "obligation 26: one table cannot have two TABLE rows");
    }

    @Test
    void rowsReadByAnotherPlanAreRefused() {
        ResultRows foreign = new ResultRows(
                new ResultSchema(List.of(new ResultSchema.Column("oid", "oid", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.ANY_NUMBER_OF_ROWS),
                List.of(new ResultRows.Row(List.of(new SqlValue.Int64(1)))));

        assertThrows(IllegalArgumentException.class,
                () -> PAIR.target().normalizeCatalog(List.of(ORDERS), foreign),
                "obligation 26: rows whose schema is not the plan's were not read by target.catalogReadPlan");
    }

    // --- Row order and grading (obligation 26; contract.md obligation 20) ----------------------------

    @Test
    void tablesComeBackInRowOrderAndNothingIsGraded() {
        TargetTableCoordinate second = coordinate("客户", "order's");
        List<ResultRows.Row> forward = new ArrayList<>();
        forward.add(tableRow(ORDERS, "r", 16384));
        forward.add(tableRow(second, "v", 16500));
        forward.add(columnRow(second, 1, "id", "money", false, "", Optional.empty()));
        List<ResultRows.Row> reversed = new ArrayList<>(List.of(forward.get(1), forward.get(0), forward.get(2)));
        List<TargetTableCoordinate> asked = List.of(ORDERS, second);

        assertEquals(List.of(ORDERS, second),
                PAIR.target().normalizeCatalog(asked, new ResultRows(SCHEMA, forward)).stream()
                        .map(TargetTableFacts::table).toList(),
                "obligation 26: the facts come back in the order the server returned the rows");
        List<TargetTableFacts> backwards = PAIR.target().normalizeCatalog(asked, new ResultRows(SCHEMA, reversed));
        assertEquals(List.of(second, ORDERS), backwards.stream().map(TargetTableFacts::table).toList(),
                "obligation 26: row order, not the coordinate order, decides the order of the facts");
        assertEquals("v", backwards.get(0).relkind(),
                "contract.md obligation 20: a view where a table was created is a difference for prove to report, "
                        + "so the dialect returns relkind unchanged and grades nothing");
        assertEquals(Optional.empty(), backwards.get(0).columns().get(0).type(),
                "contract.md obligation 20: an unmappable column type is returned as read, never judged here");
    }

    // --- Fixtures ------------------------------------------------------------------------------------

    private static TargetTableCoordinate coordinate(String schema, String name) {
        return new TargetTableCoordinate(new TargetIdentifier(schema), new TargetIdentifier(name));
    }

    private static SqlPlan plan(TargetTableCoordinate... coordinates) {
        return PAIR.target().catalogReadPlan(List.of(coordinates));
    }

    private static String sql(SqlPlan plan) {
        return plan.statements().get(0).sql();
    }

    /** The parsed type behind a {@code format_type} rendering, reached only through the public entry point. */
    private static Optional<TargetType> typeOf(String catalogType) {
        ResultRows rows = rows(tableRow(ORDERS, "r", 16384),
                columnRow(ORDERS, 1, "c", catalogType, false, "", Optional.empty()));
        return PAIR.target().normalizeCatalog(List.of(ORDERS), rows).get(0).columns().get(0).type();
    }

    /**
     * One table carrying every TP §7.4 fact: an owned sequence, an ENUM {@code CHECK}, a nullable column with
     * no default, a temporal default, a type DBX cannot name and an identity DBX cannot name.
     */
    private static ResultRows ordersRows() {
        return rows(
                tableRow(ORDERS, "r", 16384),
                columnRow(ORDERS, 1, "id", "numeric(20,0)", true, "",
                        Optional.of("nextval('shop.orders_id_seq'::regclass)")),
                columnRow(ORDERS, 2, "status", "text", true, "", Optional.of("'new'::text")),
                columnRow(ORDERS, 3, "total", "numeric(10,2)", false, "", Optional.empty()),
                columnRow(ORDERS, 4, "created_at", "timestamp(3) without time zone", true, "",
                        Optional.of("LOCALTIMESTAMP(3)")),
                columnRow(ORDERS, 5, "tags", "integer[]", false, "", Optional.empty()),
                columnRow(ORDERS, 6, "always", "bigint", true, "a", Optional.empty()),
                keyRow(ORDERS, 1, "id"),
                checkRow(ORDERS, 2, "status",
                        "CHECK (((status)::text = ANY ((ARRAY['new'::character varying, "
                                + "'paid'::character varying])::text[])))"),
                sequenceRow(ORDERS, 1, "shop", "orders_id_seq", "id", "bigint"));
    }

    private static ResultRows rows(ResultRows.Row... rows) {
        return new ResultRows(SCHEMA, List.of(rows));
    }

    private static ResultRows.Row tableRow(TargetTableCoordinate table, String relkind, long oid) {
        return row("TABLE", 0, table, Map.of("relkind", new SqlValue.Text(relkind),
                "pg_class_oid", new SqlValue.Int64(oid)));
    }

    private static ResultRows.Row columnRow(TargetTableCoordinate table, long attnum, String name, String type,
            boolean notNull, String identity, Optional<String> columnDefault) {
        Map<String, SqlValue> values = new LinkedHashMap<>();
        values.put("column_name", new SqlValue.Text(name));
        values.put("catalog_type", new SqlValue.Text(type));
        values.put("not_null", new SqlValue.Bool(notNull));
        values.put("identity", new SqlValue.Text(identity));
        values.put("column_default", columnDefault.<SqlValue>map(SqlValue.Text::new)
                .orElse(new SqlValue.Null(SqlValue.Type.TEXT)));
        return row("COLUMN", attnum, table, values);
    }

    private static ResultRows.Row keyRow(TargetTableCoordinate table, long position, String column) {
        return row("PRIMARY_KEY", position, table, Map.of("key_column_name", new SqlValue.Text(column)));
    }

    private static ResultRows.Row checkRow(TargetTableCoordinate table, long attnum, String column,
            String definition) {
        return row("ENUM_CHECK", attnum, table, Map.of("check_column_name", new SqlValue.Text(column),
                "check_definition", new SqlValue.Text(definition)));
    }

    private static ResultRows.Row sequenceRow(TargetTableCoordinate table, long attnum, String schema, String name,
            String ownerColumn, String dataType) {
        Map<String, SqlValue> values = new LinkedHashMap<>();
        values.put("sequence_schema", new SqlValue.Text(schema));
        values.put("sequence_name", new SqlValue.Text(name));
        values.put("sequence_owner_column", new SqlValue.Text(ownerColumn));
        values.put("sequence_data_type", new SqlValue.Text(dataType));
        return row("SEQUENCE", attnum, table, values);
    }

    /** One row of the plan's own result schema: the fact's own columns filled, every other column typed NULL. */
    private static ResultRows.Row row(String fact, long factOrder, TargetTableCoordinate table,
            Map<String, SqlValue> owned) {
        Map<String, SqlValue> values = new LinkedHashMap<>(owned);
        values.put("fact", new SqlValue.Text(fact));
        values.put("fact_order", new SqlValue.Int64(factOrder));
        values.put("table_schema", new SqlValue.Text(table.schema().name()));
        values.put("table_name", new SqlValue.Text(table.name().name()));
        return new ResultRows.Row(EXPECTED_COLUMNS.stream()
                .map(column -> values.getOrDefault(column.label(), typedNull(column.databaseType())))
                .toList());
    }

    private static SqlValue typedNull(String databaseType) {
        return new SqlValue.Null(switch (databaseType) {
            case "text" -> SqlValue.Type.TEXT;
            case "bigint" -> SqlValue.Type.INT64;
            case "boolean" -> SqlValue.Type.BOOLEAN;
            default -> throw new AssertionError("ADR-0008 §Plans: the catalog read declares only text, bigint and "
                    + "boolean columns, not " + databaseType);
        });
    }
}
