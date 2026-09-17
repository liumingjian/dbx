package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.BOOLEAN_ON;
import static com.dbx.dialect.api.MappingCase.SWITCHES_OFF;
import static com.dbx.dialect.api.MappingCase.ZERO_DATE_ON;
import static com.dbx.dialect.api.MappingCase.of;
import static com.dbx.dialect.api.SourceColumns.column;

import java.util.List;

/**
 * Golden rows for TP §6.3, as MySQL 8.0 reports them in {@code information_schema.COLUMNS}. One row
 * per matrix row and source-metadata variant (ADR-0022 §Golden files). Rendered to
 * {@code character-binary-special.txt}.
 */
final class CharacterBinarySpecialMappingCases {

    private static final String UTF8MB4 = "utf8mb4";
    private static final String UTF8MB4_CI = "utf8mb4_0900_ai_ci";
    private static final MappingOptions BOTH_ON = new MappingOptions(true, true);

    static final List<MappingCase> CASES = List.of(
            of("CHAR(10)", column("char", "char(10)").characterLength(10, 40).charset(UTF8MB4, UTF8MB4_CI),
                    SWITCHES_OFF),
            of("CHAR(255) latin1", column("char", "char(255)").characterLength(255, 255)
                    .charset("latin1", "latin1_swedish_ci"), SWITCHES_OFF),
            of("CHAR(0)", column("char", "char(0)").characterLength(0, 0).charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("VARCHAR(255)", column("varchar", "varchar(255)").characterLength(255, 1020)
                    .charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("VARCHAR(255), both switches on", column("varchar", "varchar(255)").characterLength(255, 1020)
                    .charset(UTF8MB4, UTF8MB4_CI), BOTH_ON),
            of("VARCHAR(64) utf8mb4_bin collation", column("varchar", "varchar(64)").characterLength(64, 256)
                    .charset(UTF8MB4, "utf8mb4_bin"), SWITCHES_OFF),
            of("VARCHAR(16) NOT NULL DEFAULT ''", column("varchar", "varchar(16)").characterLength(16, 64)
                    .charset(UTF8MB4, UTF8MB4_CI).notNull().columnDefault(""), SWITCHES_OFF),
            of("VARCHAR(16) binary character set", column("varchar", "varchar(16)").characterLength(16, 16)
                    .charset("binary", "binary"), SWITCHES_OFF),
            of("CHAR(4) binary character set", column("char", "char(4)").characterLength(4, 4)
                    .charset("binary", "binary"), SWITCHES_OFF),
            of("VARCHAR(16) binary character set with a text collation", column("varchar", "varchar(16)")
                    .characterLength(16, 16).charset("binary", "utf8mb4_bin"), SWITCHES_OFF),
            of("VARCHAR(16) without character set facts", column("varchar", "varchar(16)").characterLength(16, 64),
                    SWITCHES_OFF),
            of("VARCHAR(16) length disagreeing with column_type", column("varchar", "varchar(16)")
                    .characterLength(32, 128).charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("TINYTEXT", column("tinytext", "tinytext").characterLength(255, 255).charset(UTF8MB4, UTF8MB4_CI),
                    SWITCHES_OFF),
            of("TEXT", column("text", "text").characterLength(65535, 65535).charset(UTF8MB4, UTF8MB4_CI),
                    SWITCHES_OFF),
            of("MEDIUMTEXT", column("mediumtext", "mediumtext").characterLength(16777215, 16777215)
                    .charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("LONGTEXT", column("longtext", "longtext").characterLength(4294967295L, 4294967295L)
                    .charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("LONGTEXT binary character set", column("longtext", "longtext")
                    .characterLength(4294967295L, 4294967295L).charset("binary", "binary"), SWITCHES_OFF),
            of("BINARY(16)", column("binary", "binary(16)").characterLength(16, 16), SWITCHES_OFF),
            of("VARBINARY(255)", column("varbinary", "varbinary(255)").characterLength(255, 255), SWITCHES_OFF),
            of("VARBINARY(255) with a text character set", column("varbinary", "varbinary(255)")
                    .characterLength(255, 255).charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("TINYBLOB", column("tinyblob", "tinyblob").characterLength(255, 255), SWITCHES_OFF),
            of("BLOB", column("blob", "blob").characterLength(65535, 65535), SWITCHES_OFF),
            of("MEDIUMBLOB", column("mediumblob", "mediumblob").characterLength(16777215, 16777215), SWITCHES_OFF),
            of("LONGBLOB", column("longblob", "longblob").characterLength(4294967295L, 4294967295L), SWITCHES_OFF),
            of("ENUM('small','medium','large')", column("enum", "enum('small','medium','large')")
                    .characterLength(6, 24).charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("ENUM('it''s','') NOT NULL DEFAULT ''", column("enum", "enum('it''s','')").characterLength(4, 16)
                    .charset(UTF8MB4, UTF8MB4_CI).notNull().columnDefault(""), SWITCHES_OFF),
            of("ENUM binary character set", column("enum", "enum('a','b')").characterLength(1, 1)
                    .charset("binary", "binary"), SWITCHES_OFF),
            of("ENUM without members", column("enum", "enum").charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("SET('read','write','admin')", column("set", "set('read','write','admin')").characterLength(16, 64)
                    .charset(UTF8MB4, UTF8MB4_CI), SWITCHES_OFF),
            of("JSON", column("json", "json"), SWITCHES_OFF),
            of("JSON, both switches on", column("json", "json"), BOTH_ON),
            of("GEOMETRY", column("geometry", "geometry"), SWITCHES_OFF),
            of("POINT", column("point", "point"), SWITCHES_OFF),
            of("LINESTRING", column("linestring", "linestring"), SWITCHES_OFF),
            of("POLYGON", column("polygon", "polygon"), SWITCHES_OFF),
            of("MULTIPOINT", column("multipoint", "multipoint"), SWITCHES_OFF),
            of("MULTILINESTRING", column("multilinestring", "multilinestring"), SWITCHES_OFF),
            of("MULTIPOLYGON", column("multipolygon", "multipolygon"), SWITCHES_OFF),
            of("GEOMETRYCOLLECTION", column("geomcollection", "geomcollection"), SWITCHES_OFF),
            of("VECTOR(2048), MySQL 9.0+", column("vector", "vector(2048)").characterLength(8192, 8192), BOOLEAN_ON),
            of("CHAR(10) UNSIGNED, contradictory", column("char", "char(10) unsigned").characterLength(10, 40)
                    .charset(UTF8MB4, UTF8MB4_CI), ZERO_DATE_ON));

    private CharacterBinarySpecialMappingCases() {
    }
}
