package com.dbx.dialect.api;

/** The typed input a refusal was about, so the reason can be shown without re-asking. */
public sealed interface UnsupportedEvidence
        permits CatalogRequest, SourceColumn, SourceCoordinate, DescriptorVersion {
}
