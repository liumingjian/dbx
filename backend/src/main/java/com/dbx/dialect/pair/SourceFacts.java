package com.dbx.dialect.pair;

import com.dbx.dialect.api.MappingUnsupportedReason;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Unsupported;
import java.util.regex.Pattern;

/** Readings of {@link SourceColumn} facts that more than one TP §6 family needs. */
final class SourceFacts {

    /** {@code information_schema.COLUMNS.EXTRA} lists {@code auto_increment} among other words. */
    private static final Pattern AUTO_INCREMENT = Pattern.compile("(?i)\\bauto_increment\\b");

    private SourceFacts() {
    }

    /** The refusal for facts that contradict each other or lack one MySQL always reports. */
    static Unsupported inconsistent(SourceColumn column) {
        return new Unsupported(MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT, column);
    }

    /** Whether the source column is {@code AUTO_INCREMENT}. */
    static boolean autoIncrement(SourceColumn column) {
        return AUTO_INCREMENT.matcher(column.extra()).find();
    }
}
