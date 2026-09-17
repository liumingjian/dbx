package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.ALL_OPTIONS;
import static com.dbx.dialect.api.MappingCase.PAIR;
import static com.dbx.dialect.api.SourceColumns.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.dbx.golden.GoldenFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The anti-vacuous-green device of the matrix (ADR-0022; TP §6.1). The MySQL 8.0 {@code data_type}
 * list is a fixture, never read from the implementation: a type the implementation forgot is then
 * present on one side only and fails here, instead of being absent from both and passing. There is
 * no deferred list and no tolerated exception: an uncovered type needs a mapping decision.
 */
class TypeMappingExhaustiveTest {


    private static final List<String> DATA_TYPES = fixture("/dialect/mysql80-data-types.txt");

    private static final String GOLDEN_INPUT = "input: data_type=";


    @Test
    void everyMySql80DataTypeYieldsAClosedDecision() {
        List<String> forgotten = new ArrayList<>();
        for (String dataType : DATA_TYPES) {
            for (SourceColumn variant : variants(dataType)) {
                for (MappingOptions options : ALL_OPTIONS) {
                    String where = dataType + " as " + variant.columnType() + " under " + options;
                    try {
                        if (isNotWhitelisted(PAIR.map(variant, options))) {
                            forgotten.add(where);
                        }
                    } catch (RuntimeException e) {
                        forgotten.add(where + " threw " + e);
                    }
                }
            }
        }
        assertEquals(List.of(), forgotten,
                "TP §6.1: every MySQL 8.0 data_type gets a closed Supported | Unsupported decision. These fell "
                        + "through to NOT_WHITELISTED or threw, so the implementation forgot them; the fix is a "
                        + "mapping decision, not a deferred list");
    }

    /**
     * A refusal of contradictory facts is closed but decides nothing, so the loop above would still pass for
     * a type the mapper always refuses as inconsistent. Each fixture type's canonical, well-formed metadata,
     * as MySQL 8.0 reports it, must therefore reach a real decision: {@code Supported}, or a refusal with its
     * own stable reason such as {@code GEOMETRY}.
     */
    @Test
    void everyMySql80DataTypeReachesARealDecisionFromWellFormedMetadata() {
        assertEquals(DATA_TYPES, CANONICAL.stream().map(c -> c.build().dataType()).toList(),
                "one canonical metadata row per fixture type, in fixture order");
        List<String> undecided = new ArrayList<>();
        for (SourceColumns canonical : CANONICAL) {
            SourceColumn column = canonical.build();
            for (MappingOptions options : ALL_OPTIONS) {
                if (PAIR.map(column, options) instanceof Unsupported refused
                        && (refused.reason() == MappingUnsupportedReason.NOT_WHITELISTED
                        || refused.reason() == MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT)) {
                    undecided.add(column.columnType() + " under " + options + ": " + refused.reason());
                }
            }
        }
        assertEquals(List.of(), undecided, "TP §6.1–6.4: well-formed metadata of every MySQL 8.0 data_type needs a "
                + "real decision, not NOT_WHITELISTED or SOURCE_FACTS_INCONSISTENT");
    }

    private static final String UTF8MB4 = "utf8mb4";
    private static final String UTF8MB4_CI = "utf8mb4_0900_ai_ci";

    /** Each fixture type as a plain {@code CREATE TABLE} column of that type reports it in MySQL 8.0.36. */
    private static final List<SourceColumns> CANONICAL = List.of(
            column("tinyint", "tinyint").precision(3, 0),
            column("smallint", "smallint").precision(5, 0),
            column("mediumint", "mediumint").precision(7, 0),
            column("int", "int").precision(10, 0),
            column("bigint", "bigint").precision(19, 0),
            column("decimal", "decimal(10,2)").precision(10, 2),
            column("float", "float").precision(12),
            column("double", "double").precision(22),
            column("bit", "bit(1)").precision(1),
            column("char", "char(10)").characterLength(10, 40).charset(UTF8MB4, UTF8MB4_CI),
            column("varchar", "varchar(255)").characterLength(255, 1020).charset(UTF8MB4, UTF8MB4_CI),
            column("binary", "binary(16)").characterLength(16, 16),
            column("varbinary", "varbinary(255)").characterLength(255, 255),
            column("tinytext", "tinytext").characterLength(255, 255).charset(UTF8MB4, UTF8MB4_CI),
            column("text", "text").characterLength(65535, 65535).charset(UTF8MB4, UTF8MB4_CI),
            column("mediumtext", "mediumtext").characterLength(16777215, 16777215).charset(UTF8MB4, UTF8MB4_CI),
            column("longtext", "longtext").characterLength(4294967295L, 4294967295L).charset(UTF8MB4, UTF8MB4_CI),
            column("tinyblob", "tinyblob").characterLength(255, 255),
            column("blob", "blob").characterLength(65535, 65535),
            column("mediumblob", "mediumblob").characterLength(16777215, 16777215),
            column("longblob", "longblob").characterLength(4294967295L, 4294967295L),
            column("enum", "enum('a','b')").characterLength(1, 4).charset(UTF8MB4, UTF8MB4_CI),
            column("set", "set('a','b')").characterLength(3, 12).charset(UTF8MB4, UTF8MB4_CI),
            column("json", "json"),
            column("geometry", "geometry"),
            column("point", "point"),
            column("linestring", "linestring"),
            column("polygon", "polygon"),
            column("multipoint", "multipoint"),
            column("multilinestring", "multilinestring"),
            column("multipolygon", "multipolygon"),
            column("geomcollection", "geomcollection"),
            column("date", "date"),
            column("datetime", "datetime").datetimePrecision(0),
            column("timestamp", "timestamp").datetimePrecision(0),
            column("time", "time").datetimePrecision(0),
            column("year", "year"));

