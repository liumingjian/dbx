package com.dbx.dialect.api;

/** {@code pair.descriptorCodec}'s closed result: {@link DescriptorCodec} or {@link Unsupported}. */
public sealed interface CodecSelection permits DescriptorCodec, Unsupported {
}
