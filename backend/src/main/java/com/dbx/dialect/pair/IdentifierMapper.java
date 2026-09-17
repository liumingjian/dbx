package com.dbx.dialect.pair;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.IdentifierMapping;
import com.dbx.dialect.api.IdentifierUnsupportedReason;
import com.dbx.dialect.api.MappingRule;
import com.dbx.dialect.api.RenameAlgorithmVersion;
import com.dbx.dialect.api.SchemaCoordinate;
import com.dbx.dialect.api.SourceCoordinate;
import com.dbx.dialect.api.TableCoordinate;
import com.dbx.dialect.api.TargetIdentifier;
import com.dbx.dialect.api.Unsupported;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Byte-counted identifier mapping and the deterministic rename of TP §7.1. A name is taken only from
 * the typed coordinate; nothing is parsed out of a topic, connector or exception string.
 */
final class IdentifierMapper {

    /** PostgreSQL's identifier limit, {@code NAMEDATALEN - 1}, in UTF-8 bytes. */
    private static final int MAX_IDENTIFIER_BYTES = 63;

    /** Version 1 of {@code <utf8-prefix>_<hash12>}. Any change to the output needs a new version. */
    private static final RenameAlgorithmVersion RENAME_V1 = new RenameAlgorithmVersion(1);

    private static final int HASH_HEX_CHARACTERS = 12;

    /** Room left for the prefix once {@code _<hash12>} is appended. */
    private static final int MAX_PREFIX_BYTES = MAX_IDENTIFIER_BYTES - 1 - HASH_HEX_CHARACTERS;

    private IdentifierMapper() {
    }

    static IdentifierMapping mapIdentifier(SourceCoordinate sourceCoordinate, Optional<MappingRule> mappingRule) {
        Objects.requireNonNull(sourceCoordinate, "sourceCoordinate is required");
        Objects.requireNonNull(mappingRule, "mappingRule is required; pass Optional.empty() for none");
        if (mappingRule.isPresent()) {
            // Ignoring a rule silently would be a default-success path (ADR-0008 §Ownership).
            throw new UnsupportedOperationException("pair.mapIdentifier derives names from the source coordinate "
                    + "only (TP §7.1); applying a mapping rule is not decided by slice 4 of docs/spec/dialect.md: "
                    + mappingRule.get());
        }
        String name = switch (sourceCoordinate) {
            case SchemaCoordinate schema -> schema.database();
            case TableCoordinate table -> table.table();
            case ColumnCoordinate column -> column.column();
        };
        if (utf8Length(name) <= MAX_IDENTIFIER_BYTES) {
            return new IdentifierMapping.Exact(sourceCoordinate, new TargetIdentifier(name));
        }
        if (sourceCoordinate instanceof ColumnCoordinate) {
            // The Sink must address the exact Connect field; pruning is the only v1 escape (TP §7.1).
            return new Unsupported(IdentifierUnsupportedReason.COLUMN_NAME_OVER_63_BYTES, sourceCoordinate);
        }
        String renamed = utf8Prefix(name, MAX_PREFIX_BYTES) + "_" + hash12(sourceCoordinate);
        return new IdentifierMapping.Renamed(sourceCoordinate, new TargetIdentifier(renamed), RENAME_V1);
    }

    /** The longest leading run of whole code points that fits in {@code maxBytes} of UTF-8. */
    private static String utf8Prefix(String name, int maxBytes) {
        int bytes = 0;
        int end = 0;
        while (end < name.length()) {
            int codePoint = name.codePointAt(end);
            bytes += utf8Length(codePoint);
            if (bytes > maxBytes) {
                break;
            }
            end += Character.charCount(codePoint);
        }
        return name.substring(0, end);
    }

    private static int utf8Length(String name) {
        return name.codePoints().map(IdentifierMapper::utf8Length).sum();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    /**
     * First 12 lowercase hex characters of SHA-256 over the coordinate's names, database first, each
     * encoded as its UTF-8 byte length (4 bytes, big-endian) followed by those bytes.
     */
    private static String hash12(SourceCoordinate coordinate) {
        List<String> names = switch (coordinate) {
            case SchemaCoordinate schema -> List.of(schema.database());
            case TableCoordinate table -> List.of(table.database(), table.table());
            case ColumnCoordinate column -> throw new IllegalArgumentException("columns are never renamed: " + column);
        };
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String name : names) {
                byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
                out.writeInt(utf8.length);
                out.write(utf8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return HexFormat.of().formatHex(digest).substring(0, HASH_HEX_CHARACTERS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }
}
