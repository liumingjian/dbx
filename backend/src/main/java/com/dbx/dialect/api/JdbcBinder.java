package com.dbx.dialect.api;

/** How the Sink binds the value into PostgreSQL (ADR-0008 §Contract). Owned by slice 3. */
public enum JdbcBinder {
    SHORT,
    INT,
    LONG,
    BIG_DECIMAL,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    STRING,
    BYTES,
    DATE,
    TIME,
    TIMESTAMP
}
