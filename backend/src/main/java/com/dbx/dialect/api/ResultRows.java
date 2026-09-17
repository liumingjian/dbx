package com.dbx.dialect.api;

import java.util.List;

/**
 * The rows the gateway read for a plan, already validated against its {@link ResultSchema}, handed
 * back to a dialect to normalise. Typed values, never a map of column name to object.
 */
public record ResultRows(ResultSchema schema, List<Row> rows) {

    public ResultRows {
        Checks.present(schema, "schema");
        rows = Checks.list(rows, "rows");
        for (Row row : rows) {
            if (row.values().size() != schema.columns().size()) {
                throw new IllegalArgumentException("a row has " + row.values().size() + " values for "
                        + schema.columns().size() + " declared columns");
            }
        }
    }

    public record Row(List<SqlValue> values) {

        public Row {
            values = Checks.list(values, "values");
        }
    }
}
