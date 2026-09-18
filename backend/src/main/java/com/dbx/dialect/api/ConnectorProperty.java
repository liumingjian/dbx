package com.dbx.dialect.api;

/**
 * One configuration property as a typed record, never a {@code Map<String, Object>} entry (obligation 2).
 * {@link ConnectionSemantics} and {@link SinkSettings} render their settings as an ordered list of these;
 * the order is part of what is pinned.
 */
public sealed interface ConnectorProperty permits ConnectorProperty.Flag, ConnectorProperty.Text {

    String name();

    /** The value exactly as the configuration carries it. */
    String value();

    /** {@code name=value}. */
    default String rendered() {
        return name() + "=" + value();
    }

    /** A boolean property, rendered {@code true} or {@code false}. */
    record Flag(String name, boolean enabled) implements ConnectorProperty {

        public Flag {
            Checks.nonEmpty(name, "name");
        }

        @Override
        public String value() {
            return Boolean.toString(enabled);
        }
    }

    /** A property whose value is a fixed token, such as {@code UTC} or {@code insert}. */
    record Text(String name, String value) implements ConnectorProperty {

        public Text {
            Checks.nonEmpty(name, "name");
            Checks.nonEmpty(value, "value");
        }
    }
}
