package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.BOOLEAN_ON;
import static com.dbx.dialect.api.MappingCase.SWITCHES_OFF;
import static com.dbx.dialect.api.MappingCase.of;
import static com.dbx.dialect.api.SourceColumns.column;

import java.util.List;

/**
 * Golden rows for TP §6.2, as MySQL 8.0.19+ reports them in {@code information_schema.COLUMNS}
 * (display widths dropped except {@code tinyint(1)} and zerofill). One row per matrix row and
 * source-metadata variant (ADR-0022 §Golden files). Rendered to {@code numeric.txt}.
 */
final class NumericMappingCases {

    static final List<MappingCase> CASES = List.of(
            of("TINYINT", column("tinyint", "tinyint").precision(3, 0), SWITCHES_OFF),
            of("TINYINT(4), pre-8.0.19 display width", column("tinyint", "tinyint(4)").precision(3, 0), SWITCHES_OFF),
            of("TINYINT(4), Boolean switch on", column("tinyint", "tinyint(4)").precision(3, 0), BOOLEAN_ON),
            of("TINYINT(1)", column("tinyint", "tinyint(1)").precision(3, 0), SWITCHES_OFF),
            of("TINYINT(1), Boolean switch on", column("tinyint", "tinyint(1)").precision(3, 0), BOOLEAN_ON),
            of("BOOLEAN NOT NULL DEFAULT 0", column("tinyint", "tinyint(1)").precision(3, 0).notNull()
                    .columnDefault("0"), SWITCHES_OFF),
            of("BOOLEAN NOT NULL DEFAULT 0, Boolean switch on", column("tinyint", "tinyint(1)").precision(3, 0)
                    .notNull().columnDefault("0"), BOOLEAN_ON),
            of("TINYINT UNSIGNED", column("tinyint", "tinyint unsigned").precision(3, 0), SWITCHES_OFF),
            of("TINYINT(1) UNSIGNED, Boolean switch on", column("tinyint", "tinyint(1) unsigned").precision(3, 0),
                    BOOLEAN_ON),
            of("SMALLINT", column("smallint", "smallint").precision(5, 0), SWITCHES_OFF),
            of("SMALLINT UNSIGNED", column("smallint", "smallint unsigned").precision(5, 0), SWITCHES_OFF),
            of("MEDIUMINT", column("mediumint", "mediumint").precision(7, 0), SWITCHES_OFF),
            of("MEDIUMINT UNSIGNED", column("mediumint", "mediumint unsigned").precision(7, 0), SWITCHES_OFF),
            of("INT", column("int", "int").precision(10, 0), SWITCHES_OFF),
            of("INT AUTO_INCREMENT", column("int", "int").precision(10, 0).notNull().extra("auto_increment"),
                    SWITCHES_OFF),
            of("INT UNSIGNED", column("int", "int unsigned").precision(10, 0), SWITCHES_OFF),
            of("INT(10) UNSIGNED ZEROFILL", column("int", "int(10) unsigned zerofill").precision(10, 0),
                    SWITCHES_OFF),
            of("BIGINT", column("bigint", "bigint").precision(19, 0), SWITCHES_OFF),
            of("BIGINT UNSIGNED", column("bigint", "bigint unsigned").precision(20, 0), SWITCHES_OFF),
            of("BIGINT UNSIGNED, Boolean switch on", column("bigint", "bigint unsigned").precision(20, 0),
                    BOOLEAN_ON),
            of("SERIAL", column("bigint", "bigint unsigned").precision(20, 0).notNull().extra("auto_increment"),
                    SWITCHES_OFF),
            of("FLOAT", column("float", "float").precision(12), SWITCHES_OFF),
            of("FLOAT UNSIGNED", column("float", "float unsigned").precision(12), SWITCHES_OFF),
            of("FLOAT(7,4)", column("float", "float(7,4)").precision(7, 4), SWITCHES_OFF),
            of("DOUBLE", column("double", "double").precision(22), SWITCHES_OFF),
            of("DOUBLE UNSIGNED", column("double", "double unsigned").precision(22), SWITCHES_OFF),
            of("REAL", column("double", "double").precision(22), SWITCHES_OFF),
            of("DECIMAL", column("decimal", "decimal(10,0)").precision(10, 0), SWITCHES_OFF),
            of("DECIMAL without reported precision or scale", column("decimal", "decimal"), SWITCHES_OFF),
            of("DECIMAL(5,2)", column("decimal", "decimal(5,2)").precision(5, 2), SWITCHES_OFF),
            of("NUMERIC(65,30)", column("decimal", "decimal(65,30)").precision(65, 30), SWITCHES_OFF),
            of("DECIMAL(5,2) UNSIGNED", column("decimal", "decimal(5,2) unsigned").precision(5, 2), SWITCHES_OFF),
            of("DECIMAL with precision but no scale", column("decimal", "decimal(5,2)").precision(5), SWITCHES_OFF),
            of("BIT(1)", column("bit", "bit(1)").precision(1), SWITCHES_OFF),
            of("BIT(1), Boolean switch on", column("bit", "bit(1)").precision(1), BOOLEAN_ON),
            of("BIT(7)", column("bit", "bit(7)").precision(7), SWITCHES_OFF),
            of("BIT(8)", column("bit", "bit(8)").precision(8), SWITCHES_OFF),
            of("BIT(64)", column("bit", "bit(64)").precision(64), SWITCHES_OFF),
            of("INT with unsigned flag contradicting column_type", column("int", "int").precision(10, 0)
                    .unsignedFlag(true), SWITCHES_OFF));

    private NumericMappingCases() {
    }
}
