package com.dbx.dialect.api;

import java.util.List;

/**
 * What the target catalog says one table is (TP §7.4; ADR-0011 §DDL and structural proof; ADR-0023): its
 * object kind, its {@code pg_class} OID, its columns in {@code attnum} order, its primary key in key order,
 * and the sequences it owns. It compares nothing: {@code contract.prove} owns every comparison
 * ({@code contract.md} obligation 20). Owned by slice 8.
 *
 * <p>{@code relkind} is the catalog's own single-character code, kept as text so an object that is not a base
 * table is a difference rather than a parse failure. {@code ownedSequences} is a list, not one value, because
 * a second sequence on an approved table is drift for {@code prove} to report, not a broken read.
 */
public record TargetTableFacts(
        TargetTableCoordinate table,
        String relkind,
        long pgClassOid,
        List<TargetColumnFacts> columns,
        List<TargetIdentifier> primaryKey,
        List<TargetSequenceFacts> ownedSequences) {

    public TargetTableFacts {
        Checks.present(table, "table");
        Checks.nonEmpty(relkind, "relkind");
        Checks.positive(pgClassOid, "pgClassOid");
        columns = Checks.list(columns, "columns");
        primaryKey = Checks.list(primaryKey, "primaryKey");
        ownedSequences = Checks.list(ownedSequences, "ownedSequences");
    }
}
