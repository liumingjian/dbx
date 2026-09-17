package com.dbx.dialect.api;

/**
 * What a pair records so a contract snapshot links back to the rules that produced it (ADR-0008
 * §Registration), and what {@code catalog.list} offers the connection form.
 */
public record PairDescriptor(
        DialectId sourceDialect,
        DialectId targetDialect,
        MappingVersion mappingVersion,
        CertificationVersion certificationVersion) {

    public PairDescriptor {
        Checks.present(sourceDialect, "sourceDialect");
        Checks.present(targetDialect, "targetDialect");
        Checks.present(mappingVersion, "mappingVersion");
        Checks.present(certificationVersion, "certificationVersion");
    }
}
