package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code source.preflightScanPlan} (obligations 16 and 17; ticket #135): one table's whole preflight as one
 * bounded aggregate query (ADR-0003 ¶2; TP §6.6; {@code preflight} obligations 5–7), reached only through
 * {@code DialectCatalog.compileTime().select(...)}.
 *
 * <p>Not provable at L1 and deliberately not approximated here: that the rendered SQL runs, and that the
 * component-based zero-date expression behaves under {@code NO_ZERO_DATE}/{@code NO_ZERO_IN_DATE} sql_mode.
 * {@code docs/spec/dialect.md} §Verification gives both to L2/L3 — {@code contract} slice 8 through
 * {@code gateway}, then the pair certification run.
 */
class PreflightScanContractTest {

    private static final SourceDialect SOURCE = MappingCase.PAIR.source();
    private static final TableCoordinate ORDERS = new TableCoordinate("shop", "订单");

    // --- Fixture: a table as information_schema reports it ----------------------------------------

    private record Col(String name, String columnType, Nullability nullability) {
    }

    private static Col notNull(String name, String columnType) {
        return new Col(name, columnType, Nullability.NOT_NULL);
    }

    private static Col nullable(String name, String columnType) {
        return new Col(name, columnType, Nullability.NULLABLE);
    }

    private static ColumnCoordinate at(TableCoordinate table, String column) {
        return new ColumnCoordinate(table.database(), table.table(), column);
    }

    private static ColumnCoordinate at(String column) {
        return at(ORDERS, column);
    }

    private static ApprovedColumn approved(TableCoordinate table, String column) {
        return new ApprovedColumn(at(table, column), new TargetIdentifier(column));
    }

    private static ApprovedColumn approved(String column) {
        return approved(ORDERS, column);
    }

    private static PreflightObligation obligation(TableCoordinate table, String column, RequiredPreflight preflight) {
        return new PreflightObligation(at(table, column), preflight);
    }

    private static PreflightObligation obligation(String column, RequiredPreflight preflight) {
        return obligation(ORDERS, column, preflight);
    }

    private static SourceIndex unique(TableCoordinate table, String name, String column) {
        return new SourceIndex(name, true, true, SourceIndex.IndexType.BTREE, List.of(new SourceIndex.KeyPart(
                1, new SourceIndex.Subject.Column(at(table, column)), OptionalLong.empty(),
                SourceIndex.Direction.ASCENDING)));
    }

    private static SourceTableMetadata table(TableCoordinate coordinate, List<Col> cols, SourceIndex... indexes) {
        List<SourceColumn> columns = new ArrayList<>();
        List<ColumnComment> comments = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Col col = cols.get(i);
            String dataType = col.columnType().split("[( ]")[0];
            boolean integer = dataType.matches("(tiny|small|medium|big)?int");
            columns.add(new SourceColumn(at(coordinate, col.name()), dataType, col.columnType(),
                    col.columnType().contains("unsigned"), OptionalLong.empty(), OptionalLong.empty(),
                    integer ? OptionalInt.of(20) : OptionalInt.empty(), integer ? OptionalInt.of(0) : OptionalInt.empty(),
                    OptionalInt.empty(), Optional.empty(), Optional.empty(), col.nullability(), Optional.empty(), "",
                    i + 1));
            comments.add(new ColumnComment(at(coordinate, col.name()), ""));
        }
        return new SourceTableMetadata(coordinate, columns, comments, List.of(indexes), List.of(), "",
                Optional.empty(), new TableStatistics(OptionalLong.of(1000), OptionalLong.of(128),
                        OptionalLong.of(16384), OptionalLong.of(2048)));
    }

    /**
     * The representative table: every {@link RequiredPreflight} constant is obliged somewhere on it, one
     * approved column carries a hostile name, one column is pruned, and one keyset candidate ({@code code})
     * is not an approved column at all.
     */
    private static SourceTableMetadata orders() {
        return table(ORDERS, List.of(
                notNull("id", "bigint unsigned"),
                nullable("order`s", "varchar(64)"),
                nullable("payload", "longblob"),
                nullable("flag", "tinyint(1)"),
                nullable("total", "bigint unsigned"),
                nullable("at", "time(6)"),
                nullable("kind", "enum('a','b')"),
                nullable("created", "date"),
                nullable("secret", "varchar(64)"),
                notNull("code", "int")),
                unique(ORDERS, SourceIndex.PRIMARY, "id"), unique(ORDERS, "u_code", "code"));
    }

    /** Approved in this order; the ordinal in this list, one-based, is what every label carries. */
    private static final List<ApprovedColumn> APPROVED = List.of(
            approved("id"), approved("order`s"), approved("payload"), approved("flag"),
            approved("total"), approved("at"), approved("kind"), approved("created"));

    /** One table carrying many obligations, including both that plan no scan column. */
    private static final List<PreflightObligation> OBLIGATIONS = List.of(
            obligation("payload", RequiredPreflight.LARGE_RECORD_ENVELOPE),
            obligation("flag", RequiredPreflight.BOOLEAN_VALUES_ZERO_OR_ONE),
            obligation("total", RequiredPreflight.UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE),
            obligation("at", RequiredPreflight.TIME_WITHIN_DAY),
            obligation("kind", RequiredPreflight.ENUM_VALUE_DECLARED),
            obligation("created", RequiredPreflight.NO_ZERO_DATE),
            obligation("created", RequiredPreflight.ZERO_DATE_ROWS_COUNTED),
            obligation("id", RequiredPreflight.AUTO_INCREMENT_NEXT_VALUE_WITHIN_SIGNED_RANGE));

    private static SqlPlan plan() {
        return SOURCE.preflightScanPlan(orders(), APPROVED, OBLIGATIONS);
    }

    private static String sql(SqlPlan plan) {
        assertEquals(1, plan.statements().size(), "ADR-0003 ¶2: a table's whole preflight is one bounded query, "
                + "never one scan per column");
        return plan.statements().get(0).sql();
    }

    // --- Plan shape (ADR-0008 §Plans; ADR-0003 ¶2) -------------------------------------------------

    @Test
    void thePreflightScanIsOneExactScanNeedingOnlySelect() {
        SqlPlan plan = plan();

        assertEquals(OperationKind.SOURCE_PREFLIGHT_SCAN, plan.operationKind(), "obligation 16: one preflight scan");
        assertEquals(TimeoutClass.EXACT_SCAN, plan.timeoutClass(),
                "ADR-0003 ¶2: the envelope is an exact scan, never a sampled one");
        assertEquals(Set.of(RequiredPrivilege.SOURCE_SELECT), plan.requiredPrivileges(),
                "ADR-0006: reading rows needs SELECT and nothing more");
        assertEquals(EvidencePolicy.STATEMENT_AND_AGGREGATES, plan.evidencePolicy(),
                "ADR-0028: aggregates are evidence, row values never are");
        assertEquals(ResultSchema.Cardinality.EXACTLY_ONE_ROW, plan.resultSchema().cardinality(),
                "ADR-0003 ¶2: one aggregate query over one table returns exactly one row");
        assertEquals(List.of(), plan.statements().get(0).parameters(),
                "ADR-0008 §Plans: the scan binds no value — it reads columns, it does not compare against literals");
    }

    /** The typed result the scan returns, column by column, in plan order. */
    private static final List<ResultSchema.Column> EXPECTED_COLUMNS = List.of(
            aggregate("value_bytes_1", "bigint"), aggregate("value_bytes_2", "bigint"),
            aggregate("value_bytes_3", "bigint"), aggregate("value_bytes_4", "bigint"),
            aggregate("value_bytes_5", "bigint"), aggregate("value_bytes_6", "bigint"),
            aggregate("value_bytes_7", "bigint"), aggregate("value_bytes_8", "bigint"),
            aggregate("row_bytes", "bigint"),
            aggregate("boolean_domain_4", "bigint"),
            aggregate("unsigned_max_5", "decimal(20,0)"),
            aggregate("time_domain_6", "bigint"),
            aggregate("enum_sentinel_7", "bigint"),
            aggregate("zero_date_rows_8", "bigint"),
            aggregate("keyset_min_1", "decimal(20,0)"), aggregate("keyset_max_1", "decimal(20,0)"),
            aggregate("keyset_min_2", "decimal(20,0)"), aggregate("keyset_max_2", "decimal(20,0)"));

    private static ResultSchema.Column aggregate(String label, String databaseType) {
        return new ResultSchema.Column(label, databaseType, Nullability.NULLABLE);
    }

    @Test
    void theResultIsTypedColumnByColumnWithStableLabels() {
        assertEquals(new ResultSchema(EXPECTED_COLUMNS, ResultSchema.Cardinality.EXACTLY_ONE_ROW),
                plan().resultSchema(),
                "ADR-0008 §Plans: the result is typed column by column, and every label comes from the fixed "
                        + "scheme — kind plus a one-based ordinal — never from a column name");
        for (ResultSchema.Column column : plan().resultSchema().columns()) {
            assertTrue(column.label().matches("[a-z_]+(_[0-9]+)?"),
                    "obligation 16: a label is ASCII letters, underscores and digits, so it is legal everywhere "
                            + "a result column is addressed: " + column.label());
        }
    }

    @Test
    void everyAggregateIsNullableBecauseAnEmptyTableAnswersNull() {
        for (ResultSchema.Column column : plan().resultSchema().columns()) {
            assertEquals(Nullability.NULLABLE, column.nullability(),
                    "TP §6.6: an empty table gives NULL for every MAX, MIN and SUM, so no aggregate may be "
                            + "declared NOT NULL: " + column.label());
        }
        assertFalse(sql(plan()).contains("COUNT("),
                "TP §6.6: a count of offending rows is SUM(CASE … THEN 1 ELSE 0 END), so an empty table answers "
                        + "NULL rather than a nought that looks like a proof");
    }

    @Test
    void unsignedMaximaAndKeysetExtremaAreDeclaredWideEnoughNotToWrap() {
        Map<String, String> byLabel = new TreeMap<>();
        plan().resultSchema().columns().forEach(column -> byLabel.put(column.label(), column.databaseType()));

        for (String label : List.of("unsigned_max_5", "keyset_min_1", "keyset_max_1", "keyset_min_2", "keyset_max_2")) {
            assertEquals("decimal(20,0)", byLabel.get(label),
                    "ADR-0037; TP §6.6 check 3: a BIGINT UNSIGNED maximum read as a signed 64-bit integer wraps "
                            + "2^64-1 to -1, so " + label + " is declared decimal(20,0)");
        }
    }

    // --- Statement text (obligations 16 and 17; ADR-0003 ¶2) ---------------------------------------

    /** Written by hand from ticket #135, not copied from the implementation's output. */
    private static final String EXPECTED_SCAN = """
            SELECT MAX(COALESCE(OCTET_LENGTH(CAST(`id` AS BINARY)), 0)) AS `value_bytes_1`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`order``s` AS BINARY)), 0)) AS `value_bytes_2`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`payload` AS BINARY)), 0)) AS `value_bytes_3`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`flag` AS BINARY)), 0)) AS `value_bytes_4`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`total` AS BINARY)), 0)) AS `value_bytes_5`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`at` AS BINARY)), 0)) AS `value_bytes_6`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`kind` AS BINARY)), 0)) AS `value_bytes_7`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`created` AS BINARY)), 0)) AS `value_bytes_8`, \
            MAX(COALESCE(OCTET_LENGTH(CAST(`id` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`order``s` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`payload` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`flag` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`total` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`at` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`kind` AS BINARY)), 0) \
            + COALESCE(OCTET_LENGTH(CAST(`created` AS BINARY)), 0)) AS `row_bytes`, \
            SUM(CASE WHEN `flag` IS NOT NULL AND `flag` NOT IN (0, 1) THEN 1 ELSE 0 END) AS `boolean_domain_4`, \
            MAX(`total`) AS `unsigned_max_5`, \
            SUM(CASE WHEN `at` IS NOT NULL AND (TIME_TO_SEC(`at`) < 0 OR TIME_TO_SEC(`at`) >= 86400) \
            THEN 1 ELSE 0 END) AS `time_domain_6`, \
            SUM(CASE WHEN `kind` IS NOT NULL AND `kind` + 0 = 0 THEN 1 ELSE 0 END) AS `enum_sentinel_7`, \
            SUM(CASE WHEN `created` IS NOT NULL AND (YEAR(`created`) = 0 OR MONTH(`created`) = 0 \
            OR DAYOFMONTH(`created`) = 0) THEN 1 ELSE 0 END) AS `zero_date_rows_8`, \
            MIN(`id`) AS `keyset_min_1`, MAX(`id`) AS `keyset_max_1`, \
            MIN(`code`) AS `keyset_min_2`, MAX(`code`) AS `keyset_max_2` \
            FROM `shop`.`订单`""";

    @Test
    void theStatementTextIsPinnedForARepresentativeTable() {
        assertEquals(EXPECTED_SCAN, sql(plan()),
                "ADR-0003 ¶2; obligations 16 and 17: one query carries every per-value maximum, the row maximum, "
                        + "each type-domain count and both keyset candidates' extrema");
    }

    @Test
    void aPrunedColumnIsInNeitherThePerValueNorTheRowExpression() {
        String scan = sql(plan());

        assertTrue(orders().columns().stream().anyMatch(column -> column.coordinate().column().equals("secret")),
                "the fixture must hold a column that the approved selection prunes");
        assertFalse(scan.contains("`secret`"),
                "preflight obligation 6: after a column is excluded the scan covers only the approved selected "
                        + "columns, in the per-value maxima and in the row payload alike");
        assertEquals(APPROVED.size(), scan.split("AS `value_bytes_", -1).length - 1,
                "preflight obligation 6: one per-value maximum per approved column, no more");
        assertEquals(APPROVED.size(), rowExpression(scan).split("COALESCE", -1).length - 1,
                "ADR-0003 ¶2: the row payload sums exactly the approved columns' byte lengths");
    }

    /** The argument of the one {@code MAX(...)} aliased {@code row_bytes}. */
    private static String rowExpression(String scan) {
        int alias = scan.indexOf(" AS `row_bytes`");
        int start = scan.lastIndexOf("MAX(", alias);
        return scan.substring(start, alias);
    }

    @Test
    void thePerColumnExpressionIsTheProjectionsOwn() {
        for (ApprovedColumn column : APPROVED) {
            String projected = projectionExpression(column);
            assertTrue(sql(plan()).contains("COALESCE(OCTET_LENGTH(CAST(" + projected + " AS BINARY)), 0)"),
                    "obligation 19a: the envelope measures the expression the run extracts, byte for byte, by "
                            + "calling queryProjection's own: " + projected);
        }
    }

    /** {@code SELECT <expr> AS <alias> FROM …} for one column, so the test never re-derives the expression. */
    private static String projectionExpression(ApprovedColumn column) {
        String projection = SOURCE.queryProjection(List.of(column), List.of()).text();
        return projection.substring("SELECT ".length(), projection.lastIndexOf(" AS "));
    }

    @Test
    void noZeroDateLiteralAppearsAnywhereInTheStatement() {
        String scan = sql(plan());

        assertFalse(scan.contains("0000"),
                "TP §6.6 check 6: under NO_ZERO_DATE/NO_ZERO_IN_DATE sql_mode a zero-date literal raises an error, "
                        + "and an error is an INCONCLUSIVE that would block a healthy table");
        assertTrue(scan.contains("YEAR(`created`) = 0") && scan.contains("MONTH(`created`) = 0")
                        && scan.contains("DAYOFMONTH(`created`) = 0"),
                "TP §6.6 check 6: a zero date is exactly a date one of whose components is nought, so the "
                        + "components answer it without a literal: " + scan);
        assertFalse(scan.contains("'"),
                "TP §6.6 check 5: no member list and no date is ever spelled as a literal in this scan, so a "
                        + "value containing a quote cannot defeat a check");
    }

    @Test
    void theTwoZeroDateObligationsShareOneCount() {
        SqlPlan both = plan();
        SqlPlan onlyNoZeroDate = SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                obligation("created", RequiredPreflight.NO_ZERO_DATE)));
        SqlPlan onlyCounted = SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                obligation("created", RequiredPreflight.ZERO_DATE_ROWS_COUNTED)));

        assertEquals(1, sql(both).split("zero_date_rows_8", -1).length - 1,
                "TP §6.6 check 6: NO_ZERO_DATE and ZERO_DATE_ROWS_COUNTED are the same count, graded differently "
                        + "by preflight, so they plan one column and not two");
        assertEquals(sql(onlyNoZeroDate), sql(onlyCounted),
                "TP §6.6 check 6: the two obligations plan the identical aggregate");
    }

    // --- Every RequiredPreflight constant (TP §6.6) ------------------------------------------------

    @Test
    void everyRequiredPreflightConstantIsPlannedOrDeliberatelyPlansNothing() {
        Map<RequiredPreflight, String> planned = new TreeMap<>(Map.of(
                RequiredPreflight.BOOLEAN_VALUES_ZERO_OR_ONE, "boolean_domain_4",
                RequiredPreflight.UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE, "unsigned_max_5",
                RequiredPreflight.TIME_WITHIN_DAY, "time_domain_6",
                RequiredPreflight.ENUM_VALUE_DECLARED, "enum_sentinel_7",
                RequiredPreflight.NO_ZERO_DATE, "zero_date_rows_8",
                RequiredPreflight.ZERO_DATE_ROWS_COUNTED, "zero_date_rows_8"));
        List<String> envelopeOnly = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        plan().resultSchema().columns().forEach(column -> labels.add(column.label()));

        for (RequiredPreflight preflight : RequiredPreflight.values()) {
            if (planned.containsKey(preflight)) {
                assertTrue(labels.contains(planned.get(preflight)),
                        "TP §6.6: " + preflight + " plans the aggregate " + planned.get(preflight));
                continue;
            }
            envelopeOnly.add(preflight.name());
        }
        assertEquals(List.of("AUTO_INCREMENT_NEXT_VALUE_WITHIN_SIGNED_RANGE", "LARGE_RECORD_ENVELOPE"), envelopeOnly,
                "ADR-0003 ¶2; spec #134 correction 5: LARGE_RECORD_ENVELOPE is already measured for every approved "
                        + "column, and the next auto-increment value is an information_schema.TABLES fact");
    }

    @Test
    void theAutoIncrementObligationPlansNothingAndIsAMetadataFact() {
        SqlPlan without = SOURCE.preflightScanPlan(orders(), APPROVED, List.of());
        SqlPlan with = SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                obligation("id", RequiredPreflight.AUTO_INCREMENT_NEXT_VALUE_WITHIN_SIGNED_RANGE)));

        assertEquals(sql(without), sql(with),
                "TP §6.6 check 7; spec #134 correction 5: the next auto-increment value is read from "
                        + "information_schema.TABLES, so the scan plans no column for it");
        assertEquals(OptionalLong.of(2048), orders().statistics().autoIncrement(),
                "spec #134 correction 5: TableStatistics carries the next auto-increment value the check needs");
        assertFalse(sql(with).toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT"),
                "TP §6.6 check 7: the scan never reads the auto-increment value from the rows");
    }

    @Test
    void theEnvelopeCoversEveryApprovedColumnWhetherOrNotItIsObliged() {
        SqlPlan noObligations = SOURCE.preflightScanPlan(orders(), APPROVED, List.of());

        assertEquals(APPROVED.size() + 1 + 4, noObligations.resultSchema().columns().size(),
                "ADR-0003 ¶2: with no obligation at all the scan still measures every approved value, the row "
                        + "payload and both keyset candidates — a numeric column carries no LARGE_RECORD_ENVELOPE "
                        + "obligation yet still counts toward the payload (spec #134 correction 1)");
    }

    // --- Keyset extrema (obligation 17; ADR-0037) --------------------------------------------------

    @Test
    void everyKeysetCandidateGetsExtremaAndPrunedCandidatesAreNotFilteredHere() {
        SourceTableMetadata orders = orders();

        assertEquals(List.of(at("id"), at("code")),
                SOURCE.keysetCandidates(orders).stream().map(KeysetCandidate::column).toList(),
                "obligation 17: the candidate list is source.keysetCandidates', in its order");
        assertTrue(APPROVED.stream().noneMatch(column -> column.source().equals(at("code"))),
                "spec #134 gap 3: the fixture must hold a candidate that is not an approved column");
        assertTrue(sql(plan()).contains("MIN(`code`) AS `keyset_min_2`")
                        && sql(plan()).contains("MAX(`code`) AS `keyset_max_2`"),
                "spec #134 gap 3: dropping a pruned candidate is preflight obligations 16–18's rule, so this "
                        + "plan reports extrema for every candidate and invents no rule of its own");
    }

    @Test
    void aTableWithoutAKeysetCandidatePlansNoExtrema() {
        SourceTableMetadata unkeyed = table(ORDERS, List.of(nullable("payload", "longblob")));

        SqlPlan plan = SOURCE.preflightScanPlan(unkeyed, List.of(approved("payload")), List.of());

        assertEquals(List.of("value_bytes_1", "row_bytes"),
                plan.resultSchema().columns().stream().map(ResultSchema.Column::label).toList(),
                "obligation 17: a table ADR-0037 finds no keyset column for gets no extrema columns invented for it");
    }

    // --- Refusals (spec #134 correction 1) ---------------------------------------------------------

    @Test
    void anObligationNamingAColumnThatIsNotApprovedIsRefusedNamingTheCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                        obligation("secret", RequiredPreflight.NO_ZERO_DATE))),
                "preflight obligation 6: an obligation on a pruned column means the caller merged the wrong lists");
        assertTrue(failure.getMessage().contains("secret"),
                "the refusal names the coordinate the caller got wrong: " + failure.getMessage());
    }

    @Test
    void anObligationNamingAColumnThatIsNotInTheTableIsRefusedNamingTheCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                        obligation("ghost", RequiredPreflight.TIME_WITHIN_DAY))),
                "ADR-0003 ¶2: an obligation for a column the table does not have is a caller error");
        assertTrue(failure.getMessage().contains("ghost"),
                "the refusal names the coordinate the caller got wrong: " + failure.getMessage());
    }

    @Test
    void anApprovedColumnThatIsNotInTheTableIsRefusedNamingTheCoordinate() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SOURCE.preflightScanPlan(orders(), List.of(approved("ghost")), List.of()),
                "ADR-0003 ¶2: the approved columns and the table metadata must describe the same table");
        assertTrue(failure.getMessage().contains("ghost"),
                "the refusal names the coordinate the caller got wrong: " + failure.getMessage());
    }

    @Test
    void aTableWithNoApprovedColumnIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> SOURCE.preflightScanPlan(orders(), List.of(), OBLIGATIONS),
                "ADR-0003 ¶2: the scan measures approved extraction expressions, so an empty approval has nothing "
                        + "to measure and is a caller error, not an empty plan");
    }

    // --- Fingerprints (ADR-0008 §Plans) ------------------------------------------------------------

    @Test
    void distinctInputsGiveDistinctFingerprints() {
        SqlPlan base = plan();
        SourceTableMetadata renamedTable = table(new TableCoordinate("shop", "orders"), List.of(
                notNull("id", "bigint unsigned"), nullable("created", "date")), unique(
                        new TableCoordinate("shop", "orders"), SourceIndex.PRIMARY, "id"));
        Map<String, SqlPlan> variants = new TreeMap<>(Map.of(
                "the table", SOURCE.preflightScanPlan(renamedTable,
                        List.of(approved(renamedTable.table(), "id"), approved(renamedTable.table(), "created")),
                        List.of(obligation(renamedTable.table(), "created", RequiredPreflight.NO_ZERO_DATE))),
                "an approved column removed", SOURCE.preflightScanPlan(orders(), APPROVED.subList(0, 7),
                        OBLIGATIONS.subList(0, 5)),
                "the approved column order", SOURCE.preflightScanPlan(orders(), reversed(APPROVED), List.of()),
                "an obligation removed", SOURCE.preflightScanPlan(orders(), APPROVED, OBLIGATIONS.subList(0, 5)),
                "the obligation order", SOURCE.preflightScanPlan(orders(), APPROVED, reversed(OBLIGATIONS)),
                "an obligation's kind", SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                        obligation("flag", RequiredPreflight.BOOLEAN_VALUES_ZERO_OR_ONE))),
                "an obligation's column", SOURCE.preflightScanPlan(orders(), APPROVED, List.of(
                        obligation("total", RequiredPreflight.UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE))),
                "a keyset candidate removed", SOURCE.preflightScanPlan(
                        table(ORDERS, orderColumns(), unique(ORDERS, SourceIndex.PRIMARY, "id")),
                        APPROVED, OBLIGATIONS)));

        assertEquals(base.fingerprint(), plan().fingerprint(),
                "ADR-0008 §Plans: equal inputs are an equal plan");
        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : variants.entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: changing only " + variant.getKey() + " must change the fingerprint");
        }
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }

    /** {@link #orders()}'s columns, so a variant can drop an index without changing anything else. */
    private static List<Col> orderColumns() {
        return List.of(notNull("id", "bigint unsigned"), nullable("order`s", "varchar(64)"),
                nullable("payload", "longblob"), nullable("flag", "tinyint(1)"),
                nullable("total", "bigint unsigned"), nullable("at", "time(6)"),
                nullable("kind", "enum('a','b')"), nullable("created", "date"),
                nullable("secret", "varchar(64)"), notNull("code", "int"));
    }

    /**
     * Derived outside Java from the documented {@code SqlPlan/1} encoding (length-prefixed UTF-8, big-endian
     * ints, SHA-256), with the statement text taken from {@link #EXPECTED_SCAN} and the schema from
     * {@link #EXPECTED_COLUMNS}, in a separate Python script quoted on ticket #135 — never copied out of a
     * failing assertion.
     */
    @Test
    void theRepresentativeFingerprintIsPinnedFromTheIndependentModel() {
        assertEquals("236e35edce803de4dab6be8b3b82360af64451484c78b06cb82e22b9b49da6c0",
                plan().fingerprint().sha256Hex(),
                "ADR-0008 §Plans: the preflight scan's fingerprint must not depend on the JVM, machine or run");
    }

    // --- Hostile names (obligation 5; TP §7.1) -----------------------------------------------------

    private static final List<String> HOSTILE = List.of(
            "order`s", "``", "`", "a\"b", "'single'", "back\\slash\\", "$$body$$", "x; DROP TABLE t; --",
            "a -- b", "a /* b */", "# hash", "new\nline", "cr\rlf", "?", "t FOR UPDATE", "FLUSH",
            "adjacent￿", "订单明细", "emoji😀", " padded ", "0000-00-00",
            "a".repeat(63), "a".repeat(64), "订".repeat(64), "é".repeat(31) + "a", "é".repeat(32));

    @Test
    void hostileNamesNeverChangeStatementStructureAndRoundTripExactly() {
        String benign = sql(hostilePlan("shop", "orders", "created"));
        for (String name : HOSTILE) {
            for (SqlPlan plan : List.of(hostilePlan(name, "orders", "created"), hostilePlan("shop", name, "created"),
                    hostilePlan("shop", "orders", name))) {
                ParameterizedStatement statement = plan.statements().get(0);

                assertEquals(skeleton(benign), skeleton(statement.sql()),
                        "ADR-0008 §Plans: a hostile name must not change the statement's structure: " + name);
                assertEquals(0, statement.parameters().size(),
                        "ADR-0008 §Plans: no placeholder, no parameter — a quoted ? is not a binding: " + name);
                assertTrue(identifiers(statement.sql()).contains(name),
                        "TP §7.1: backtick quoting round-trips the exact source name: " + name);
            }
        }
    }

    @Test
    void quotingBoundariesArePinnedLiterally() {
        assertEquals("SELECT MAX(COALESCE(OCTET_LENGTH(CAST(`a``b` AS BINARY)), 0)) AS `value_bytes_1`, "
                        + "MAX(COALESCE(OCTET_LENGTH(CAST(`a``b` AS BINARY)), 0)) AS `row_bytes` "
                        + "FROM `s`.`a\"b\\c;--/*\nd`",
                sql(hostilePlan("s", "a\"b\\c;--/*\nd", "a`b")),
                "TP §7.1: MySQL quoting is not PostgreSQL's — a doubled backtick, and a double quote, backslash, "
                        + "semicolon, comment opener and newline kept literal");
        assertEquals(64, "é".repeat(32).getBytes(StandardCharsets.UTF_8).length,
                "TP §7.1: the fixture name is 64 bytes, MySQL's limit in characters and more than PostgreSQL's");
        for (String nul : List.of("nul name", " leading", "trailing ")) {
            assertThrows(IllegalArgumentException.class, () -> hostilePlan("s", "t", nul),
                    "TP §7.1: MySQL permits no U+0000 anywhere in an identifier, so such a name is refused "
                            + "rather than quoted");
        }
    }

    /** One approved column on a table with no keyset candidate, so only the name varies. */
    private static SqlPlan hostilePlan(String database, String tableName, String column) {
        TableCoordinate coordinate = new TableCoordinate(database, tableName);
        return SOURCE.preflightScanPlan(table(coordinate, List.of(nullable(column, "date"))),
                List.of(approved(coordinate, column)), List.of());
    }

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
        throw new AssertionError("unterminated identifier in " + sql);
    }
}
