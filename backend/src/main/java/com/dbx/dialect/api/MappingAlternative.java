package com.dbx.dialect.api;

/** A structured alternative that only ever widens the default's value domain (TP §6.1). Owned by slice 3. */
public record MappingAlternative(
        TargetType targetType,
        ConnectRepresentation connectRepresentation,
        JdbcBinder jdbcBinder,
        ValueSemantics valueSemantics) {

    public MappingAlternative {
        Checks.present(targetType, "targetType");
        Checks.present(connectRepresentation, "connectRepresentation");
        Checks.present(jdbcBinder, "jdbcBinder");
        Checks.present(valueSemantics, "valueSemantics");
    }
}
