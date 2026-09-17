package com.dbx.dialect.api;

/** A source structure left out of the minimal writable table and delivered as supplemental SQL (ADR-0026). Slice 7. */
public record DeferredStructure(Kind kind, TableCoordinate source, TargetTableCoordinate target) {

    public DeferredStructure {
        Checks.present(kind, "kind");
        Checks.present(source, "source");
        Checks.present(target, "target");
    }

    public enum Kind {
        UNIQUE_CONSTRAINT,
        INDEX,
        FOREIGN_KEY,
        COMMENT,
        COLLATION,
        ON_UPDATE_CURRENT_TIMESTAMP
    }
}
