package com.dbx.dialect.postgres;

import com.dbx.dialect.api.DeferredStructure;
import com.dbx.dialect.api.DialectId;
import com.dbx.dialect.api.MaintenanceAction;
import com.dbx.dialect.api.RequiredPrivilege;
import com.dbx.dialect.api.ResultRows;
import com.dbx.dialect.api.SamplingLookupKeys;
import com.dbx.dialect.api.SinkSettings;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.Statement;
import com.dbx.dialect.api.TargetDialect;
import com.dbx.dialect.api.TargetIdentifier;
import com.dbx.dialect.api.TargetTable;
import com.dbx.dialect.api.TargetTableCoordinate;
import com.dbx.dialect.api.TargetTableFacts;
import com.dbx.dialect.api.ValidationItem;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The PostgreSQL 15 target dialect, implemented in full by slices 7 and 8. A directed pair
 * constructs it with its source side's {@link SourceDefinitions}, the only source text supplemental SQL quotes.
 */
public final class Postgres15TargetDialect implements TargetDialect {

    /** Stable across releases: a contract snapshot records it (ADR-0008 §Registration). */
    public static final DialectId ID = new DialectId("postgresql-15");

    private final PostgresSupplemental supplemental;

    public Postgres15TargetDialect(SourceDefinitions sourceDefinitions) {
        this.supplemental = new PostgresSupplemental(
                Objects.requireNonNull(sourceDefinitions, "sourceDefinitions is required"));
    }

    @Override
    public SqlPlan ddlPlan(TargetTable table) {
        return PostgresDdl.plan(Objects.requireNonNull(table, "table is required"));
    }

    @Override
    public SqlPlan catalogReadPlan(List<TargetTableCoordinate> coordinates) {
        return CatalogRead.plan(Objects.requireNonNull(coordinates, "coordinates are required"));
    }

    @Override
    public List<SqlPlan> capabilityProbePlans(TargetIdentifier schema, TargetIdentifier probeName) {
        return CapabilityProbe.plans(Objects.requireNonNull(schema, "schema is required"),
                Objects.requireNonNull(probeName, "probeName is required"));
    }

    @Override
    public List<SqlPlan> maintenancePlans(List<MaintenanceAction> actions) {
        return Maintenance.plans(Objects.requireNonNull(actions, "actions are required"));
    }

    @Override
    public List<SqlPlan> validationFactPlans(List<ValidationItem> items) {
        return ValidationFacts.plans(Objects.requireNonNull(items, "items are required"));
    }

    @Override
    public List<SqlPlan> samplingLookupPlan(SamplingLookupKeys keys) {
        return SamplingLookup.plans(Objects.requireNonNull(keys, "keys are required"));
    }

    @Override
    public List<TargetTableFacts> normalizeCatalog(List<TargetTableCoordinate> coordinates, ResultRows rows) {
        return CatalogNormalizer.normalize(coordinates, rows);
    }

    @Override
    public List<Statement> supplementalStatements(List<DeferredStructure> deferredStructures) {
        return supplemental.statements(Objects.requireNonNull(deferredStructures, "deferredStructures is required"));
    }

    @Override
    public String leastPrivilegeSql(Set<RequiredPrivilege> missing) {
        return LeastPrivilege.sql(Objects.requireNonNull(missing, "missing is required"));
    }

    @Override
    public SinkSettings sinkSettings() {
        return SinkSettings.V1;
    }
}
