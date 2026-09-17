package com.dbx.dialect.mysql;

import com.dbx.dialect.api.ApprovedColumn;
import com.dbx.dialect.api.ColumnCoordinate;
import com.dbx.dialect.api.MappingRule;
import com.dbx.dialect.api.ProjectionSql;
import com.dbx.dialect.api.TableCoordinate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code source.queryProjection}: the only renderer of the prune/rename projection
 * {@code SELECT <expr> AS <alias>, … FROM <db>.<table>} ({@code docs/spec/dialect.md} obligation 19a;
 * TP §7.1; #92). {@code connector.deriveBox} places the text verbatim and appends its incrementing
 * predicate and {@code LIMIT}, so the text carries no {@code WHERE}, {@code ORDER BY}, {@code LIMIT},
 * {@code ;} or value transform.
 */
final class QueryProjection {

    private QueryProjection() {
    }

    /**
     * The SQL expression a projected source column is read as. Slice 6's envelope scan measures these
     * exact expressions (obligation 19a), so it calls this function; it never re-derives one.
     */
    static String columnExpression(ColumnCoordinate column) {
        return MySqlIdentifier.quoted(column.column());
    }

    /**
     * {@code approvedColumns} is authoritative: {@code contract} already resolved {@code USER} over
     * {@code AUTO} (contract obligation 8). {@code mappingRules} must agree with it; a disagreement is a
     * programming error in the caller and throws naming it. {@code TableRename} and
     * {@code TargetTypeOverride} do not change the text.
     */
    static ProjectionSql render(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules) {
        if (approvedColumns.isEmpty()) {
            throw new IllegalArgumentException("obligation 19a: a projection needs at least one approved column");
        }
        TableCoordinate table = tableOf(approvedColumns.get(0).source());
        for (ApprovedColumn approved : approvedColumns) {
            if (!tableOf(approved.source()).equals(table)) {
                throw new IllegalArgumentException("obligation 19a: a projection reads one table, but the approved "
                        + "columns span " + table + " and " + tableOf(approved.source()));
            }
        }
        for (ApprovedColumn approved : approvedColumns) {
            checkRulesAgree(approved, mappingRules);
        }
        List<String> items = new ArrayList<>(approvedColumns.size());
        for (ApprovedColumn approved : approvedColumns) {
            items.add(columnExpression(approved.source()) + " AS " + MySqlIdentifier.quoted(approved.target().name()));
        }
        return new ProjectionSql("SELECT " + String.join(", ", items) + " FROM "
                + MySqlIdentifier.quoted(table.database()) + "." + MySqlIdentifier.quoted(table.table()));
    }

    /**
     * A prune of a projected column disagrees whatever its origin. A rename disagrees when its target is
     * not the approved one; when a column has a {@code USER} rename, its {@code AUTO} renames were
     * overridden and are not compared (contract obligation 8).
     */
    private static void checkRulesAgree(ApprovedColumn approved, List<MappingRule> mappingRules) {
        List<MappingRule.ColumnRename> renames = new ArrayList<>();
        for (MappingRule rule : mappingRules) {
            if (rule instanceof MappingRule.ColumnPrune prune && prune.source().equals(approved.source())) {
                throw new IllegalArgumentException("contract obligation 8: " + prune.origin() + " ColumnPrune for "
                        + prune.source() + " disagrees with the approved columns, which project it");
            }
            if (rule instanceof MappingRule.ColumnRename rename && rename.source().equals(approved.source())) {
                renames.add(rename);
            }
        }
        boolean userRenamed = renames.stream().anyMatch(r -> r.origin() == MappingRule.RuleOrigin.USER);
        for (MappingRule.ColumnRename rename : renames) {
            if (userRenamed && rename.origin() == MappingRule.RuleOrigin.AUTO) {
                continue;
            }
            if (!rename.target().equals(approved.target())) {
                throw new IllegalArgumentException("contract obligation 8: " + rename.origin() + " ColumnRename of "
                        + rename.source() + " to " + rename.target() + " disagrees with the approved target "
                        + approved.target());
            }
        }
    }

    private static TableCoordinate tableOf(ColumnCoordinate column) {
        return new TableCoordinate(column.database(), column.table());
    }
}
