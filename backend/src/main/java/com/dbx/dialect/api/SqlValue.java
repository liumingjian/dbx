package com.dbx.dialect.api;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * A typed value bound as a statement parameter, never spliced into SQL text (ADR-0008 §Plans). A
 * later slice that needs another type adds a variant here, and the compiler then points at every
 * switch that has to learn it — the fingerprint encoding among them.
 */
public sealed interface SqlValue {

    enum Type {
        INT64,
        DECIMAL,
        TEXT,
        BYTES,
        BOOLEAN
    }

    /** A typed {@code NULL}: the gateway still has to know what it binds. */
    record Null(Type type) implements SqlValue {

        public Null {
            Checks.present(type, "type");
        }
    }

    record Int64(long value) implements SqlValue {
    }

    /** Scale is significant: {@code 1.0} and {@code 1.00} are different values. */
    record Decimal(BigDecimal value) implements SqlValue {

        public Decimal {
            Checks.present(value, "value");
        }
    }

    record Text(String value) implements SqlValue {

        public Text {
            Checks.present(value, "value");
        }
    }

    record Bool(boolean value) implements SqlValue {
    }

    /** Copied on the way in and on the way out, so a plan cannot be changed through its array. */
    record Bytes(byte[] value) implements SqlValue {

        public Bytes {
            value = Checks.present(value, "value").clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes bytes && Arrays.equals(value, bytes.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Bytes[" + HexFormat.of().formatHex(value) + "]";
        }
    }
}
