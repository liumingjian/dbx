package com.dbx.dialect.api;

import java.util.List;

/**
 * The JDBC Sink settings obligation 29 fixes (ADR-0011 §Sink contract; TP §6.5). There is exactly one
 * value, {@link #V1}, and no constructor outside this type, so no other Sink configuration is expressible.
 */
public final class SinkSettings {

    /** The only Sink settings v1 has. */
    public static final SinkSettings V1 = new SinkSettings();

    private SinkSettings() {
    }

    /** ADR-0011 §Sink contract's properties in its order, then UTC session semantics (TP §6.5). */
    public List<ConnectorProperty> properties() {
        return List.of(
                new ConnectorProperty.Flag("auto.create", false),
                new ConnectorProperty.Flag("auto.evolve", false),
                new ConnectorProperty.Text("insert.mode", "insert"),
                new ConnectorProperty.Text("pk.mode", "none"),
                new ConnectorProperty.Flag("delete.enabled", false),
                new ConnectorProperty.Text("quote.sql.identifiers", "always"),
                new ConnectorProperty.Text("db.timezone", "UTC"));
    }

    @Override
    public String toString() {
        return "SinkSettings" + properties();
    }
}
