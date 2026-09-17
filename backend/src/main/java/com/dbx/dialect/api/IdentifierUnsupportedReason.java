package com.dbx.dialect.api;

/** Why {@code pair.mapIdentifier} refused a name (TP §7.1). Owned by slice 4. */
public enum IdentifierUnsupportedReason implements UnsupportedReason {
    COLUMN_NAME_OVER_63_BYTES,
    /**
     * A mapping rule was passed. No slice of docs/spec/dialect.md applies one here: a {@code USER} rule
     * overrides an {@code AUTO} one in contract assembly, and prune and rename take effect in
     * {@code source.queryProjection} (TP §7.1). Refused rather than silently ignored (ADR-0008 §Ownership).
     */
    MAPPING_RULE_NOT_SUPPORTED_IN_V1
}
