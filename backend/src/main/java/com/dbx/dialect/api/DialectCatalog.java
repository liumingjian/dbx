package com.dbx.dialect.api;

import com.dbx.dialect.catalog.CompileTimeCatalog;
import java.util.List;

/**
 * The compile-time catalog of certified directed database pairs (ADR-0008 §Registration).
 *
 * <p>Selection is exact: missing, ambiguous, uncertified or version-incompatible requests are
 * {@link Unsupported}, with no nearest-version fallback. Implemented by slice 2.
 */
public interface DialectCatalog {

    /** The one catalog v1 has. There is no SPI, {@code ServiceLoader} or configured class name. */
    static DialectCatalog compileTime() {
        return CompileTimeCatalog.INSTANCE;
    }

    /** Selects the pair for probed product facts: a {@link DatabasePair} or {@link Unsupported}. */
    PairSelection select(ProductVersion sourceProductVersion, ProductVersion targetProductVersion);

    /** Descriptors of exactly the certified pairs, for the connection form (#46 Q1). */
    List<PairDescriptor> list();
}
