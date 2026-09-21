package com.dbx.archfixture.connector;

import com.dbx.archfixture.connection.api.FixtureConnectionCrypto;

/**
 * Obligation 3's violation on the {@code connector} side: the other half, because a rule that reports
 * only {@code gateway} would let the same reach in through the connector's {@code projectSecret}.
 */
public final class DecryptsItsOwnSecret {

    private final FixtureConnectionCrypto crypto = null;
}
