package com.dbx.dialect.api;

import java.util.List;
import java.util.Set;

/**
 * The PostgreSQL 15 target dialect's capabilities (ADR-0008; {@code docs/spec/dialect.md} §Interface).
 * It renders and interprets; it never opens a connection. The slice implementing each is named on it.
 */
public interface TargetDialect {

    /** The minimal writable table of an approved contract (ADR-0011 §DDL; slice 7). */
    SqlPlan ddlPlan(TargetTable table);

    /** Reads what structural proof compares (TP §7.4; slice 8). */
    SqlPlan catalogReadPlan(List<TargetTableCoordinate> coordinates);

    /** Probes on an isolated, uniquely named object (ADR-0006; slice 8). */
    List<SqlPlan> capabilityProbePlans(TargetIdentifier schema, TargetIdentifier probeName);

    /** Drops by OID, never {@code CASCADE} (ADR-0023; slice 8). */
    List<SqlPlan> maintenancePlans(List<MaintenanceAction> actions);

    /** Target-side validation facts (TP §9.2; slice 8). */
    List<SqlPlan> validationFactPlans(List<ValidationItem> items);

    /** Typed key lookups for sampled keys (TP §9.3; slice 8). */
    SqlPlan samplingLookupPlan(SamplingLookupKeys keys);

    /**
     * The rows of one {@link #catalogReadPlan} as one {@link TargetTableFacts} per table, in the order the
     * server returned them: everything structural proof compares, plus the {@code pg_class} OID (TP §7.4;
     * ADR-0023; slice 8). It compares nothing — {@code prove} is {@code contract}'s.
     *
     * <p>It takes the coordinates the plan asked for as well as the rows, because the guard the sub-spec
     * demands — a row set holding a table nobody asked for is a broken read, not a difference — is not
     * expressible from rows alone.
     */
    List<TargetTableFacts> normalizeCatalog(List<TargetTableCoordinate> coordinates, ResultRows rows);

    /** Executable supplemental SQL, foreign keys last (ADR-0026; slice 7). */
    List<Statement> supplementalStatements(List<DeferredStructure> deferredStructures);

    /** Text for the DBA to run, never a plan (ADR-0006; slice 8). */
    String leastPrivilegeSql(Set<RequiredPrivilege> missing);

    /** The fixed Sink settings of ADR-0011 §Sink contract (slice 7). */
    SinkSettings sinkSettings();
}
