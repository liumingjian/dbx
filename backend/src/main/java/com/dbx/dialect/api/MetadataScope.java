package com.dbx.dialect.api;

import java.util.List;

/** The source database and the selected tables a metadata or capability plan covers. Owned by slice 5. */
public record MetadataScope(SchemaCoordinate database, List<TableCoordinate> tables) {

    public MetadataScope {
        Checks.present(database, "database");
        tables = Checks.list(tables, "tables");
    }
}
