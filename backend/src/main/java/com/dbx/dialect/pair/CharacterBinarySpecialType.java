package com.dbx.dialect.pair;

/** TP §6.3's {@code data_type}s, decided by {@link CharacterBinarySpecialMapping}. */
enum CharacterBinarySpecialType implements MySqlDataType {
    CHAR,
    VARCHAR,
    TINYTEXT,
    TEXT,
    MEDIUMTEXT,
    LONGTEXT,
    BINARY,
    VARBINARY,
    TINYBLOB,
    BLOB,
    MEDIUMBLOB,
    LONGBLOB,
    ENUM,
    SET,
    JSON,
    GEOMETRY,
    POINT,
    LINESTRING,
    POLYGON,
    MULTIPOINT,
    MULTILINESTRING,
    MULTIPOLYGON,
    GEOMCOLLECTION,
    VECTOR
}
