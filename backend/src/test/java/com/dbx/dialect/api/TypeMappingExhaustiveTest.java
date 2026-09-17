package com.dbx.dialect.api;

import static com.dbx.dialect.api.SourceColumns.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
 * present on one side only and fails here, instead of being absent from both and passing.
 */
class TypeMappingExhaustiveTest {

    private static final DatabasePair PAIR = (DatabasePair) DialectCatalog.compileTime()
            .select(new ProductVersion("MySQL", "8.0.36"), new ProductVersion("PostgreSQL", "15.4"));

    private static final List<String> DATA_TYPES = fixture("/dialect/mysql80-data-types.txt");
    private static final List<String> DEFERRED = fixture("/dialect/mapping-deferred-data-types.txt");

    private static final List<MappingOptions> ALL_OPTIONS = List.of(
            new MappingOptions(false, false), new MappingOptions(true, false),
            new MappingOptions(false, true), new MappingOptions(true, true));

    @Test
    void everyMySql80DataTypeIsEitherDecidedOrExplicitlyDeferred() {
        List<String> forgotten = new ArrayList<>();
        List<String> deferredButDecided = new ArrayList<>();
        for (String dataType : DATA_TYPES) {
            boolean deferred = DEFERRED.contains(dataType);
            for (SourceColumn variant : variants(dataType)) {
                for (MappingOptions options : ALL_OPTIONS) {
                    boolean notWhitelisted = isNotWhitelisted(PAIR.map(variant, options));
                    if (!deferred && notWhitelisted) {
                        forgotten.add(dataType + " as " + variant.columnType() + " under " + options);
                    }
                    if (deferred && !notWhitelisted) {
                        deferredButDecided.add(dataType + " as " + variant.columnType() + " under " + options);
                    }
                }
            }
        }
        assertEquals(List.of(), forgotten,
                "TP §6.1: every MySQL 8.0 data_type gets a real decision. These fell through to NOT_WHITELISTED "
                        + "although they are not in the deferred list, so the implementation forgot them");
        assertEquals(List.of(), deferredButDecided,
                "The deferred list only shrinks honestly: these types are decided now, so remove them from "
                        + "mapping-deferred-data-types.txt");
    }

    @Test
    void theDeferredListOnlyNamesRealMySql80DataTypes() {
        Set<String> unknown = new TreeSet<>(DEFERRED);
        unknown.removeAll(DATA_TYPES);
        assertEquals(Set.of(), unknown, "a deferred entry that is not a MySQL 8.0 data_type defers nothing");
        assertEquals(new LinkedHashSet<>(DATA_TYPES).size(), DATA_TYPES.size(), "the fixture lists each type once");
        assertEquals(new LinkedHashSet<>(DEFERRED).size(), DEFERRED.size(), "the deferred list names each type once");
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
