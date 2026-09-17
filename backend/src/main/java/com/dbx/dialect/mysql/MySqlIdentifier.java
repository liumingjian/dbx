package com.dbx.dialect.mysql;

/**
 * The one place a MySQL source name becomes SQL text: the source counterpart of
 * {@code TargetIdentifier.quoted()} ({@code docs/spec/dialect.md} obligation 5; TP §7.1). Every
 * source-side renderer — capability plans, the query projection, supplemental comments — calls
 * {@link #quoted(String)}; nothing else in {@code dialect} quotes a source name, and no other module
 * can reach this package (ADR-0018 rule 1).
 */
public final class MySqlIdentifier {

    private MySqlIdentifier() {
    }

    /**
     * The exact source name as a backtick-quoted identifier. Quoting is unconditional, so reserved
     * words need no special path. An embedded {@code `} is doubled; every other character — double
     * quote, single quote, backslash, {@code ;}, {@code --}, {@code /*}, newline — is literal inside a
     * backtick-quoted identifier and stays as it is. A name holding U+0000 is refused: MySQL permits
     * no such identifier, so it cannot have come from the source catalog.
     */
    public static String quoted(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("TP §7.1: a MySQL identifier is never empty");
        }
        if (name.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("TP §7.1: a MySQL identifier cannot contain U+0000, so this name "
                    + "did not come from the source catalog");
        }
        return '`' + name.replace("`", "``") + '`';
    }
}
