package com.dbx.dialect.postgres;

import com.dbx.dialect.api.SqlValue;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The PostgreSQL 15 target dialect's single typed literal renderer (ADR-0008 §Plans as amended by #123).
 * It is the only place a value becomes SQL text, and only where PostgreSQL takes no bind parameter: DDL
 * {@code DEFAULT} and {@code CHECK}, and supplemental SQL for the DBA. It takes a closed {@link SqlValue},
 * never source text.
 *
 * <p>Strings are escape-string constants ({@code E'…'}), whose meaning does not depend on
 * {@code standard_conforming_strings}: {@code '} is doubled, {@code \} is doubled, and every control
 * character becomes {@code \}{@code uXXXX}, so a rendered literal is one line and a quote or backslash in
 * the value never ends it.
 */
final class PostgresLiteral {

    private PostgresLiteral() {
    }

    static String render(SqlValue value) {
        return switch (value) {
            case SqlValue.Null n -> "NULL";
            case SqlValue.Int64 i -> Long.toString(i.value());
            case SqlValue.Decimal d -> d.value().toPlainString();
            case SqlValue.Bool b -> b.value() ? "true" : "false";
            case SqlValue.Text t -> text(t.value());
            case SqlValue.Bytes b -> "E'\\\\x" + HexFormat.of().formatHex(b.value()) + "'::bytea";
        };
    }

    private static String text(String value) {
        StringBuilder out = new StringBuilder(value.length() + 3).append("E'");
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (codePoint == 0) {
                throw new IllegalArgumentException("PostgreSQL text cannot hold U+0000, so no literal can carry it");
            }
            if (Character.isSurrogate(value.charAt(i)) && Character.charCount(codePoint) == 1) {
                throw new IllegalArgumentException("an unpaired surrogate at index " + i + " is not a character "
                        + "PostgreSQL can store");
            }
            if (codePoint == '\'') {
                out.append("''");
            } else if (codePoint == '\\') {
                out.append("\\\\");
            } else if (Character.getType(codePoint) == Character.CONTROL) {
                out.append(String.format(Locale.ROOT, "\\u%04X", codePoint));
            } else {
                out.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return out.append('\'').toString();
    }
}
