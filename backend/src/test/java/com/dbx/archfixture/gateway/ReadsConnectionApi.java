package com.dbx.archfixture.gateway;

import com.dbx.archfixture.connection.api.FixtureConnectionCrypto;

/**
 * Obligation 3's violation on the {@code gateway} side — and note it goes through {@code connection.api},
 * so ADR-0018 rule 1 is perfectly happy with it.
 *
 * <p>That is why obligation 3 needs a rule of its own: {@code gateway} receives decrypted material from
 * {@code orchestration} and must never be able to ask for the key itself, however legally it asks.
 */
public final class ReadsConnectionApi {

    private final FixtureConnectionCrypto crypto = null;
}
