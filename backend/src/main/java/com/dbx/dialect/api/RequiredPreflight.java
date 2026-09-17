package com.dbx.dialect.api;

/** The column-level exact preflights of TP §6.6 checks 1–6 a mapping can require. Owned by slice 3. */
public enum RequiredPreflight {
    LARGE_RECORD_ENVELOPE,
    BOOLEAN_VALUES_ZERO_OR_ONE,
    UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE,
    TIME_WITHIN_DAY,
    ENUM_VALUE_DECLARED,
    NO_ZERO_DATE
}
