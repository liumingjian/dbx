package com.dbx.dialect.postgres;

import com.dbx.dialect.api.DeferredStructure;
import com.dbx.dialect.api.DeferredStructure.MappedColumn;
import com.dbx.dialect.api.SourceForeignKey;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.Statement;
import com.dbx.dialect.api.SupplementalCommentReason;
import com.dbx.dialect.api.TargetTableCoordinate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code target.supplementalStatements}: ADR-0026's supplemental SQL (obligation 25). What PostgreSQL can hold is
 * executable and unnamed, so no copied MySQL name collides in a schema; what it cannot is a comment giving the
 * source definition, "requires manual handling" and one stable {@link SupplementalCommentReason}.
 *
 * <p>Source definitions come from {@link SourceDefinitions}, which keeps a newline in a name literal. Every line
 * of comment text therefore starts with {@code -- }, so no source name can end a comment early; a source name
 * appears nowhere else. Values go through {@link PostgresLiteral}, which renders them on one line.
 *
 * <p>Output order: foreign keys after everything else; within each group by target schema, then target table
 * name, then input order (the order {@code contract} took from the source metadata).
 */
final class PostgresSupplemental {

    private static final String MANUAL = "requires manual handling";

    private final SourceDefinitions source;

    PostgresSupplemental(SourceDefinitions source) {
        this.source = source;
    }

    List<Statement> statements(List<DeferredStructure> structures) {
        List<DeferredStructure> ordered = new ArrayList<>(List.copyOf(structures));
        ordered.sort(Comparator.<DeferredStructure, Boolean>comparing(s -> s instanceof DeferredStructure.ForeignKey)
                .thenComparing(s -> s.target().schema().name())
                .thenComparing(s -> s.target().name().name()));
        return ordered.stream().map(this::statement).toList();
    }

    private Statement statement(DeferredStructure structure) {
        return switch (structure) {
            case DeferredStructure.Index index -> index(index);
            case DeferredStructure.ForeignKey foreignKey -> foreignKey(foreignKey);
            case DeferredStructure.TableComment comment -> Statement.executable("COMMENT ON TABLE "
                    + relation(comment.target()) + " IS " + PostgresLiteral.text(comment.comment()));
            case DeferredStructure.ColumnCommentText comment -> switch (comment.column()) {
                case MappedColumn.Pruned pruned -> commentOnly(SupplementalCommentReason.PRUNED_COLUMN,
                        source.column(pruned.source()) + " COMMENT " + PostgresLiteral.text(comment.comment()));
                case MappedColumn.Approved approved -> Statement.executable("COMMENT ON COLUMN "
                        + relation(comment.target()) + "." + PostgresIdentifier.quoted(approved.target()) + " IS "
                        + PostgresLiteral.text(comment.comment()));
            };
            case DeferredStructure.Collation collation -> {
                String definition = collation.column().map(c -> source.column(c.source()))
                        .orElse(source.table(collation.source())) + " COLLATE " + collation.collation();
                yield commentOnly(collation.column().filter(MappedColumn.Pruned.class::isInstance).isPresent()
                        ? SupplementalCommentReason.PRUNED_COLUMN : SupplementalCommentReason.COLLATION, definition);
            }
            case DeferredStructure.OnUpdate onUpdate -> commentOnly(
                    onUpdate.column() instanceof MappedColumn.Pruned
                            ? SupplementalCommentReason.PRUNED_COLUMN
                            : SupplementalCommentReason.ON_UPDATE_CURRENT_TIMESTAMP,
                    source.column(onUpdate.column().source()) + " ON UPDATE " + onUpdate.definition());
            case DeferredStructure.ColumnDefault columnDefault -> switch (columnDefault.column()) {
                case MappedColumn.Pruned pruned -> commentOnly(SupplementalCommentReason.PRUNED_COLUMN,
                        source.column(pruned.source()) + " DEFAULT " + PostgresLiteral.render(columnDefault.value()));
                case MappedColumn.Approved approved -> Statement.executable("ALTER TABLE "
                        + relation(columnDefault.target()) + " ALTER COLUMN "
                        + PostgresIdentifier.quoted(approved.target())
                        + " SET DEFAULT " + PostgresLiteral.render(columnDefault.value()));
            };
        };
    }

