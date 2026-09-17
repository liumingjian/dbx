package com.dbx.dialect.api;

import java.util.List;
import java.util.Optional;

/**
 * The MySQL 8.0 source dialect's capabilities (ADR-0008; {@code docs/spec/dialect.md} §Interface).
 * It interprets and plans; it never opens a connection. The slice implementing each is named on it.
 */
public interface SourceDialect {

    /** Reads types, keys, indexes, defaults, statistics, charset and collation (slice 5). */
    SqlPlan metadataPlan(MetadataScope scope);

    /** Keeps the raw {@code information_schema} facts (slice 5). */
    SourceTableMetadata normalizeMetadata(ResultRows rows);

    /** Read-only capability checks (ADR-0006; slice 5). */
    List<SqlPlan> capabilityPlans(MetadataScope scope);

    /** One table's obligations merged into minimal bounded scans (ADR-0003; slice 6). */
    SqlPlan preflightScanPlan(SourceTableMetadata table, List<PreflightObligation> obligations);

    /** Exact {@code COUNT(*)} and the keyset column's min and max (ADR-0037; slice 6). */
    SqlPlan baselinePlan(SourceTableMetadata table, Optional<KeysetColumn> keysetColumn);

    /** Source-side validation facts (TP §9.2; slice 6). */
    List<SqlPlan> validationFactPlans(List<ValidationItem> items);

    /** Deterministic sampling of {@code n} keys (TP §9.3; slice 6). */
    SqlPlan samplingPlan(SamplingKey key, int n);

    /** Single integer, {@code NOT NULL}, unique columns in ADR-0037 §Choice order (slice 5). */
    List<KeysetCandidate> keysetCandidates(SourceTableMetadata table);

    /** The only renderer of the prune/rename projection (#92; slice 5). */
    ProjectionSql queryProjection(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules);

    /** The fingerprinted Connector/J settings of TP §6.5 (slice 5). */
    ConnectionSemantics connectionSemantics(MappingOptions options);
}
