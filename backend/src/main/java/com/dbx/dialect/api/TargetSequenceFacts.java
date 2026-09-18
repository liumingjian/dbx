package com.dbx.dialect.api;

/**
 * A sequence the catalog says an approved table owns, with the column that owns it and the sequence's own
 * data type ({@code pg_depend}, {@code pg_sequence}; TP §7.4; ADR-0011 §DDL and structural proof). Ownership
 * is a fact of the read, not a judgement: deciding whether it is the ownership ADR-0011 asks for belongs to
 * {@code contract.prove}. Owned by slice 8.
 */
public record TargetSequenceFacts(TargetTableCoordinate sequence, TargetIdentifier ownerColumn, String dataType) {

    public TargetSequenceFacts {
        Checks.present(sequence, "sequence");
        Checks.present(ownerColumn, "ownerColumn");
        Checks.nonEmpty(dataType, "dataType");
    }
}