    /**
     * One reason per index, the first that applies: a pruned column (the target lacks it), a FULLTEXT or SPATIAL
     * index, an expression key part, a prefix key part. Otherwise every key part is an approved column, one
     * {@code columns} entry each in key-part order.
     */
    private Statement index(DeferredStructure.Index deferred) {
        SourceIndex index = deferred.index();
        String definition = source.index(deferred.source(), index);
        Optional<SupplementalCommentReason> reason = indexReason(deferred);
        if (reason.isPresent()) {
            return commentOnly(reason.get(), definition);
        }
        List<String> keys = new ArrayList<>();
        boolean anyDescending = false;
        for (int keyPart = 0; keyPart < index.keyParts().size(); keyPart++) {
            MappedColumn.Approved approved = (MappedColumn.Approved) deferred.columns().get(keyPart);
            boolean descending = index.keyParts().get(keyPart).direction() == SourceIndex.Direction.DESCENDING;
            anyDescending |= descending;
            keys.add(PostgresIdentifier.quoted(approved.target()) + (descending ? " DESC" : ""));
        }
        String keyList = "(" + String.join(", ", keys) + ")";
        // ADD UNIQUE takes no direction, so a unique index with a descending key part stays an index.
        String sql = index.unique() && !anyDescending
                ? "ALTER TABLE " + relation(deferred.target()) + " ADD UNIQUE " + keyList
                : "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX ON " + relation(deferred.target()) + " "
                        + keyList;
        return Statement.executable(comment("source: " + definition) + "\n" + sql);
    }

    private static Optional<SupplementalCommentReason> indexReason(DeferredStructure.Index deferred) {
        if (deferred.columns().stream().anyMatch(MappedColumn.Pruned.class::isInstance)) {
            return Optional.of(SupplementalCommentReason.PRUNED_COLUMN);
        }
        boolean fulltextOrSpatial = switch (deferred.index().indexType()) {
            case FULLTEXT, SPATIAL -> true;
            case BTREE, HASH -> false;
        };
        if (fulltextOrSpatial) {
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

    /** One reason per foreign key, the first that applies: an out-of-scope referenced table, a pruned column. */
    private Statement foreignKey(DeferredStructure.ForeignKey deferred) {
        SourceForeignKey foreignKey = deferred.foreignKey();
        String definition = source.foreignKey(deferred.source(), foreignKey) + rules(foreignKey);
        if (!(deferred.referenced() instanceof DeferredStructure.ReferencedTable.InScope referenced)) {
            return commentOnly(SupplementalCommentReason.REFERENCED_TABLE_OUT_OF_SCOPE, definition);
        }
        if (deferred.columns().stream().anyMatch(MappedColumn.Pruned.class::isInstance)
                || referenced.columns().stream().anyMatch(MappedColumn.Pruned.class::isInstance)) {
            return commentOnly(SupplementalCommentReason.PRUNED_COLUMN, definition);
        }
        String sql = "ALTER TABLE " + relation(deferred.target()) + " ADD FOREIGN KEY (" + targets(deferred.columns())
                + ") REFERENCES " + relation(referenced.table()) + " (" + targets(referenced.columns()) + ")"
                + rules(foreignKey);
        return Statement.executable(comment("source: " + definition) + "\n" + sql);
    }

    /** The referential rules, spelled the same in MySQL and PostgreSQL. */
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
        return columns.stream().map(c -> PostgresIdentifier.quoted(((MappedColumn.Approved) c).target()))
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
        return PostgresIdentifier.qualified(coordinate);
    }
}
