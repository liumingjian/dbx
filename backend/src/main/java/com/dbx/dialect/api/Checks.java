package com.dbx.dialect.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Constructor guards shared by the value types. Names are kept exactly: nothing here trims. */
final class Checks {

    private Checks() {
    }

    static <T> T present(T value, String what) {
        return Objects.requireNonNull(value, what + " is required");
    }

    static String nonEmpty(String value, String what) {
        present(value, what);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        return value;
    }

    static <T> List<T> list(List<T> values, String what) {
        return List.copyOf(present(values, what));
    }

    static <T> List<T> nonEmptyList(List<T> values, String what) {
        List<T> copy = list(values, what);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        return copy;
    }

    static <T> Set<T> set(Set<T> values, String what) {
        return Set.copyOf(present(values, what));
    }

    static int positive(int value, String what) {
        if (value < 1) {
            throw new IllegalArgumentException(what + " must be positive, was " + value);
        }
        return value;
    }

    static long positive(long value, String what) {
        if (value < 1) {
            throw new IllegalArgumentException(what + " must be positive, was " + value);
        }
        return value;
    }
}
