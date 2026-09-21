package com.dbx.archfixture.orchestration;

import com.dbx.archfixture.connection.PersistingConnection;

/**
 * Obligation 5's violation: a legitimate caller of {@code connection} — {@code orchestration} does call
 * {@code encrypt} — reaching past {@code connection.api} into the module.
 *
 * <p>It is the same shape as ADR-0018 rule 1's fixture, kept here as well because obligation 5 is
 * {@code connection}'s own: whatever else changes inside this module, the master-key loader stays
 * unnameable from outside.
 */
public final class ReachesIntoConnectionInternals {

    private final PersistingConnection internals = null;
}
