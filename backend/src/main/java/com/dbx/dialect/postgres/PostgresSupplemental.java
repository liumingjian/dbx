package com.dbx.dialect.postgres;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.DeferredStructure;
import com.dbx.dialect.api.DeferredStructure.MappedColumn;
import com.dbx.dialect.api.SourceForeignKey;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.Statement;
import com.dbx.dialect.api.SupplementalCommentReason;
import com.dbx.dialect.api.TableCoordinate;
import com.dbx.dialect.api.TargetTableCoordinate;
import com.dbx.dialect.mysql.MySqlIdentifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code target.supplementalStatements}: ADR-0026's supplemental SQL (obligation 25). What PostgreSQL can hold is
 * executable and unnamed, so no copied MySQL name collides in a schema; what it cannot is a comment giving the
 * source definition, "requires manual handling" and one stable {@link SupplementalCommentReason}.
 *
 * <p>Source definitions quote names with {@link MySqlIdentifier#quoted}, which keeps a newline literal. Every line
 * of comment text therefore starts with {@code -- }, so no source name can end a comment early; a source name
 * appears nowhere else. Values go through {@link PostgresLiteral}, which renders them on one line.
 *
 * <p>Output order: foreign keys after everything else; within each group by target schema, then target table
 * name, then input order (the order {@code contract} took from the source metadata).
 */
final class PostgresSupplemental {

    private static final String MANUAL = "requires manual handling";

    private PostgresSupplemental() {
    }

    static List<Statement> statements(List<DeferredStructure> structures) {
        List<DeferredStructure> ordered = new ArrayList<>(List.copyOf(structures));
        ordered.sort(Comparator.<DeferredStructure, Boolean>comparing(s -> s instanceof DeferredStructure.ForeignKey)
                .thenComparing(s -> s.target().schema().name())
                .thenComparing(s -> s.target().name().name()));
        return ordered.stream().map(PostgresSupplemental::statement).toList();
    }

    private static Statement statement(DeferredStructure structure) {
        return switch (structure) {
            case DeferredStructure.Index index -> index(index);
            case DeferredStructure.ForeignKey foreignKey -> foreignKey(foreignKey);
            case DeferredStructure.TableComment comment -> Statement.executable("COMMENT ON TABLE "
                    + relation(comment.target()) + " IS " + text(comment.comment()));
            case DeferredStructure.ColumnCommentText comment -> switch (comment.column()) {
                case MappedColumn.Pruned pruned -> commentOnly(SupplementalCommentReason.PRUNED_COLUMN,
                        column(pruned.source()) + " COMMENT " + text(comment.comment()));
                case MappedColumn.Approved approved -> Statement.executable("COMMENT ON COLUMN "
                        + relation(comment.target()) + "." + PostgresDdl.quoted(approved.target()) + " IS "
                        + text(comment.comment()));
            };
            case DeferredStructure.Collation collation -> {
                String definition = collation.column().map(c -> column(c.source())).orElse(table(collation.source()))
                        + " COLLATE " + collation.collation();
                yield commentOnly(collation.column().filter(MappedColumn.Pruned.class::isInstance).isPresent()
                        ? SupplementalCommentReason.PRUNED_COLUMN : SupplementalCommentReason.COLLATION, definition);
            }
            case DeferredStructure.OnUpdate onUpdate -> commentOnly(
                    onUpdate.column() instanceof MappedColumn.Pruned
                            ? SupplementalCommentReason.PRUNED_COLUMN
                            : SupplementalCommentReason.ON_UPDATE_CURRENT_TIMESTAMP,
                    column(onUpdate.column().source()) + " ON UPDATE " + onUpdate.definition());
            case DeferredStructure.ColumnDefault columnDefault -> switch (columnDefault.column()) {
                case MappedColumn.Pruned pruned -> commentOnly(SupplementalCommentReason.PRUNED_COLUMN,
                        column(pruned.source()) + " DEFAULT " + PostgresLiteral.render(columnDefault.value()));
                case MappedColumn.Approved approved -> Statement.executable("ALTER TABLE "
                        + relation(columnDefault.target()) + " ALTER COLUMN " + PostgresDdl.quoted(approved.target())
                        + " SET DEFAULT " + PostgresLiteral.render(columnDefault.value()));
            };
        };
    }

    /**
     * One reason per index, the first that applies: a pruned column (the target lacks it), a FULLTEXT or SPATIAL
     * index, an expression key part, a prefix key part.
     */
    private static Statement index(DeferredStructure.Index deferred) {
        SourceIndex index = deferred.index();
        String source = indexDefinition(deferred.source(), index);
        Optional<SupplementalCommentReason> reason = indexReason(deferred);
        if (reason.isPresent()) {
            return commentOnly(reason.get(), source);
        }
        List<String> parts = new ArrayList<>();
        int column = 0;
        boolean descending = false;
        for (SourceIndex.KeyPart part : index.keyParts()) {
            MappedColumn.Approved approved = (MappedColumn.Approved) deferred.columns().get(column++);
            descending |= part.direction() == SourceIndex.Direction.DESCENDING;
            parts.add(PostgresDdl.quoted(approved.target())
                    + (part.direction() == SourceIndex.Direction.DESCENDING ? " DESC" : ""));
        }
        String keys = "(" + String.join(", ", parts) + ")";
        String sql;
        if (index.unique() && !descending) {
            sql = "ALTER TABLE " + relation(deferred.target()) + " ADD UNIQUE " + keys;
        } else {
            sql = "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX ON " + relation(deferred.target()) + " "
                    + keys;
        }
        return Statement.executable(comment("source: " + source) + "\n" + sql);
    }

    private static Optional<SupplementalCommentReason> indexReason(DeferredStructure.Index deferred) {
        if (deferred.columns().stream().anyMatch(MappedColumn.Pruned.class::isInstance)) {
            return Optional.of(SupplementalCommentReason.PRUNED_COLUMN);
        }
        String type = deferred.index().indexType();
        if (type.equalsIgnoreCase("FULLTEXT") || type.equalsIgnoreCase("SPATIAL")) {
            return Optional.of(SupplementalCommentReason.FULLTEXT_OR_SPATIAL_INDEX);
        }
        List<SourceIndex.KeyPart> parts = deferred.index().keyParts();
        if (parts.stream().anyMatch(part -> part.subject() instanceof SourceIndex.Subject.Expression)) {
            return Optional.of(SupplementalCommentReason.EXPRESSION_KEY_PART);
        }
        if (parts.stream().anyMatch(part -> part.prefixLength().isPresent())) {
            return Optional.of(SupplementalCommentReason.PREFIX_KEY_PART);
        }
        return Optional.empty();
    }

    /** The index as MySQL would create it. */
    private static String indexDefinition(TableCoordinate table, SourceIndex index) {
        String type = index.indexType().equalsIgnoreCase("FULLTEXT") || index.indexType().equalsIgnoreCase("SPATIAL")
                ? index.indexType().toUpperCase(Locale.ROOT) + " "
                : index.unique() ? "UNIQUE " : "";
        String parts = index.keyParts().stream().map(part -> {
            String subject = switch (part.subject()) {
                case SourceIndex.Subject.Column column -> MySqlIdentifier.quoted(column.column().column());
                case SourceIndex.Subject.Expression expression -> "(" + expression.expression() + ")";
            };
            String prefix = part.prefixLength().isPresent() ? "(" + part.prefixLength().getAsLong() + ")" : "";
            return subject + prefix + (part.direction() == SourceIndex.Direction.DESCENDING ? " DESC" : "");
        }).collect(Collectors.joining(", "));
        return "CREATE " + type + "INDEX " + MySqlIdentifier.quoted(index.name()) + " ON " + table(table)
                + " (" + parts + ")" + (index.visible() ? "" : " INVISIBLE");
    }

    /** One reason per foreign key, the first that applies: an out-of-scope referenced table, a pruned column. */
    private static Statement foreignKey(DeferredStructure.ForeignKey deferred) {
        SourceForeignKey foreignKey = deferred.foreignKey();
        String source = "ALTER TABLE " + table(deferred.source()) + " ADD CONSTRAINT "
                + MySqlIdentifier.quoted(foreignKey.name()) + " FOREIGN KEY ("
                + foreignKey.parts().stream().map(p -> MySqlIdentifier.quoted(p.column().column()))
                        .collect(Collectors.joining(", "))
                + ") REFERENCES " + table(foreignKey.referencedTable()) + " ("
                + foreignKey.parts().stream().map(p -> MySqlIdentifier.quoted(p.referencedColumn().column()))
                        .collect(Collectors.joining(", "))
                + ")" + rules(foreignKey);
        if (!(deferred.referenced() instanceof DeferredStructure.ReferencedTable.InScope referenced)) {
            return commentOnly(SupplementalCommentReason.REFERENCED_TABLE_OUT_OF_SCOPE, source);
        }
        if (deferred.columns().stream().anyMatch(MappedColumn.Pruned.class::isInstance)
                || referenced.columns().stream().anyMatch(MappedColumn.Pruned.class::isInstance)) {
            return commentOnly(SupplementalCommentReason.PRUNED_COLUMN, source);
        }
        String sql = "ALTER TABLE " + relation(deferred.target()) + " ADD FOREIGN KEY (" + targets(deferred.columns())
                + ") REFERENCES " + relation(referenced.table()) + " (" + targets(referenced.columns()) + ")"
                + rules(foreignKey);
        return Statement.executable(comment("source: " + source) + "\n" + sql);
    }

    private static String rules(SourceForeignKey foreignKey) {
        return " ON UPDATE " + action(foreignKey.onUpdate()) + " ON DELETE " + action(foreignKey.onDelete());
    }

    private static String action(SourceForeignKey.ReferentialAction action) {
        return switch (action) {
            case CASCADE -> "CASCADE";
            case SET_NULL -> "SET NULL";
            case SET_DEFAULT -> "SET DEFAULT";
            case RESTRICT -> "RESTRICT";
            case NO_ACTION -> "NO ACTION";
        };
    }

    private static String targets(List<MappedColumn> columns) {
        return columns.stream().map(c -> PostgresDdl.quoted(((MappedColumn.Approved) c).target()))
                .collect(Collectors.joining(", "));
    }

    private static Statement commentOnly(SupplementalCommentReason reason, String definition) {
        return Statement.commentOnly(comment(MANUAL + " (" + reason.name() + "): " + definition), reason);
    }

    /** Comment text on as many lines as it has, each one a {@code --} comment: PostgreSQL ends one at CR or LF. */
    private static String comment(String text) {
        return "-- " + String.join("\n-- ", text.split("\r\n|\r|\n", -1));
    }

    private static String relation(TargetTableCoordinate coordinate) {
        return PostgresDdl.qualified(coordinate.schema(), coordinate.name());
    }

    private static String table(TableCoordinate table) {
        return MySqlIdentifier.quoted(table.database()) + "." + MySqlIdentifier.quoted(table.table());
    }

    private static String column(ColumnCoordinate column) {
        return MySqlIdentifier.quoted(column.database()) + "." + MySqlIdentifier.quoted(column.table()) + "."
                + MySqlIdentifier.quoted(column.column());
    }

    private static String text(String value) {
        return PostgresLiteral.render(new SqlValue.Text(value));
    }
}
