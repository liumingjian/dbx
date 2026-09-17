package com.dbx.dialect.pair;

import com.dbx.dialect.api.CertificationVersion;
import com.dbx.dialect.api.CodecSelection;
import com.dbx.dialect.api.CodecUnsupportedReason;
import com.dbx.dialect.api.DescriptorVersion;
import com.dbx.dialect.api.MappingVersion;
import com.dbx.dialect.api.PairDescriptor;
import com.dbx.dialect.api.Unsupported;
import com.dbx.dialect.mysql.MySql80SourceDialect;
import com.dbx.dialect.postgres.Postgres15TargetDialect;

/** The pair's registered identity and its versioned descriptor codecs (ADR-0008 §Registration). */
final class PairRegistration {

    /**
     * Bump the mapping version when a mapping rule changes, the certification version when the pair is
     * recertified. Existing snapshots keep the versions they recorded.
     */
    private static final PairDescriptor DESCRIPTOR = new PairDescriptor(
            MySql80SourceDialect.ID, Postgres15TargetDialect.ID, new MappingVersion(1), new CertificationVersion(1));

    private PairRegistration() {
    }

    static PairDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * One case per version this build can read. A version without its own case is refused, never read
     * by a neighbouring codec: there is no default branch that could upgrade it silently.
     */
    static CodecSelection descriptorCodec(DescriptorVersion version) {
        return switch (version.value()) {
            case 1 -> DescriptorCodecV1.INSTANCE;
            default -> new Unsupported(CodecUnsupportedReason.UNKNOWN_DESCRIPTOR_VERSION, version);
        };
    }
}
