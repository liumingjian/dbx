package com.dbx.dialect.pair;

/** TP §6.4's {@code data_type}s, decided by {@link TemporalMapping}. */
enum TemporalType implements MySqlDataType {
    DATE,
    DATETIME,
    TIMESTAMP,
    TIME,
    YEAR
}
