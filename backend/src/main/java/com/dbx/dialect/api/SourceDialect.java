package com.dbx.dialect.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The MySQL 8.0 source dialect's capabilities (ADR-0008; {@code docs/spec/dialect.md} §Interface).
 * It interprets and plans; it never opens a connection. The slice implementing each is named on it.
 *
 * <p>A class rather than an interface so that its one constructor can demand a
 * {@link BoundedReadRequirement}: a source dialect that does not declare how its reads stay bounded
 * does not compile, and one that passes {@code null} does not construct (obligation 9; ADR-0033
 * §Bounded Source reads are a mandatory platform policy).
 */
public abstract class SourceDialect {

    private final BoundedReadRequirement boundedRead;

    protected SourceDialect(BoundedReadRequirement boundedRead) {
        this.boundedRead = Objects.requireNonNull(boundedRead,
                "ADR-0033: a source dialect without a bounded-read declaration cannot be registered");
    }

    /** How this dialect's reads stay bounded in bytes (ADR-0033); its content is slice 5's. */
    public final BoundedReadRequirement boundedRead() {
        return boundedRead;
    }

    /** Reads types, keys, indexes, defaults, statistics, charset and collation (slice 5). */
    public abstract SqlPlan metadataPlan(MetadataScope scope);

    /** Keeps the raw {@code information_schema} facts (slice 5). */
    public abstract SourceTableMetadata normalizeMetadata(ResultRows rows);

    /** Read-only capability checks (ADR-0006; slice 5). */
    public abstract List<SqlPlan> capabilityPlans(MetadataScope scope);

    /** One table's obligations merged into minimal bounded scans (ADR-0003; slice 6). */
    public abstract SqlPlan preflightScanPlan(SourceTableMetadata table, List<PreflightObligation> obligations);

    /** Exact {@code COUNT(*)} and the keyset column's min and max (ADR-0037; slice 6). */
    public abstract SqlPlan baselinePlan(SourceTableMetadata table, Optional<KeysetColumn> keysetColumn);

    /** Source-side validation facts (TP §9.2; slice 6). */
    public abstract List<SqlPlan> validationFactPlans(List<ValidationItem> items);

    /** Deterministic sampling of {@code n} keys (TP §9.3; slice 6). */
    public abstract SqlPlan samplingPlan(SamplingKey key, int n);

    /** Single integer, {@code NOT NULL}, unique columns in ADR-0037 §Choice order (slice 5). */
    public abstract List<KeysetCandidate> keysetCandidates(SourceTableMetadata table);

    /** The only renderer of the prune/rename projection (#92; slice 5). */
    public abstract ProjectionSql queryProjection(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules);

    /** The fingerprinted Connector/J settings of TP §6.5 (slice 5). */
    public abstract ConnectionSemantics connectionSemantics(MappingOptions options);
}
