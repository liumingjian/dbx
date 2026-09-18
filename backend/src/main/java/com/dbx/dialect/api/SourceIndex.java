package com.dbx.dialect.api;

import java.util.List;
import java.util.OptionalLong;

/**
 * One index of a source table as {@code information_schema.STATISTICS} reports it, key parts in
 * sequence order. The primary key is the index MySQL names {@code PRIMARY}, a name no other index may
 * take. Invisible indexes are kept: they still enforce uniqueness.
 */
public record SourceIndex(String name, boolean unique, boolean visible, IndexType indexType, List<KeyPart> keyParts) {

    /** The name MySQL reserves for the primary key. */
    public static final String PRIMARY = "PRIMARY";

    public SourceIndex {
        Checks.nonEmpty(name, "name");
        Checks.present(indexType, "indexType");
        keyParts = Checks.nonEmptyList(keyParts, "keyParts");
        for (int i = 0; i < keyParts.size(); i++) {
            if (keyParts.get(i).sequence() != i + 1) {
                throw new IllegalArgumentException("index " + name + ": key part " + (i + 1) + " has sequence "
                        + keyParts.get(i).sequence() + "; key parts are in SEQ_IN_INDEX order from 1");
            }
        }
        if (name.equals(PRIMARY) && !unique) {
            throw new IllegalArgumentException("index " + name + ": a primary key is unique");
        }
    }

    public boolean primaryKey() {
        return name.equals(PRIMARY);
    }

    /** {@code STATISTICS.INDEX_TYPE} as MySQL 8.0 reports it. */
    public enum IndexType {
        BTREE,
        HASH,
        FULLTEXT,
        SPATIAL
    }

    /** One key part: a column or an expression, an optional prefix length, and its direction. */
    public record KeyPart(int sequence, Subject subject, OptionalLong prefixLength, Direction direction) {

        public KeyPart {
            Checks.positive(sequence, "sequence");
            Checks.present(subject, "subject");
            Checks.present(prefixLength, "prefixLength");
            if (prefixLength.isPresent()) {
                Checks.positive(prefixLength.getAsLong(), "prefixLength");
            }
            Checks.present(direction, "direction");
        }
    }

    /** What a key part indexes: a column ({@code COLUMN_NAME}) or a functional expression ({@code EXPRESSION}). */
    public sealed interface Subject {

        record Column(ColumnCoordinate column) implements Subject {

            public Column {
                Checks.present(column, "column");
            }
        }

        /** The expression text exactly as MySQL reports it. */
        record Expression(String expression) implements Subject {

            public Expression {
                Checks.nonEmpty(expression, "expression");
            }
        }
    }

    /** {@code STATISTICS.COLLATION}: {@code A}, {@code D}, or {@code NULL} for an index that is not sorted. */
    public enum Direction {
        ASCENDING,
        DESCENDING,
        NOT_SORTED
    }
}
