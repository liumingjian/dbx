package com.dbx.dialect.api;

/**
 * An approved target name, unquoted and exact. It is the only thing a plan may quote an identifier
 * from (ADR-0008 §Plans; TP §7.1); quoting itself is the target dialect's, landed by slice 4.
 */
public record TargetIdentifier(String name) {

    public TargetIdentifier {
        Checks.nonEmpty(name, "name");
    }
}
