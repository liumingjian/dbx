package com.dbx.dialect.mysql;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.ApprovedColumn;
import com.dbx.dialect.api.ConnectionSemantics;
import com.dbx.dialect.api.KeysetCandidate;
import com.dbx.dialect.api.KeysetColumn;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingRule;
import com.dbx.dialect.api.MetadataScope;
import com.dbx.dialect.api.PreflightObligation;
import com.dbx.dialect.api.ProjectionSql;
import com.dbx.dialect.api.ResultRows;
import com.dbx.dialect.api.SamplingKey;
import com.dbx.dialect.api.SourceDialect;
import com.dbx.dialect.api.SourceTableMetadata;
import com.dbx.dialect.api.SqlPlan;
import com.dbx.dialect.api.ValidationItem;
import java.util.List;
import java.util.Optional;

/** The MySQL 8.0 source dialect. Slices 5 and 6 implement it; until then every capability fails. */
public final class MySql80SourceDialect implements SourceDialect {

    public static final MySql80SourceDialect INSTANCE = new MySql80SourceDialect();

    private MySql80SourceDialect() {
    }

    @Override
    public SqlPlan metadataPlan(MetadataScope scope) {
        throw new NotImplementedInSlice("source.metadataPlan", 5);
    }

    @Override
    public SourceTableMetadata normalizeMetadata(ResultRows rows) {
        throw new NotImplementedInSlice("source.normalizeMetadata", 5);
    }

    @Override
    public List<SqlPlan> capabilityPlans(MetadataScope scope) {
        throw new NotImplementedInSlice("source.capabilityPlans", 5);
    }

    @Override
    public SqlPlan preflightScanPlan(SourceTableMetadata table, List<PreflightObligation> obligations) {
        throw new NotImplementedInSlice("source.preflightScanPlan", 6);
    }

    @Override
    public SqlPlan baselinePlan(SourceTableMetadata table, Optional<KeysetColumn> keysetColumn) {
        throw new NotImplementedInSlice("source.baselinePlan", 6);
    }

    @Override
    public List<SqlPlan> validationFactPlans(List<ValidationItem> items) {
        throw new NotImplementedInSlice("source.validationFactPlans", 6);
    }

    @Override
    public SqlPlan samplingPlan(SamplingKey key, int n) {
        throw new NotImplementedInSlice("source.samplingPlan", 6);
    }

    @Override
    public List<KeysetCandidate> keysetCandidates(SourceTableMetadata table) {
        throw new NotImplementedInSlice("source.keysetCandidates", 5);
    }

    @Override
    public ProjectionSql queryProjection(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules) {
        throw new NotImplementedInSlice("source.queryProjection", 5);
    }

    @Override
    public ConnectionSemantics connectionSemantics(MappingOptions options) {
        throw new NotImplementedInSlice("source.connectionSemantics", 5);
    }
}
