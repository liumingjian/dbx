package com.dbx.dialect.api;

import java.util.List;

/**
 * One statement of a {@link SqlPlan}: SQL text with {@code ?} placeholders and exactly one bound
 * value per placeholder (ADR-0008 §Plans). A {@code ?} inside a quoted identifier or a quoted
 * literal is text, not a placeholder, so a hostile identifier cannot shift the bindings.
 */
public record ParameterizedStatement(String sql, List<SqlValue> parameters) {

    public ParameterizedStatement {
        Checks.nonEmpty(sql, "sql");
        parameters = Checks.list(parameters, "parameters");
        int placeholders = placeholdersIn(sql);
        if (placeholders != parameters.size()) {
            throw new IllegalArgumentException("ADR-0008 §Plans: every value is bound, so the statement needs one "
                    + "parameter per placeholder; it has " + placeholders + " placeholders and "
                    + parameters.size() + " parameters");
        }
    }

    /** Counts {@code ?} outside {@code "…"}, {@code `…`} and {@code '…'}; a doubled quote stays inside. */
    private static int placeholdersIn(String sql) {
        int count = 0;
        char open = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (open != 0) {
                if (c == open) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == open) {
                        i++;
                    } else {
                        open = 0;
                    }
                }
            } else if (c == '"' || c == '`' || c == '\'') {
                open = c;
            } else if (c == '?') {
                count++;
            }
        }
        if (open != 0) {
            throw new IllegalArgumentException("unterminated " + open + " quote in statement: " + sql);
        }
        return count;
    }
}
