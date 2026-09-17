package com.dbx.dialect.api;

import java.util.List;

/**
 * A PostgreSQL 15 type with its modifiers, e.g. {@code NUMERIC [20, 0]} or {@code TIMESTAMP [3]}
 * (TP §6.2–6.4). Rendering it as DDL text belongs to the target dialect, not to this value.
 */
public record TargetType(TargetTypeName name, List<Integer> modifiers) {

    public TargetType {
        Checks.present(name, "name");
        modifiers = Checks.list(modifiers, "modifiers");
    }
}
