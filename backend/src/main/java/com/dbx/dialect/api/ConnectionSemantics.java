package com.dbx.dialect.api;

/**
 * The fingerprinted Source Connector/J semantics that follow the task switches (TP §6.5). The fixed
 * settings — UTC, UTF-8, cursor fetch, truncation — are added by slice 5.
 */
public record ConnectionSemantics(boolean tinyInt1isBit, boolean transformedBitIsBoolean, ZeroDateTimeBehavior zeroDateTimeBehavior) {

    public ConnectionSemantics {
        Checks.present(zeroDateTimeBehavior, "zeroDateTimeBehavior");
    }

    public enum ZeroDateTimeBehavior {
        EXCEPTION,
        CONVERT_TO_NULL
    }
}
