package com.dbx.dialect.mysql;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.MetadataScope;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.TableCoordinate;
import com.dbx.dialect.api.TimeoutClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code source.capabilityPlans}: proves ADR-0006's read-only account can do what the run asks by
 * exercising it, never by parsing grants (roles, wildcards and partial revokes make {@code SHOW GRANTS}
 * and {@code *_PRIVILEGES} unreliable). Obligation 20; ADR-0006 §Capability checks; TP §6.5.
 */
final class CapabilityPlans {

    /**
     * TP §6.5 last paragraph: the effective server and session settings its Connector/J semantics rely on,
     * read as facts. {@code character_set_results} is {@code NULL} when a client disables result
     * conversion; {@code character_set_system} has no session scope.
     */
    private static final List<Setting> SETTINGS = List.of(
            new Setting("@@session.time_zone", "time_zone", "varchar", Nullability.NOT_NULL),
            new Setting("@@session.character_set_client", "character_set_client", "varchar", Nullability.NOT_NULL),
            new Setting("@@session.character_set_connection", "character_set_connection", "varchar",
                    Nullability.NOT_NULL),
            new Setting("@@session.character_set_database", "character_set_database", "varchar", Nullability.NOT_NULL),
            new Setting("@@session.character_set_filesystem", "character_set_filesystem", "varchar",
                    Nullability.NOT_NULL),
            new Setting("@@session.character_set_results", "character_set_results", "varchar", Nullability.NULLABLE),
            new Setting("@@session.character_set_server", "character_set_server", "varchar", Nullability.NOT_NULL),
            new Setting("@@global.character_set_system", "character_set_system", "varchar", Nullability.NOT_NULL),
            new Setting("@@session.collation_connection", "collation_connection", "varchar", Nullability.NOT_NULL),
            new Setting("@@session.sql_mode", "sql_mode", "varchar", Nullability.NOT_NULL),
            new Setting("@@session.information_schema_stats_expiry", "information_schema_stats_expiry",
                    "bigint unsigned", Nullability.NOT_NULL));

    private static final SqlPlan SETTINGS_PLAN = new SqlPlan(
            OperationKind.SOURCE_CAPABILITY_CHECK,
            List.of(new ParameterizedStatement("SELECT " + String.join(", ", SETTINGS.stream()
                    .map(s -> s.variable() + " AS " + MySqlIdentifier.quoted(s.label())).toList()), List.of())),
            new ResultSchema(SETTINGS.stream()
                    .map(s -> new ResultSchema.Column(s.label(), s.databaseType(), s.nullability())).toList(),
                    ResultSchema.Cardinality.EXACTLY_ONE_ROW),
            TimeoutClass.CAPABILITY_PROBE,
            Set.of(),
            EvidencePolicy.STATEMENT_AND_RESULT);

    /**
     * The probe's result is its own typed count, never the table's columns: the plan does not know the
     * column list, and a typed schema must not depend on it (ADR-0008 §Plans).
     */
    private static final ResultSchema PROBE_RESULT = new ResultSchema(
            List.of(new ResultSchema.Column("probed_rows", "bigint", Nullability.NOT_NULL)),
            ResultSchema.Cardinality.EXACTLY_ONE_ROW);

    private CapabilityPlans() {
    }

    /** The settings read first, then one probe per scoped table in scope order. */
    static List<SqlPlan> of(MetadataScope scope) {
        List<SqlPlan> plans = new ArrayList<>();
        plans.add(SETTINGS_PLAN);
        Set<TableCoordinate> seen = new HashSet<>();
        for (TableCoordinate table : scope.tables()) {
            if (!table.database().equals(scope.database().database())) {
                throw new IllegalArgumentException("a capability scope covers one database: " + table
                        + " is not in " + scope.database());
            }
            if (!seen.add(table)) {
                throw new IllegalArgumentException("a capability scope lists each table once: " + table);
            }
            plans.add(selectProbe(table));
        }
        return List.copyOf(plans);
    }

    /**
     * A zero-row read that fails unless {@code SELECT} is held on every visible column. {@code *} makes
     * MySQL check the privilege on each column it expands to. INVISIBLE columns are not in {@code *}, so a
     * missing grant on one passes: an open gap on #123, not a proof. {@code LIMIT} keeps the derived table from being
     * merged away, and {@code LIMIT 0} reads no row. The outer {@code COUNT(*)} gives one typed row whatever
     * the table's columns are. Whether a real partial revoke fails it is proven at L2/L3, not here.
     */
    private static SqlPlan selectProbe(TableCoordinate table) {
        String sql = "SELECT COUNT(*) AS " + MySqlIdentifier.quoted("probed_rows") + " FROM (SELECT * FROM "
                + MySqlIdentifier.qualified(table) + " LIMIT 0) AS " + MySqlIdentifier.quoted("select_probe");
        return new SqlPlan(
                OperationKind.SOURCE_CAPABILITY_CHECK,
                List.of(new ParameterizedStatement(sql, List.of())),
                PROBE_RESULT,
                TimeoutClass.CAPABILITY_PROBE,
                Set.of(RequiredPrivilege.SOURCE_SELECT),
                EvidencePolicy.STATEMENT_AND_RESULT);
    }

    private record Setting(String variable, String label, String databaseType, Nullability nullability) {
    }
}
