package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.dbx.golden.GoldenFiles;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden set 1 (ADR-0022 §Golden files): the whole type-mapping matrix, read-only. A one-row change
 * to mapping shows up here as a diff, and only {@code -Pgolden.update=type-mapping-matrix} with a
 * {@code Golden-Update} trailer may accept it.
 */
class TypeMappingMatrixGoldenTest {

    @TestFactory
    Stream<DynamicTest> theRecordedMatrixIsWhatPairMapDecidesToday() {
        return new TypeMappingMatrixGoldenSource().render().entrySet().stream()
                .map(file -> dynamicTest(file.getKey(), () -> GoldenFiles.assertMatches(
                        TypeMappingMatrixGoldenSource.SET, file.getKey(), file.getValue())));
    }

    @Test
    void everyGoldenRowHasItsOwnName() {
        TypeMappingMatrixGoldenSource.FAMILIES.forEach((file, cases) -> {
            List<String> names = cases.stream().map(MappingCase::name).toList();
            Set<String> distinct = new TreeSet<>(names);
            assertEquals(names.size(), distinct.size(),
                    "ADR-0022: one golden case per matrix row and variant; a repeated name in " + file
                            + " hides which row changed");
        });
    }
}
