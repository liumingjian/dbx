package com.dbx.dialect.pair;

import com.dbx.dialect.api.CertificationVersion;
import com.dbx.dialect.api.DescriptorCodec;
import com.dbx.dialect.api.DescriptorVersion;
import com.dbx.dialect.api.DialectId;
import com.dbx.dialect.api.EncodedDescriptor;
import com.dbx.dialect.api.MappingVersion;
import com.dbx.dialect.api.PairDescriptor;

/**
 * Version 1 of the pair descriptor encoding. Frozen: once a snapshot holds this text, this class must
 * keep reading it exactly. A new shape is a new version with its own codec, never an edit here.
 *
 * <p>Format: {@code dbx.dialect.PairDescriptor/1} then, each after {@code |}, the source and target
 * dialect ids as {@code <length>:<id>} (length in UTF-16 code units, so any character survives), the
 * mapping version and the certification version as decimal integers.
 */
final class DescriptorCodecV1 implements DescriptorCodec {

    static final DescriptorCodecV1 INSTANCE = new DescriptorCodecV1();

    private static final DescriptorVersion VERSION = new DescriptorVersion(1);
    private static final String HEADER = "dbx.dialect.PairDescriptor/1";

    private DescriptorCodecV1() {
    }

    @Override
    public DescriptorVersion version() {
        return VERSION;
    }

    @Override
    public EncodedDescriptor encode(PairDescriptor descriptor) {
        String text = HEADER
                + '|' + lengthPrefixed(descriptor.sourceDialect().value())
                + '|' + lengthPrefixed(descriptor.targetDialect().value())
                + '|' + descriptor.mappingVersion().value()
                + '|' + descriptor.certificationVersion().value();
        return new EncodedDescriptor(VERSION, text);
    }

    @Override
    public PairDescriptor decode(EncodedDescriptor encoded) {
        if (!encoded.version().equals(VERSION)) {
            throw new IllegalArgumentException("ADR-0008 §Registration: the version 1 descriptor codec does not read "
                    + "a version " + encoded.version().value() + " descriptor; select that version's codec");
        }
        Reader reader = new Reader(encoded.text());
        reader.expect(HEADER);
        reader.expect("|");
        DialectId source = new DialectId(reader.lengthPrefixed());
        reader.expect("|");
        DialectId target = new DialectId(reader.lengthPrefixed());
        reader.expect("|");
        MappingVersion mapping = new MappingVersion(reader.integer());
        reader.expect("|");
        CertificationVersion certification = new CertificationVersion(reader.integer());
        reader.end();
        return new PairDescriptor(source, target, mapping, certification);
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    /** A strict reader: anything it did not expect is a malformed snapshot, not something to repair. */
    private static final class Reader {

        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        void expect(String literal) {
            if (!text.startsWith(literal, position)) {
                throw malformed("expected \"" + literal + "\"");
            }
            position += literal.length();
        }

        String lengthPrefixed() {
            int length = integer();
            expect(":");
            if (length > text.length() - position) {
                throw malformed("an id of length " + length + " runs past the end");
            }
            String value = text.substring(position, position + length);
            position += length;
            return value;
        }

        int integer() {
            int start = position;
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') {
                position++;
            }
            String digits = text.substring(start, position);
            if (digits.isEmpty() || (digits.length() > 1 && digits.charAt(0) == '0') || digits.length() > 9) {
                throw malformed("expected a canonical decimal integer");
            }
            return Integer.parseInt(digits);
        }

        void end() {
            if (position != text.length()) {
                throw malformed("unexpected trailing text");
            }
        }

        private IllegalArgumentException malformed(String what) {
            return new IllegalArgumentException("ADR-0008 §Registration: not a well-formed version 1 pair descriptor ("
                    + what + " at offset " + position + ")");
        }
    }
}
