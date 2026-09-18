package com.dbx.dialect.postgres;

import com.dbx.dialect.api.RequiredPrivilege;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@code target.leastPrivilegeSql}: what a failed capability check hands a DBA (ADR-0006 §Capability checks,
 * obligation 27). It is <b>text, not a plan</b> — DBX renders it, shows it, and never runs it; a plan is
 * something DBX executes, and ADR-0006 keeps every {@code GRANT} outside the bounded DBX-owned-object rule in
 * the DBA's hands.
 *
 * <p>Every account, schema, database and object is a marked {@code <placeholder>}: DBX never invents an account
 * name. The text is deterministic and ordered by privilege name, so two checks that found the same gap produce
 * byte-identical text, and an empty gap produces the empty string rather than an empty statement to run.
 *
 * <p>What it never renders, per ADR-0006: {@code SUPERUSER}, {@code ALL PRIVILEGES}, role creation, altered
 * default privileges, a grant across a whole schema's objects, or taking a privilege back from the customer.
 * {@code GRANT USAGE, CREATE ON SCHEMA} is a privilege on the one named schema, not a grant over the objects
 * in it, and it is the narrowest form PostgreSQL offers for creating a DBX-owned table there.
 *
 * <p>A source privilege has no PostgreSQL statement at all. It is reported as a comment naming the MySQL
 * account, because rendering a plausible PostgreSQL grant for it would be an invention.
 */
final class LeastPrivilege {

    private static final List<String> HEADER = List.of(
            "-- Least-privilege SQL for a DBA to review and run. DBX renders it and never runs it, and never",
            "-- invents an account name (ADR-0006 §Capability checks).",
            "-- Replace <role> with the account DBX connects with, <schema> with the target schema, <database>",
            "-- with the target database, and <table> or <sequence> with one DBX-owned object; repeat an object",
            "-- line once per object.");

    private LeastPrivilege() {
    }

    static String sql(Set<RequiredPrivilege> missing) {
        if (missing.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>(HEADER);
        missing.stream().sorted(Comparator.comparing(RequiredPrivilege::name))
                .forEach(privilege -> lines.addAll(clause(privilege)));
        return String.join("\n", lines) + "\n";
    }

    /** The closed answer per privilege; a new constant makes the compiler ask for its line. */
    private static List<String> clause(RequiredPrivilege privilege) {
        return switch (privilege) {
            case SOURCE_READ_METADATA -> List.of(
                    "-- SOURCE_READ_METADATA: a MySQL privilege on the source account. There is no PostgreSQL",
                    "-- statement for it, so DBX renders none.");
            case SOURCE_SELECT -> List.of(
                    "-- SOURCE_SELECT: a MySQL privilege on the source account, on the selected tables only.",
                    "-- There is no PostgreSQL statement for it, so DBX renders none.");
            case TARGET_CREATE_IN_SCHEMA -> List.of(
                    "-- TARGET_CREATE_IN_SCHEMA: create DBX-owned tables, sequences and the capability probe",
                    "-- object in the target schema. This is a privilege on the schema itself, not on the",
                    "-- objects it already holds.",
                    "GRANT USAGE, CREATE ON SCHEMA <schema> TO <role>;");
            case TARGET_CREATE_SCHEMA -> List.of(
                    "-- TARGET_CREATE_SCHEMA: create the target schema when it does not exist yet. This carries",
                    "-- no privilege over any existing schema or object in the database.",
                    "GRANT CREATE ON DATABASE <database> TO <role>;");
            case TARGET_OWN_OBJECT -> List.of(
                    "-- TARGET_OWN_OBJECT: read, insert into and truncate the objects DBX created, one statement",
                    "-- per object. Dropping one needs ownership, which the account that created it already has.",
                    "GRANT SELECT, INSERT, TRUNCATE ON TABLE <schema>.<table> TO <role>;",
                    "GRANT USAGE, SELECT, UPDATE ON SEQUENCE <schema>.<sequence> TO <role>;");
            case TARGET_READ_CATALOG -> List.of(
                    "-- TARGET_READ_CATALOG: look DBX-owned objects up in pg_catalog, which needs USAGE on the",
                    "-- schema that holds them.",
                    "GRANT USAGE ON SCHEMA <schema> TO <role>;");
        };
    }
}
