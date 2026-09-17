package com.dbx.dialect.api;

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

    private static final DatabasePair PAIR = (DatabasePair) DialectCatalog.compileTime()
            .select(new ProductVersion("MySQL", "8.0.36"), new ProductVersion("PostgreSQL", "15.4"));

    private static final List<String> DATA_TYPES = fixture("/dialect/mysql80-data-types.txt");

    private static final String GOLDEN_INPUT = "input: data_type=";

    private static final List<MappingOptions> ALL_OPTIONS = List.of(
            new MappingOptions(false, false), new MappingOptions(true, false),
            new MappingOptions(false, true), new MappingOptions(true, true));

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
