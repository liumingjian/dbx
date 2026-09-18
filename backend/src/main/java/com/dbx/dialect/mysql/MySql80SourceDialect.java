package com.dbx.dialect.mysql;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.ApprovedColumn;
import com.dbx.dialect.api.BoundedReadRequirement;
import com.dbx.dialect.api.ConnectionSemantics;
import com.dbx.dialect.api.DialectId;
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

/** The MySQL 8.0 source dialect. Slices 5 and 6 implement it; a capability not yet landed fails. */
public final class MySql80SourceDialect extends SourceDialect {

    /** Stable across releases: a contract snapshot records it (ADR-0008 §Registration). */
    public static final DialectId ID = new DialectId("mysql-8.0");

    private static final long MIB = 1024L * 1024L;

    /** ADR-0033 §Settings and ADR-0037 §Bulk path: the constants {@code connector.deriveBox} applies to M. */
    private static final BoundedReadRequirement BOUNDED_READ = new BoundedReadRequirement(
            true,
            new BoundedReadRequirement.RowBudget(4 * MIB, 1, 1024),
            BoundedReadRequirement.BufferSizing.EQUAL_TO_FETCH_ROWS,
            new BoundedReadRequirement.RowBudget(64 * MIB, 1, 131072),
            BoundedReadRequirement.Rounding.POWER_OF_TWO_DOWN,
            new BoundedReadRequirement.LargeRecordOverride(1, 4),
            new BoundedReadRequirement.PollInterval(100, Integer.MAX_VALUE),
            "",
            64 * MIB);

    public static final MySql80SourceDialect INSTANCE = new MySql80SourceDialect();

    private MySql80SourceDialect() {
        super(BOUNDED_READ);
    }

    @Override
    public SqlPlan metadataPlan(MetadataScope scope) {
        return MetadataRead.plan(scope);
    }

    @Override
    public List<SourceTableMetadata> normalizeMetadata(ResultRows rows) {
        return MetadataNormalizer.normalize(rows);
    }

    @Override
    public List<SqlPlan> capabilityPlans(MetadataScope scope) {
        return CapabilityPlans.of(scope);
    }

    @Override
    public SqlPlan preflightScanPlan(SourceTableMetadata table, List<ApprovedColumn> approvedColumns,
            List<PreflightObligation> obligations) {
        return PreflightScan.plan(table, approvedColumns, obligations);
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
    public List<SqlPlan> samplingPlan(SamplingKey key, int n) {
        return Sampling.plans(key, n);
    }

    @Override
    public List<KeysetCandidate> keysetCandidates(SourceTableMetadata table) {
        return KeysetCandidates.of(table);
    }

    @Override
    public ProjectionSql queryProjection(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules) {
        return QueryProjection.render(approvedColumns, mappingRules);
    }

    @Override
    public ConnectionSemantics connectionSemantics(MappingOptions options) {
        return new ConnectionSemantics(options.tinyintOneAsBoolean(), options.zeroDateAsNull()
                ? ConnectionSemantics.ZeroDateTimeBehavior.CONVERT_TO_NULL
                : ConnectionSemantics.ZeroDateTimeBehavior.EXCEPTION);
    }
}
