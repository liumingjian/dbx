package com.dbx.dialect.postgres;

import com.dbx.dialect.NotImplementedInSlice;
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
import java.util.Set;

/** The PostgreSQL 15 target dialect. Slices 7 and 8 implement it; until then every capability fails. */
public final class Postgres15TargetDialect implements TargetDialect {

    /** Stable across releases: a contract snapshot records it (ADR-0008 §Registration). */
    public static final DialectId ID = new DialectId("postgresql-15");

    public static final Postgres15TargetDialect INSTANCE = new Postgres15TargetDialect();

    private Postgres15TargetDialect() {
    }

    @Override
    public SqlPlan ddlPlan(TargetTable table) {
        throw new NotImplementedInSlice("target.ddlPlan", 7);
    }

    @Override
    public SqlPlan catalogReadPlan(List<TargetTableCoordinate> coordinates) {
        throw new NotImplementedInSlice("target.catalogReadPlan", 8);
    }

    @Override
    public List<SqlPlan> capabilityProbePlans(TargetIdentifier schema, TargetIdentifier probeName) {
        throw new NotImplementedInSlice("target.capabilityProbePlans", 8);
    }

    @Override
    public List<SqlPlan> maintenancePlans(List<MaintenanceAction> actions) {
        throw new NotImplementedInSlice("target.maintenancePlans", 8);
    }

    @Override
    public List<SqlPlan> validationFactPlans(List<ValidationItem> items) {
        throw new NotImplementedInSlice("target.validationFactPlans", 8);
    }

    @Override
    public SqlPlan samplingLookupPlan(SamplingLookupKeys keys) {
        throw new NotImplementedInSlice("target.samplingLookupPlan", 8);
    }

    @Override
    public TargetTableFacts normalizeCatalog(ResultRows rows) {
        throw new NotImplementedInSlice("target.normalizeCatalog", 8);
    }

    @Override
    public List<Statement> supplementalStatements(List<DeferredStructure> deferredStructures) {
        throw new NotImplementedInSlice("target.supplementalStatements", 7);
    }

    @Override
    public String leastPrivilegeSql(Set<RequiredPrivilege> missing) {
        throw new NotImplementedInSlice("target.leastPrivilegeSql", 8);
    }

    @Override
    public SinkSettings sinkSettings() {
        throw new NotImplementedInSlice("target.sinkSettings", 7);
    }
}
