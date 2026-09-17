package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.SourceForeignKey;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.TableCoordinate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Source structures as MySQL would define them, for the source definition a supplemental statement quotes
 * to the DBA (ADR-0026 §Supplemental SQL; obligation 25). Names are quoted under MySQL's rules here, on the
 * source side; the pair hands this text to the target dialect, which never quotes a source name itself
 * (ADR-0008 §Contract and mapping boundary). The text keeps a newline in a name literal, so a caller putting
 * it in a comment must comment every line.
 */
public final class MySqlDefinitions {

    private MySqlDefinitions() {
    }

    /** {@code `db`.`table`}. */
    public static String table(TableCoordinate table) {
        return MySqlIdentifier.qualified(table);
    }

    /** {@code `db`.`table`.`column`}. */
    public static String column(ColumnCoordinate column) {
        return MySqlIdentifier.qualified(column);
    }

    /** {@code CREATE [UNIQUE|FULLTEXT|SPATIAL] INDEX `name` ON `db`.`table` (key parts)[ INVISIBLE]}. */
    public static String index(TableCoordinate table, SourceIndex index) {
        String kind = switch (index.indexType()) {
            case FULLTEXT, SPATIAL -> index.indexType().name() + " ";
            case BTREE, HASH -> index.unique() ? "UNIQUE " : "";
        };
        String parts = index.keyParts().stream().map(part -> {
            String subject = switch (part.subject()) {
                case SourceIndex.Subject.Column column -> MySqlIdentifier.quoted(column.column().column());
                case SourceIndex.Subject.Expression expression -> "(" + expression.expression() + ")";
            };
            String prefix = part.prefixLength().isPresent() ? "(" + part.prefixLength().getAsLong() + ")" : "";
            return subject + prefix + (part.direction() == SourceIndex.Direction.DESCENDING ? " DESC" : "");
        }).collect(Collectors.joining(", "));
        return "CREATE " + kind + "INDEX " + MySqlIdentifier.quoted(index.name()) + " ON " + table(table)
                + " (" + parts + ")" + (index.visible() ? "" : " INVISIBLE");
    }

    /**
     * {@code ALTER TABLE `db`.`table` ADD CONSTRAINT `name` FOREIGN KEY (…) REFERENCES `db`.`other` (…)}, without
     * the referential rules: they read the same in both dialects, so the caller appends its own rendering.
     */
    public static String foreignKey(TableCoordinate table, SourceForeignKey foreignKey) {
        return "ALTER TABLE " + table(table) + " ADD CONSTRAINT " + MySqlIdentifier.quoted(foreignKey.name())
                + " FOREIGN KEY (" + columns(foreignKey.parts().stream().map(SourceForeignKey.Part::column).toList())
                + ") REFERENCES " + table(foreignKey.referencedTable()) + " ("
                + columns(foreignKey.parts().stream().map(SourceForeignKey.Part::referencedColumn).toList()) + ")";
    }

    private static String columns(List<ColumnCoordinate> columns) {
        return columns.stream().map(c -> MySqlIdentifier.quoted(c.column())).collect(Collectors.joining(", "));
    }
}
