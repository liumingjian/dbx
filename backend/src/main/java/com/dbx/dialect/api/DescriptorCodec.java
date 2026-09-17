package com.dbx.dialect.api;

/**
 * Reads and writes one version of a pair's dialect descriptors, preserving that version's identity
 * and semantics rather than upgrading it (ADR-0008 §Registration). The read and write operations
 * are shaped by slice 2, together with the descriptors they carry.
 */
public non-sealed interface DescriptorCodec extends CodecSelection {

    DescriptorVersion version();
}
