package com.dbx.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Each rejection ADR-0022 §Explicit updates asks for, watched to fire. */
class GoldenUpdateRequestTest {

    private static final Set<String> REGISTERED = Set.of("harness");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void anAbsentNameIsRejected(String absent) {
        assertTrue(rejectionFor(absent).contains("exactly one set name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "ALL", "All"})
    void allIsRejected(String all) {
        assertTrue(rejectionFor(all).contains("rejected"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "harn*", "harness?", "harness,boxes", "harn[ea]ss", "{harness}", "%"})
    void aWildcardOrAListIsRejected(String pattern) {
        assertTrue(rejectionFor(pattern).contains("exactly one named set"));
    }

    @Test
    void anUnknownNameFailsRatherThanCreatingASet() {
        assertTrue(rejectionFor("type-mapping").contains("Unknown golden set"));
    }

    @Test
    void oneRegisteredNameIsAccepted() {
        assertEquals("harness", GoldenUpdateRequest.validate("harness", REGISTERED));
        assertEquals("harness", GoldenUpdateRequest.validate("  harness\n", REGISTERED));
    }

    private static String rejectionFor(String rawName) {
        return assertThrows(GoldenUpdateRejected.class,
                () -> GoldenUpdateRequest.validate(rawName, REGISTERED)).getMessage();
    }
}
