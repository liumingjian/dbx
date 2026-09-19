package com.dbx.archfixture.connection.api;

/**
 * A {@code connection.api} that grew a seventh entry point: the exactly-six rule's violation.
 *
 * <p>{@code rotate} is the plausible one — rotating the master key is a real wish, and it is exactly
 * the kind of capability that would appear in slice 3 without anybody deciding it belongs here. The
 * six legitimate names are present too, so the rule has to report the extra one rather than merely
 * notice that the set differs.
 */
public interface FixtureConnectionCrypto {

    Object encrypt(Object material);

    Object decrypt(Object ciphertext);

    Object wrap(Object backup);

    Object unwrap(Object wrapped);

    Object erase(Object wrapped);

    Object fingerprint();

    Object rotate();
}
