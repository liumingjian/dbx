package com.dbx.dialect.pair;

/** TP §6.2's {@code data_type}s, decided by {@link NumericMapping}. */
enum NumericType implements MySqlDataType {
    TINYINT,
    SMALLINT,
    MEDIUMINT,
    INT,
    BIGINT,
    DECIMAL,
    FLOAT,
    DOUBLE,
    BIT
}
