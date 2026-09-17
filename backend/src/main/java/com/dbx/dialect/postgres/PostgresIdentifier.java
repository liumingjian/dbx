package com.dbx.dialect.postgres;

import com.dbx.dialect.api.TargetIdentifier;
import com.dbx.dialect.api.TargetTableCoordinate;

/**
 * PostgreSQL's identifier limit and the one place in this dialect a {@link TargetIdentifier} becomes SQL text
 * (TP §7.1; ADR-0008 §Plans). The pair's identifier mapping counts bytes with the same functions, so the name
 * it approves is the name DDL and supplemental SQL accept.
 */
public final class PostgresIdentifier {

    /** {@code NAMEDATALEN - 1}, in UTF-8 bytes. PostgreSQL silently truncates a longer identifier. */
    public static final int MAX_BYTES = 63;

    private PostgresIdentifier() {
    }

    /** UTF-8 length of {@code name}, counted per code point. */
    public static int utf8Bytes(String name) {
        return name.codePoints().map(PostgresIdentifier::utf8Bytes).sum();
    }

    /** UTF-8 length of one code point. */
    public static int utf8Bytes(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    /**
     * {@link TargetIdentifier#quoted()}, refusing a name over {@link #MAX_BYTES}: truncated, it would create or
     * address a different object than the approved one.
     */
    static String quoted(TargetIdentifier identifier) {
        int bytes = utf8Bytes(identifier.name());
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException("TP §7.1: PostgreSQL truncates identifiers beyond 63 bytes, so "
                    + identifier.quoted() + " (" + bytes + " bytes) would create a different object");
        }
        return identifier.quoted();
    }

    /** {@code "schema"."name"}. */
    static String qualified(TargetIdentifier schema, TargetIdentifier name) {
        return quoted(schema) + "." + quoted(name);
    }

    /** {@code "schema"."table"}. */
    static String qualified(TargetTableCoordinate table) {
        return qualified(table.schema(), table.name());
    }
}
