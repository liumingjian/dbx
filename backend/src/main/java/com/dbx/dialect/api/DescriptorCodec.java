package com.dbx.dialect.api;

/**
 * Reads and writes exactly one version of a pair's descriptor, preserving that version's identity
 * and semantics rather than upgrading it (ADR-0008 §Registration). A codec writes only its own
 * version and reads only its own version; reading another version means selecting that version's
 * codec through {@code pair.descriptorCodec}, which refuses a version it cannot interpret.
 */
public non-sealed interface DescriptorCodec extends CodecSelection {

    DescriptorVersion version();

    /** Encodes the descriptor as this codec's version, whatever the installed pair's current one is. */
    EncodedDescriptor encode(PairDescriptor descriptor);

    /**
     * Decodes exactly what was recorded: the dialect ids, mapping version and certification version
     * come back unchanged, never replaced by the installed pair's.
     *
     * @throws IllegalArgumentException if {@code encoded} is stamped with another version or is not a
     *     well-formed encoding of this version; a frozen snapshot is never guessed at
     */
    PairDescriptor decode(EncodedDescriptor encoded);
}
