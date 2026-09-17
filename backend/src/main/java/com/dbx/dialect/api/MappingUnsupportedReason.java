package com.dbx.dialect.api;

/** Why {@code pair.map} refused a column (TP §6.2–6.4). Owned by slice 3. */
public enum MappingUnsupportedReason implements UnsupportedReason {
    BIT_WIDTH_AT_LEAST_8,
    GEOMETRY,
    VECTOR,
    NOT_WHITELISTED
}
