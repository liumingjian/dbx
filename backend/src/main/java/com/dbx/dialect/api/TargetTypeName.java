package com.dbx.dialect.api;

/** The PostgreSQL 15 types TP §6.2–6.4 map to. Owned by slice 3. */
public enum TargetTypeName {
    SMALLINT,
    INTEGER,
    BIGINT,
    NUMERIC,
    REAL,
    DOUBLE_PRECISION,
    BOOLEAN,
    CHAR,
    VARCHAR,
    TEXT,
    BYTEA,
    JSON,
    JSONB,
    DATE,
    TIME,
    TIMESTAMP,
    TIMESTAMPTZ
}
