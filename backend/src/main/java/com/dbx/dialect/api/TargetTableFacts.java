package com.dbx.dialect.api;

import java.util.List;

/** What the target catalog says a table is, with its {@code pg_class} OID (TP §7.4; ADR-0023). Owned by slice 8. */
public record TargetTableFacts(TargetTableCoordinate table, long pgClassOid, List<TargetColumn> columns) {

    public TargetTableFacts {
        Checks.present(table, "table");
        Checks.positive(pgClassOid, "pgClassOid");
        columns = Checks.list(columns, "columns");
    }
}