    @Test
    void theFixtureListsEachTypeOnceAndNoDeferredListExists() {
        assertEquals(new LinkedHashSet<>(DATA_TYPES).size(), DATA_TYPES.size(), "the fixture lists each type once");
        assertNull(TypeMappingExhaustiveTest.class.getResource("/dialect/mapping-deferred-data-types.txt"),
                "#118 deleted the deferred list; a type without a mapping decision is a finding, not an entry");
    }

    /**
     * Golden set 1 covers the full matrix. The count compared with the fixture is the number of
     * distinct fixture {@code data_type}s with at least one recorded row: a type's several rows
     * (metadata and switch variants) count once. Rows for types outside the MySQL 8.0 fixture, such as
     * MySQL 9.0+ {@code vector}, are not counted, but they too must carry a real decision.
     */
    @Test
    void theGoldenMatrixRecordsARowForEveryFixtureType() {
        Set<String> fixtureTypesRecorded = new TreeSet<>();
        Set<String> otherTypesRecorded = new TreeSet<>();
        for (String file : TypeMappingMatrixGoldenSource.FAMILIES.keySet()) {
            GoldenFiles.read(TypeMappingMatrixGoldenSource.SET, file).lines()
                    .filter(line -> line.startsWith(GOLDEN_INPUT))
                    .map(line -> line.substring(GOLDEN_INPUT.length(), line.indexOf(' ', GOLDEN_INPUT.length())))
                    .forEach(dataType -> (DATA_TYPES.contains(dataType) ? fixtureTypesRecorded : otherTypesRecorded)
                            .add(dataType));
        }

        Set<String> missing = new TreeSet<>(DATA_TYPES);
        missing.removeAll(fixtureTypesRecorded);
        assertEquals(Set.of(), missing, "ADR-0022: golden set 1 has no row for these MySQL 8.0 data_types");
        assertEquals(DATA_TYPES.size(), fixtureTypesRecorded.size(),
                "the golden matrix's distinct MySQL 8.0 data_types match the fixture list");
        for (String other : otherTypesRecorded) {
            assertFalse(isNotWhitelisted(PAIR.map(column(other, other).build(), MappingOptions.DEFAULTS)),
                    "the golden row for " + other + ", outside the 8.0 fixture, still needs a real decision");
        }
    }

    @Test
    void aDataTypeOutsideMySql80IsRefusedWithAStableReason() {
        for (String dataType : List.of("not_a_type", "uuid", "tinyint unsigned", " int")) {
            MappingDecision decision = PAIR.map(column(dataType, dataType).build(), MappingOptions.DEFAULTS);
            assertTrue(isNotWhitelisted(decision),
                    "TP §6.3: a type outside the whitelist is Unsupported(NOT_WHITELISTED), got " + decision);
        }
    }

    /** Metadata variants a real column of this type can arrive with, including facts left absent. */
    private static List<SourceColumn> variants(String dataType) {
        return List.of(
                column(dataType, dataType).build(),
                column(dataType, dataType + " unsigned").build(),
                column(dataType, dataType + "(1)").precision(1, 0).build(),
                column(dataType, dataType + "(10,2)").precision(10, 2).datetimePrecision(6).notNull().build(),
                column(dataType, dataType + "(255)").characterLength(255, 1020).charset("utf8mb4", "utf8mb4_0900_ai_ci")
                        .build());
    }

    private static boolean isNotWhitelisted(MappingDecision decision) {
        return switch (decision) {
            case Supported ignored -> false;
            case Unsupported u -> u.reason() == MappingUnsupportedReason.NOT_WHITELISTED;
        };
    }

    private static List<String> fixture(String resource) {
        try (InputStream stream = TypeMappingExhaustiveTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                fail("missing fixture " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
