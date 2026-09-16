package com.dbx.archfixture.contract;

import com.dbx.archfixture.dialect.DialectInternal;

/** One module naming another module's non-api class: rule 1's violation. */
public final class ReachesIntoDialectInternals {

    private final DialectInternal internal = null;
}
