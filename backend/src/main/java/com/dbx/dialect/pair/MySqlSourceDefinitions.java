package com.dbx.dialect.pair;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.SourceForeignKey;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.TableCoordinate;
import com.dbx.dialect.mysql.MySqlDefinitions;
import com.dbx.dialect.postgres.SourceDefinitions;

/**
 * The pair's cross-endpoint wiring for supplemental SQL: PostgreSQL comments quote MySQL's own rendering of a
 * source definition, so neither endpoint dialect knows the other (ADR-0008 §Contract and mapping boundary).
 */
enum MySqlSourceDefinitions implements SourceDefinitions {
    INSTANCE;

    @Override
    public String table(TableCoordinate table) {
        return MySqlDefinitions.table(table);
    }

    @Override
    public String column(ColumnCoordinate column) {
        return MySqlDefinitions.column(column);
    }

    @Override
    public String index(TableCoordinate table, SourceIndex index) {
        return MySqlDefinitions.index(table, index);
    }

    @Override
    public String foreignKey(TableCoordinate table, SourceForeignKey foreignKey) {
        return MySqlDefinitions.foreignKey(table, foreignKey);
    }
}
