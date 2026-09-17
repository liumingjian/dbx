package com.dbx.dialect.pair;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.CodecSelection;
import com.dbx.dialect.api.DescriptorVersion;
import com.dbx.dialect.api.PairDescriptor;

/** The pair's registered identity and its versioned descriptor codecs (ADR-0008 §Registration). Slice 2. */
final class PairRegistration {

    private PairRegistration() {
    }

    static PairDescriptor descriptor() {
        throw new NotImplementedInSlice("pair.descriptor", 2);
    }

    static CodecSelection descriptorCodec(DescriptorVersion version) {
        throw new NotImplementedInSlice("pair.descriptorCodec", 2);
    }
}
