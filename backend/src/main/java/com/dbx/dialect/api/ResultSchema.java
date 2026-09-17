package com.dbx.dialect.api;

import java.util.List;

/**
 * The result a plan expects, which the gateway validates before any fact is used (ADR-0008 §Plans):
 * ordered columns with their database types and nullability, and the row cardinality.
 */
public record ResultSchema(List<Column> columns, Cardinality cardinality) {

    public ResultSchema {
        columns = Checks.list(columns, "columns");
        Checks.present(cardinality, "cardinality");
        if (cardinality == Cardinality.NO_RESULT && !columns.isEmpty()) {
            throw new IllegalArgumentException("a plan with no result set declares no result columns");
        }
    }

    /** For statements that return no rows, such as DDL. */
    public static final ResultSchema NONE = new ResultSchema(List.of(), Cardinality.NO_RESULT);

    public enum Cardinality {
        NO_RESULT,
        EXACTLY_ONE_ROW,
        AT_MOST_ONE_ROW,
        ANY_NUMBER_OF_ROWS
    }

    /** A result column; the type name is the database's own, e.g. {@code bigint}. */
    public record Column(String label, String databaseType, Nullability nullability) {

        public Column {
            Checks.nonEmpty(label, "label");
            Checks.nonEmpty(databaseType, "databaseType");
            Checks.present(nullability, "nullability");
        }
    }
}
