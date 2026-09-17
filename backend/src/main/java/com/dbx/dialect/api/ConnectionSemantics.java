package com.dbx.dialect.api;

import java.util.List;

/**
 * The fingerprinted Source Connector/J semantics of TP §6.5. Only the two task switches vary; every
 * other setting is a constant of this type, so no instance can weaken it. There is deliberately no
 * {@code defaultFetchSize}: the connector's {@code setFetchSize(batch.max.rows)} always wins (ADR-0033).
 *
 * @param tinyintOneAsBoolean the Boolean switch; drives both {@code tinyInt1isBit} and {@code transformedBitIsBoolean}
 * @param zeroDateTimeBehavior {@code EXCEPTION}, or {@code CONVERT_TO_NULL} under the zero-date switch
 */
public record ConnectionSemantics(boolean tinyintOneAsBoolean, ZeroDateTimeBehavior zeroDateTimeBehavior) {

    public static final boolean JDBC_COMPLIANT_TRUNCATION = true;
    public static final String CONNECTION_TIME_ZONE = "UTC";
    public static final boolean FORCE_CONNECTION_TIME_ZONE_TO_SESSION = true;
    public static final boolean PRESERVE_INSTANTS = true;
    /** Forced Unicode/UTF-8. */
    public static final boolean USE_UNICODE = true;
    public static final String CHARACTER_ENCODING = "UTF-8";
    /** Blob-to-string compatibility switches stay off. */
    public static final boolean BLOBS_ARE_STRINGS = false;
    public static final boolean FUNCTIONS_NEVER_RETURN_BLOBS = false;
    public static final boolean YEAR_IS_DATE_TYPE = true;
    /** ADR-0033: on every Source connection; not a DBA option and independent of table size. */
    public static final boolean USE_CURSOR_FETCH = true;

    public ConnectionSemantics {
        Checks.present(zeroDateTimeBehavior, "zeroDateTimeBehavior");
    }

    /** The Connector/J properties in TP §6.5 order. */
    public List<ConnectorProperty> properties() {
        return List.of(
                new ConnectorProperty.Flag("tinyInt1isBit", tinyintOneAsBoolean),
                new ConnectorProperty.Flag("transformedBitIsBoolean", tinyintOneAsBoolean),
                new ConnectorProperty.Flag("jdbcCompliantTruncation", JDBC_COMPLIANT_TRUNCATION),
                new ConnectorProperty.Text("zeroDateTimeBehavior", zeroDateTimeBehavior.name()),
                new ConnectorProperty.Text("connectionTimeZone", CONNECTION_TIME_ZONE),
                new ConnectorProperty.Flag("forceConnectionTimeZoneToSession", FORCE_CONNECTION_TIME_ZONE_TO_SESSION),
                new ConnectorProperty.Flag("preserveInstants", PRESERVE_INSTANTS),
                new ConnectorProperty.Flag("useUnicode", USE_UNICODE),
                new ConnectorProperty.Text("characterEncoding", CHARACTER_ENCODING),
                new ConnectorProperty.Flag("blobsAreStrings", BLOBS_ARE_STRINGS),
                new ConnectorProperty.Flag("functionsNeverReturnBlobs", FUNCTIONS_NEVER_RETURN_BLOBS),
                new ConnectorProperty.Flag("yearIsDateType", YEAR_IS_DATE_TYPE),
                new ConnectorProperty.Flag("useCursorFetch", USE_CURSOR_FETCH));
    }

    /** The {@code zeroDateTimeBehavior} values TP §6.5 permits; Connector/J's {@code ROUND} is not one. */
    public enum ZeroDateTimeBehavior {
        EXCEPTION,
        CONVERT_TO_NULL
    }
}
