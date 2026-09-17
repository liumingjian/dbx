package com.dbx.dialect.api;

/** Why {@code pair.mapIdentifier} refused a name (TP §7.1). Owned by slice 4. */
public enum IdentifierUnsupportedReason implements UnsupportedReason {
    COLUMN_NAME_OVER_63_BYTES
}
