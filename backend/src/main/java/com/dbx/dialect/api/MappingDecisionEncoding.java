package com.dbx.dialect.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The canonical encoding behind {@link MappingDecision#mappingFingerprint()}: length-prefixed UTF-8
 * strings, big-endian counts, list order kept (the lists of a decision are ordered on purpose). A
 * change here bumps {@link #ENCODING} so an old fingerprint is never compared to a new one.
 */
final class MappingDecisionEncoding {

    static final String ENCODING = "dbx.dialect.MappingDecision/1";

    private MappingDecisionEncoding() {
    }

    static MappingFingerprint fingerprint(MappingDecision decision) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            string(out, ENCODING);
            switch (decision) {
                case Supported supported -> supported(out, supported);
                case Unsupported unsupported -> unsupported(out, unsupported);
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return new MappingFingerprint(HexFormat.of().formatHex(sha256().digest(bytes.toByteArray())));
    }

    private static void supported(DataOutputStream out, Supported s) throws IOException {
        string(out, "SUPPORTED");
        targetType(out, s.targetType());
        string(out, s.extractionIntent().name());
        connect(out, s.connectRepresentation());
        string(out, s.jdbcBinder().name());
        string(out, s.valueSemantics().name());
        names(out, s.requiredPreflights());
        names(out, s.contractEffects());
        out.writeInt(s.alternatives().size());
        for (MappingAlternative alternative : s.alternatives()) {
            targetType(out, alternative.targetType());
            connect(out, alternative.connectRepresentation());
            string(out, alternative.jdbcBinder().name());
            string(out, alternative.valueSemantics().name());
        }
        names(out, s.notices());
    }

    private static void unsupported(DataOutputStream out, Unsupported u) throws IOException {
        string(out, "UNSUPPORTED");
        string(out, u.reason().getClass().getSimpleName());
        string(out, u.reason().name());
        if (u.evidence() instanceof SourceColumn column) {
            sourceColumn(out, column);
        } else {
            // pair.map only refuses with a SourceColumn; other evidence is a record whose toString is
            // a deterministic rendering of its components.
            string(out, u.evidence().getClass().getSimpleName());
            string(out, u.evidence().toString());
        }
    }

    private static void sourceColumn(DataOutputStream out, SourceColumn c) throws IOException {
        string(out, "SourceColumn");
        string(out, c.coordinate().database());
        string(out, c.coordinate().table());
        string(out, c.coordinate().column());
        string(out, c.dataType());
        string(out, c.columnType());
        out.writeBoolean(c.unsigned());
        optionalLong(out, c.characterMaximumLength());
        optionalLong(out, c.characterOctetLength());
        optionalInt(out, c.numericPrecision());
        optionalInt(out, c.numericScale());
        optionalInt(out, c.datetimePrecision());
        optionalString(out, c.characterSetName());
        optionalString(out, c.collationName());
        string(out, c.nullability().name());
        optionalString(out, c.columnDefault());
        string(out, c.extra());
        out.writeInt(c.ordinalPosition());
    }

    private static void targetType(DataOutputStream out, TargetType type) throws IOException {
        string(out, type.name().name());
        out.writeInt(type.modifiers().size());
        for (int modifier : type.modifiers()) {
            out.writeInt(modifier);
        }
    }

    private static void connect(DataOutputStream out, ConnectRepresentation connect) throws IOException {
        string(out, connect.schemaType().name());
        optionalString(out, connect.logicalType().map(Enum::name));
    }

    private static void names(DataOutputStream out, List<? extends Enum<?>> values) throws IOException {
        out.writeInt(values.size());
        for (Enum<?> value : values) {
            string(out, value.name());
        }
    }

    private static void optionalString(DataOutputStream out, Optional<String> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            string(out, value.get());
        }
    }

    private static void optionalInt(DataOutputStream out, OptionalInt value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            out.writeInt(value.getAsInt());
        }
    }

    private static void optionalLong(DataOutputStream out, OptionalLong value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            out.writeLong(value.getAsLong());
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absentFromTheJdk) {
            throw new IllegalStateException("every Java platform must provide SHA-256", absentFromTheJdk);
        }
    }
}
