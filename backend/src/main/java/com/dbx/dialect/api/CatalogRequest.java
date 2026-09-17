package com.dbx.dialect.api;

/** The evidence a refused {@code catalog.select} carries: exactly what was asked for. */
public record CatalogRequest(ProductVersion source, ProductVersion target) implements UnsupportedEvidence {

    public CatalogRequest {
        Checks.present(source, "source");
        Checks.present(target, "target");
    }
}
