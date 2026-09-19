package com.dbx.dialect.postgres;

import com.dbx.dialect.api.EvidencePolicy;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.OperationKind;
import com.dbx.dialect.api.ParameterizedStatement;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultSchema;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.SqlValue;
import com.dbx.dialect.api.TargetIdentifier;
import com.dbx.dialect.api.TimeoutClass;
import java.util.List;
import java.util.Set;

/**
 * {@code target.capabilityProbePlans}: ADR-0006 §Capability checks proven on one isolated, uniquely named probe
 * object (obligation 27). Five plans exercise create, insert, read, truncate and drop, in that order. The drop
 * plan is always present and always last, so the caller always holds the durable cleanup request ADR-0006
 * demands, even when an earlier plan fails.
 *
 * <p><b>Identifier confinement.</b> The probe table's single column carries the probe object's own name, so the
 * only identifiers spelled here are the two the caller named; the catalog read addresses the probe by bound
 * value, never by spliced text. No production table can be named by construction — which is the point, because
 * ADR-0011 §Table write contract forbids fabricating a row in a table DBX did not create for this purpose.
 *
 * <p><b>The caller names the probe.</b> Deriving a unique name here would need a clock or a random source:
 * ADR-0018 rule 3 bans both in a pure module, and either would make the plan's fingerprint differ between two
 * runs of the same check.
 *
 * <p><b>Evidence.</b> Every plan is {@code STATEMENT_ONLY}. A probe proves a privilege, not a fact about
 * customer data, and the only row it reads is the one it wrote into its own disposable object; ADR-0028 keeps
 * data values out of evidence, so not even that synthetic row can reach a diagnostic package.
 *
 * <p>That the sequence succeeds on a real PostgreSQL 15 is not provable at L1: it runs at L2 in {@code contract}
 * slice 8 through {@code gateway} (docs/spec/dialect.md §Verification).
 */
final class CapabilityProbe {

    /** The value inserted into the probe object: one row DBX fabricated in an object it created. */
    private static final SqlValue PROBE_ROW = new SqlValue.Int64(1);

    /** {@code pg_class.relkind} of an ordinary table, bound rather than spliced. */
    private static final SqlValue ORDINARY_TABLE = new SqlValue.Text("r");

    /** Both read statements return one {@code COUNT(*)}, so one result schema describes the whole read plan. */
    private static final ResultSchema COUNT = new ResultSchema(
            List.of(new ResultSchema.Column("count", "bigint", Nullability.NOT_NULL)),
            ResultSchema.Cardinality.EXACTLY_ONE_ROW);

    private CapabilityProbe() {
    }

    static List<SqlPlan> plans(TargetIdentifier schema, TargetIdentifier probeName) {
        String relation = PostgresIdentifier.qualified(schema, probeName);
        String column = PostgresIdentifier.quoted(probeName);
        return List.of(
                write("CREATE TABLE " + relation + " (" + column + " bigint NOT NULL)",
                        List.of(), Set.of(RequiredPrivilege.TARGET_CREATE_IN_SCHEMA)),
                write("INSERT INTO " + relation + " (" + column + ") VALUES (?)",
                        List.of(PROBE_ROW), Set.of(RequiredPrivilege.TARGET_OWN_OBJECT)),
                read(schema, probeName, relation),
                write("TRUNCATE TABLE " + relation, List.of(), Set.of(RequiredPrivilege.TARGET_OWN_OBJECT)),
                write("DROP TABLE " + relation + " RESTRICT", List.of(), Set.of(RequiredPrivilege.TARGET_OWN_OBJECT)));
    }

    /**
     * Introspects the probe object and then reads the row back. The introspection names the probe as bound
     * values against {@code pg_namespace.nspname} and {@code pg_class.relname}, the same rule the target catalog
     * read follows, so a hostile probe name cannot become catalog SQL.
     */
    private static SqlPlan read(TargetIdentifier schema, TargetIdentifier probeName, String relation) {
        return new SqlPlan(
                OperationKind.TARGET_CAPABILITY_PROBE,
                List.of(new ParameterizedStatement(
                                "SELECT COUNT(*) FROM pg_catalog.pg_class c "
                                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                                        + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind = ?",
                                List.of(new SqlValue.Text(schema.name()), new SqlValue.Text(probeName.name()),
                                        ORDINARY_TABLE)),
                        new ParameterizedStatement("SELECT COUNT(*) FROM " + relation, List.of())),
                COUNT,
                TimeoutClass.CAPABILITY_PROBE,
                Set.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT),
                EvidencePolicy.STATEMENT_ONLY);
    }

    /** One statement that returns no rows. */
    private static SqlPlan write(String sql, List<SqlValue> parameters, Set<RequiredPrivilege> privileges) {
        return new SqlPlan(
                OperationKind.TARGET_CAPABILITY_PROBE,
                List.of(new ParameterizedStatement(sql, parameters)),
                ResultSchema.NONE,
                TimeoutClass.CAPABILITY_PROBE,
                privileges,
                EvidencePolicy.STATEMENT_ONLY);
    }
}
