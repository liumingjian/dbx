package com.dbx.dialect.api;

import java.util.List;

/** One table's columns to gather validation facts for, on both sides (TP §9.2). Owned by slices 6 and 8. */
public record ValidationItem(TableCoordinate source, TargetTableCoordinate target, List<ApprovedColumn> columns) {

    public ValidationItem {
        Checks.present(source, "source");
        Checks.present(target, "target");
        columns = Checks.list(columns, "columns");
    }
}
