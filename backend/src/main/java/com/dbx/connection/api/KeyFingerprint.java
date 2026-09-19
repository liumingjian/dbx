package com.dbx.connection.api;

import java.util.Objects;

/**
 * The present master key's fingerprint (ADR-0035 §Master key): what {@code workflow} stores in H2 and
 * what the environment check and upgrade compare, so that a swapped key is noticed before it is used.
 *
 * <p>It is safe to print, store and show in the UI, which is exactly why it must reveal nothing about
 * the key and stay stable across releases — a fingerprint that changed with a release would make every
 * upgrade look like a key swap. The algorithm is slice 2's decision (§Implementer decides); the type
 * only fixes that a fingerprint is a value, never the key.
 */
public record KeyFingerprint(String value) {

    public KeyFingerprint {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a fingerprint is what upgrade compares, so it cannot be blank");
        }
    }
}
