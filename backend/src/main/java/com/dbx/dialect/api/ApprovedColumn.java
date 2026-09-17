package com.dbx.dialect.api;

/** A selected source column and the approved target name it is projected as (TP §7.1; #92). */
public record ApprovedColumn(ColumnCoordinate source, TargetIdentifier target) {

    public ApprovedColumn {
        Checks.present(source, "source");
        Checks.present(target, "target");
    }
}
