package com.dbx.dialect.api;

/**
 * An approved target name, unquoted and exact. It is the only thing SQL may take an identifier from
 * (ADR-0008 §Plans; TP §7.1), and {@link #quoted()} is the one place that turns it into SQL text.
 */
public record TargetIdentifier(String name) {

    public TargetIdentifier {
        Checks.nonEmpty(name, "name");
    }

    /**
     * The name as a double-quoted identifier. Quoting is unconditional, so reserved words need no
     * special path. An embedded {@code "} is doubled; every other character, backslash and newline
     * included, is literal inside a quoted identifier and stays as it is (TP §7.1).
     */
    public String quoted() {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
