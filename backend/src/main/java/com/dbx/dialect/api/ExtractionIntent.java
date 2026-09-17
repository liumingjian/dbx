package com.dbx.dialect.api;

/** How the Source reads the column (TP §6.1, §6.5). Owned by slice 3. */
public enum ExtractionIntent {
    AS_DECLARED,
    TINYINT_ONE_AS_BOOLEAN,
    BINARY_CHARACTER_AS_BYTES,
    YEAR_AS_DATE,
    ZERO_DATE_AS_NULL
}
