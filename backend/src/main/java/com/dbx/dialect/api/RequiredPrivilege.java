package com.dbx.dialect.api;

/** A privilege a plan needs, so capability checks can prove it before use (ADR-0006 §Capability checks). */
public enum RequiredPrivilege {
    SOURCE_READ_METADATA,
    SOURCE_SELECT,
    TARGET_READ_CATALOG,
    TARGET_CREATE_SCHEMA,
    TARGET_CREATE_IN_SCHEMA,
    TARGET_OWN_OBJECT
}
