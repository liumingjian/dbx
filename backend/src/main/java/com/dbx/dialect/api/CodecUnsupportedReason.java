package com.dbx.dialect.api;

/** Why {@code pair.descriptorCodec} refused a version (ADR-0008 §Registration). Owned by slice 2. */
public enum CodecUnsupportedReason implements UnsupportedReason {
    UNKNOWN_DESCRIPTOR_VERSION
}
