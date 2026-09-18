package com.dbx.dialect.postgres;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.MaintenanceAction;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TimeoutClass;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code target.maintenancePlans}: abandonment (废弃) drops, sequence alignment and exact-object grants
 * (obligation 28; ADR-0023; ADR-0006; TP §7.3). One plan per action, in the order the actions were given.
 *
 * <p>No statement carries {@code CASCADE}. ADR-0023 §What is removed drops each owned table with
 * {@code DROP TABLE} and never {@code CASCADE}, because cascading reaches customer objects outside the
 * confirmed scope; {@code RESTRICT} is rendered explicitly even though PostgreSQL defaults to it, so the
 * refusal is visible in the statement a DBA reads on the abandonment list (废弃清单).
 *
 * <p><b>Why a drop is one plan of two statements.</b> ADR-0023 §Identity, not name refuses a table whose
 * {@code pg_class} OID no longer matches, so the drop is authorised by an identity guard that must travel
 * with it: two plans would let a caller hold a drop without the guard. {@link SqlPlan} carries one
 * {@link ResultSchema} for the whole plan, and that is not ambiguous here — the guard is the only statement
 * that returns rows, {@code DROP} returns none, so the plan's {@code EXACTLY_ONE_ROW} describes the guard.
 * This module cannot make the pair atomic and does not pretend to: the advisory lock and the reread between
 * them are {@code orchestration}'s (ADR-0023 §Confirmation renders the destruction first).
 *
 * <p><b>Evidence.</b> The guarded drops are {@link EvidencePolicy#STATEMENT_AND_RESULT}: the guard's OID row
 * is the audit record of what authorised an irreversible drop, and an OID is catalog metadata, never a
 * customer row value, so ADR-0028 is satisfied. {@code setval} and {@code GRANT} are
 * {@link EvidencePolicy#STATEMENT_ONLY}: the statements say everything, and {@code setval} echoes a key
 * value that evidence does not need.
 */
final class Maintenance {

    /** Every maintenance statement runs under one timeout class and one privilege (ADR-0006, obligation 28). */
    private static final TimeoutClass TIMEOUT = TimeoutClass.MAINTENANCE;

    /** The management account already owns every DBX-created table, so a drop needs no new privilege (ADR-0023). */
    private static final Set<RequiredPrivilege> PRIVILEGES = Set.of(RequiredPrivilege.TARGET_OWN_OBJECT);

    /** {@code pg_class.relkind} of an ordinary table; bound, never spliced. */
    private static final String ORDINARY_TABLE = "r";

    private static final ResultSchema OID_ROW = new ResultSchema(
            List.of(new ResultSchema.Column("oid", "oid", Nullability.NOT_NULL)),
            ResultSchema.Cardinality.EXACTLY_ONE_ROW);

    private static final ResultSchema SETVAL_ROW = new ResultSchema(
            List.of(new ResultSchema.Column("setval", "bigint", Nullability.NOT_NULL)),
            ResultSchema.Cardinality.EXACTLY_ONE_ROW);

    private Maintenance() {
    }

    static List<SqlPlan> plans(List<MaintenanceAction> actions) {
        return actions.stream().map(action -> plan(Objects.requireNonNull(action, "a maintenance action is required")))
                .toList();
    }

    private static SqlPlan plan(MaintenanceAction action) {
        return switch (action) {
            case MaintenanceAction.DropOwnedTable drop -> dropOwnedTable(drop);
            case MaintenanceAction.DropEmptySchema drop -> dropEmptySchema(drop);
            case MaintenanceAction.SetOwnedSequence setval -> setOwnedSequence(setval);
            case MaintenanceAction.GrantOnOwnedObject grant -> grantOnOwnedObject(grant);
        };
    }

    /**
     * The guard selects the OID of the one relation that matches the recorded namespace, name, kind <em>and</em>
     * OID. A table someone replaced since keeps the name but not the OID, so the guard returns no row and the
     * plan's {@code EXACTLY_ONE_ROW} fails before the drop runs (ADR-0023 §Identity, not name).
     */
    private static SqlPlan dropOwnedTable(MaintenanceAction.DropOwnedTable drop) {
        ParameterizedStatement guard = new ParameterizedStatement(
                "SELECT c.oid FROM pg_catalog.pg_class c "
                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = ?::\"char\" AND c.oid = ?::oid",
                List.of(new SqlValue.Text(drop.table().schema().name()),
                        new SqlValue.Text(drop.table().name().name()),
                        new SqlValue.Text(ORDINARY_TABLE),
                        new SqlValue.Int64(drop.pgClassOid())));
        ParameterizedStatement dropTable = new ParameterizedStatement(
                "DROP TABLE " + PostgresIdentifier.qualified(drop.table()) + " RESTRICT", List.of());
        return new SqlPlan(OperationKind.TARGET_MAINTENANCE, List.of(guard, dropTable), OID_ROW, TIMEOUT,
                PRIVILEGES, EvidencePolicy.STATEMENT_AND_RESULT);
    }

    /**
     * The guard proves both facts ADR-0023 demands before a schema goes: the {@code pg_namespace} OID is the one
     * DBX recorded when it created the schema, and the schema holds no relation at all. A schema that existed
     * before the task has a different OID, so it is never touched.
     */
    private static SqlPlan dropEmptySchema(MaintenanceAction.DropEmptySchema drop) {
        ParameterizedStatement guard = new ParameterizedStatement(
                "SELECT n.oid FROM pg_catalog.pg_namespace n WHERE n.nspname = ? AND n.oid = ?::oid "
                        + "AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_class c WHERE c.relnamespace = n.oid)",
                List.of(new SqlValue.Text(drop.schema().name()), new SqlValue.Int64(drop.namespaceOid())));
        ParameterizedStatement dropSchema = new ParameterizedStatement(
                "DROP SCHEMA " + PostgresIdentifier.quoted(drop.schema()) + " RESTRICT", List.of());
        return new SqlPlan(OperationKind.TARGET_MAINTENANCE, List.of(guard, dropSchema), OID_ROW, TIMEOUT,
                PRIVILEGES, EvidencePolicy.STATEMENT_AND_RESULT);
    }

    /**
     * {@code setval} with all three arguments bound: the sequence as the text {@code regclass} parses, the value
     * as {@code INT64} and {@code is_called} as a Boolean, so the empty-table {@code is_called=false} case is
     * expressible rather than spliced (TP §7.3). {@code MaintenanceAction.SetOwnedSequence} does not guard the
     * value positive for the same reason.
     */
    private static SqlPlan setOwnedSequence(MaintenanceAction.SetOwnedSequence setval) {
        ParameterizedStatement statement = new ParameterizedStatement("SELECT setval(?::regclass, ?, ?)",
                List.of(new SqlValue.Text(PostgresIdentifier.qualified(setval.sequence())),
                        new SqlValue.Int64(setval.value()),
                        new SqlValue.Bool(setval.isCalled())));
        return new SqlPlan(OperationKind.TARGET_MAINTENANCE, List.of(statement), SETVAL_ROW, TIMEOUT,
                PRIVILEGES, EvidencePolicy.STATEMENT_ONLY);
    }

    /**
     * One privilege on one named object, never schema-wide and never {@code ALL}: ADR-0006 lets DBX grant exact
     * object privileges on objects it created and nothing else.
     */
    private static SqlPlan grantOnOwnedObject(MaintenanceAction.GrantOnOwnedObject grant) {
        ParameterizedStatement statement = new ParameterizedStatement(
                "GRANT " + postgresPrivilege(grant.privilege()) + " ON " + PostgresIdentifier.qualified(grant.object())
                        + " TO " + PostgresIdentifier.quoted(grant.grantee()),
                List.of());
        return new SqlPlan(OperationKind.TARGET_MAINTENANCE, List.of(statement), ResultSchema.NONE, TIMEOUT,
                PRIVILEGES, EvidencePolicy.STATEMENT_ONLY);
    }

    /**
     * The closed {@link RequiredPrivilege} to PostgreSQL privilege function, covering exactly the three grants
     * ADR-0006 permits on a DBX-owned object: {@code SELECT} to read it, {@code USAGE} to reach and draw from it,
     * {@code INSERT} for the Sink writer. A privilege with no object-level meaning — {@code TARGET_CREATE_SCHEMA},
     * whose PostgreSQL counterpart {@code CREATE} exists only on a schema or database, and either {@code SOURCE_*},
     * which is MySQL's — throws rather than rendering something plausible that a DBA would then run.
     */
    private static String postgresPrivilege(RequiredPrivilege privilege) {
        return switch (privilege) {
            case TARGET_READ_CATALOG -> "SELECT";
            case TARGET_CREATE_IN_SCHEMA -> "USAGE";
            case TARGET_OWN_OBJECT -> "INSERT";
            case TARGET_CREATE_SCHEMA, SOURCE_READ_METADATA, SOURCE_SELECT -> throw new IllegalArgumentException(
                    "ADR-0006: " + privilege + " has no object-level PostgreSQL privilege, so DBX cannot grant it "
                            + "on one owned object; it is granted on a schema or belongs to the source account");
        };
    }
}
