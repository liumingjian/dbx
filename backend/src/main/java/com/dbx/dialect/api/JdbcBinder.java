package com.dbx.dialect.api;

/**
 * How the Sink binds the value into PostgreSQL, fixed by the Connect schema type the value travels as
 * (ADR-0008 §Contract). Grouped by the TP §6 family that introduced each constant; add yours to your group.
 */
public enum JdbcBinder {
    // numeric (TP §6.2)
    BYTE,
    SHORT,
    INT,
    LONG,
    BIG_DECIMAL,
    FLOAT,
    DOUBLE,
    BOOLEAN,

    // character, binary and special (TP §6.3)
    STRING,
    BYTES,

    // temporal (TP §6.4)
    DATE,
    TIME,
    TIMESTAMP
}
