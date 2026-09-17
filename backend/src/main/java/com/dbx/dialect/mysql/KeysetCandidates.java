package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.KeysetCandidate;
import com.dbx.dialect.api.KeysetCandidate.Origin;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.SourceIndex;
import com.dbx.dialect.api.SourceTableMetadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-0037 §Choice from {@link SourceTableMetadata} alone: single-column unique indexes on a {@code NOT NULL}
 * integer column, the primary key first, then unique indexes in the server order the metadata kept. Nothing
 * is sorted here: "lowest name" means lowest in the source server's collation, and only the row order
 * carries that. Value ranges are data facts for slice 6; dropping pruned candidates is the caller's job.
 */
final class KeysetCandidates {

    private static final Set<String> INTEGER_TYPES = Set.of("tinyint", "smallint", "mediumint", "int", "bigint");

    /**
     * {@code tinyint(1)}: under the Boolean switch it reaches Connect as {@code BOOLEAN}, which cannot be paged
     * by, and a table keyed by it is small enough for the bulk path anyway.
     */
    private static final Pattern TINYINT_ONE = Pattern.compile("tinyint\\(1\\)( .*)?");

    private KeysetCandidates() {
    }

    static List<KeysetCandidate> of(SourceTableMetadata table) {
        Objects.requireNonNull(table, "table is required");
        Set<ColumnCoordinate> chosen = new HashSet<>();
        List<KeysetCandidate> candidates = new ArrayList<>();
        table.primaryKey().flatMap(index -> pageableColumn(table, index)).ifPresent(column -> {
            chosen.add(column);
            candidates.add(new KeysetCandidate(column, Origin.PRIMARY_KEY));
        });
        for (SourceIndex index : table.indexes()) {
            if (index.primaryKey() || !index.unique()) {
                continue;
            }
            pageableColumn(table, index).filter(chosen::add)
                    .ifPresent(column -> candidates.add(new KeysetCandidate(column, Origin.UNIQUE_INDEX)));
        }
        return List.copyOf(candidates);
    }

    /** The index's only key part, when it is a whole {@code NOT NULL} integer column other than {@code tinyint(1)}. */
    private static Optional<ColumnCoordinate> pageableColumn(SourceTableMetadata table, SourceIndex index) {
        if (index.keyParts().size() != 1) {
            return Optional.empty();
        }
        SourceIndex.KeyPart part = index.keyParts().get(0);
        if (!(part.subject() instanceof SourceIndex.Subject.Column subject) || part.prefixLength().isPresent()) {
            return Optional.empty();
        }
        // SourceTableMetadata refuses a key part naming an unknown column, so the column is there.
        SourceColumn column = table.columns().stream()
                .filter(candidate -> candidate.coordinate().equals(subject.column()))
                .findFirst().orElseThrow();
        boolean pageable = column.nullability() == Nullability.NOT_NULL
                && INTEGER_TYPES.contains(column.dataType())
                && !TINYINT_ONE.matcher(column.columnType()).matches();
        return pageable ? Optional.of(column.coordinate()) : Optional.empty();
    }
}
