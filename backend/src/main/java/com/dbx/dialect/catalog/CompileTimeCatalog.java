package com.dbx.dialect.catalog;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.DialectCatalog;
import com.dbx.dialect.api.PairDescriptor;
import com.dbx.dialect.api.PairSelection;
import com.dbx.dialect.api.ProductVersion;
import java.util.List;

/** v1's compile-time catalog (ADR-0008 §Registration). Slice 2 registers MySQL 8.0 → PostgreSQL 15 here. */
public final class CompileTimeCatalog implements DialectCatalog {

    public static final CompileTimeCatalog INSTANCE = new CompileTimeCatalog();

    private CompileTimeCatalog() {
    }

    @Override
    public PairSelection select(ProductVersion sourceProductVersion, ProductVersion targetProductVersion) {
        throw new NotImplementedInSlice("catalog.select", 2);
    }

    @Override
    public List<PairDescriptor> list() {
        throw new NotImplementedInSlice("catalog.list", 2);
    }
}
