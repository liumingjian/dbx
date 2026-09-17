package com.dbx.dialect.api;

/**
 * A source name, kept character for character (TP §7.1). Coordinates are typed at their level, so a
 * table name can never be taken for a column name, and never come from a topic or connector name.
 */
public sealed interface SourceCoordinate extends UnsupportedEvidence
        permits SchemaCoordinate, TableCoordinate, ColumnCoordinate {
}
