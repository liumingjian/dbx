package com.dbx.dialect.api;

import java.util.Optional;

/** The Connect/Avro schema a value travels as (TP §6.2–6.4). Owned by slice 3. */
public record ConnectRepresentation(SchemaType schemaType, Optional<LogicalType> logicalType) {

    public ConnectRepresentation {
        Checks.present(schemaType, "schemaType");
        Checks.present(logicalType, "logicalType");
    }

    public enum SchemaType {
        INT8,
        INT16,
        INT32,
        INT64,
        FLOAT32,
        FLOAT64,
        BOOLEAN,
        STRING,
        BYTES
    }

    public enum LogicalType {
        DECIMAL,
        DATE,
        TIME,
        TIMESTAMP
    }
}
