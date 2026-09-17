package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** {@code source.boundedRead}: obligation 22, ADR-0033 §Settings, ADR-0037 §Bulk path. */
class BoundedReadContractTest {

    private static final long MIB = 1024L * 1024L;
    private static final BoundedReadRequirement READ = PAIR.source().boundedRead();

    @Test
    void cursorFetchIsRequired() {
        assertTrue(READ.cursorFetch(), "ADR-0033: every MySQL Source connection uses cursor fetch");
    }

    @Test
    void fetchRowsComeFromA4MibBudgetClampedTo1Through1024() {
        assertEquals(new BoundedReadRequirement.RowBudget(4 * MIB, 1, 1024), READ.fetchRows(),
                "ADR-0033 §Settings: batch.max.rows = clamp(4 MiB ÷ M, 1, 1024)");
    }

    @Test
    void maxBufferSizeEqualsTheFetchRows() {
        assertEquals(BoundedReadRequirement.BufferSizing.EQUAL_TO_FETCH_ROWS, READ.maxBufferSize(),
                "ADR-0033 §Settings: max.buffer.size is equal to batch.max.rows");
    }

    @Test
    void keysetChunksComeFromA64MibBudgetClampedTo1Through131072() {
        assertEquals(new BoundedReadRequirement.RowBudget(64 * MIB, 1, 131072), READ.keysetChunkRows(),
                "ADR-0033 §Settings: query.suffix LIMIT N, N = clamp(64 MiB ÷ M, 1, 131072)");
    }

    @Test
    void rowCountsRoundDownToAPowerOfTwo() {
        assertEquals(BoundedReadRequirement.Rounding.POWER_OF_TWO_DOWN, READ.rounding(),
                "ADR-0033 §Settings: \"pow2\" means rounded down to a power of two");
    }

    @Test
    void aLargeRecordTableFetchesOneRowIntoABufferOfFour() {
        assertEquals(new BoundedReadRequirement.LargeRecordOverride(1, 4), READ.largeRecord(),
                "ADR-0033 §Settings, large-record box: batch.max.rows=1, max.buffer.size=4");
    }

    @Test
    void keysetReadsPollEvery100MsAndBulkReadsNeverRepoll() {
        assertEquals(new BoundedReadRequirement.PollInterval(100, 2147483647), READ.pollInterval(),
                "ADR-0033 §Settings: poll.interval.ms=100 for keyset reads; ADR-0037 §Bulk path: 2147483647");
    }

    @Test
    void aBulkReadHasAnEmptySuffix() {
        assertEquals("", READ.bulkQuerySuffix(), "ADR-0037 §Bulk path: a bulk read's query.suffix is empty");
    }

    @Test
    void aBulkReadIsCappedAt64Mib() {
        assertEquals(64 * MIB, READ.bulkReadCapBytes(), "ADR-0037 §64 MiB cap: a bulk read holds at most 64 MiB");
    }

    @Test
    void theDeclarationHasExactlyTheseConstants() {
        assertEquals(List.of("cursorFetch", "fetchRows", "maxBufferSize", "keysetChunkRows", "rounding",
                        "largeRecord", "pollInterval", "bulkQuerySuffix", "bulkReadCapBytes"),
                Arrays.stream(BoundedReadRequirement.class.getRecordComponents()).map(RecordComponent::getName).toList(),
                "ADR-0033 §Settings; ADR-0037 §Bulk path: the declaration holds the M-independent constants, "
                        + "and not preflight's 1.5 planned-row-count factor");
    }

    @Test
    void cursorFetchCannotBeDeclaredOff() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new BoundedReadRequirement(false, READ.fetchRows(), READ.maxBufferSize(), READ.keysetChunkRows(),
                        READ.rounding(), READ.largeRecord(), READ.pollInterval(), READ.bulkQuerySuffix(),
                        READ.bulkReadCapBytes()),
                "ADR-0033: cursor fetch is a mandatory platform policy");
        assertTrue(failure.getMessage().contains("ADR-0033"), "the failure names its ruling: " + failure.getMessage());
    }

    /** {@code connector.deriveBox} computes from M; nothing reachable from the declaration takes an argument. */
    @TestFactory
    Stream<DynamicTest> nothingIsComputedFromM() {
        return Stream.of(BoundedReadRequirement.class, BoundedReadRequirement.RowBudget.class,
                        BoundedReadRequirement.LargeRecordOverride.class, BoundedReadRequirement.PollInterval.class,
                        BoundedReadRequirement.BufferSizing.class, BoundedReadRequirement.Rounding.class)
                .map(type -> dynamicTest(type.getSimpleName(), () -> {
                    List<String> computing = Arrays.stream(type.getMethods())
                            .filter(method -> method.getDeclaringClass() == type)
                            .filter(method -> !Modifier.isStatic(method.getModifiers()))
                            .filter(method -> method.getParameterCount() > 0 && !method.getName().equals("equals"))
                            .map(Method::toString)
                            .toList();
                    assertEquals(List.of(), computing,
                            "ADR-0033 §Settings: the bounded-read declaration exposes nothing computed from M; "
                                    + "connector.deriveBox does that");
                }));
    }
}
