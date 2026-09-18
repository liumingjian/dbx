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

    /** How this dialect's reads stay bounded in bytes (ADR-0033; ADR-0037), without M. */
    public final BoundedReadRequirement boundedRead() {
        return boundedRead;
    }

    /**
     * One plan reading types, keys, indexes, foreign keys, defaults, comments, charset, collation and fresh
     * statistics of every table in {@code scope}, names bound as values (obligations 15, 19b; slice 5).
     */
    public abstract SqlPlan metadataPlan(MetadataScope scope);

    /**
     * The rows of one {@link #metadataPlan} as one {@link SourceTableMetadata} per table, in the order the
     * server returned them, keeping the raw {@code information_schema} facts (slice 5).
     */
    public abstract List<SourceTableMetadata> normalizeMetadata(ResultRows rows);

    /** Read-only capability checks (ADR-0006; slice 5). */
    public abstract List<SqlPlan> capabilityPlans(MetadataScope scope);

    /**
     * One table's whole preflight merged into one bounded aggregate scan (ADR-0003 ¶2; TP §6.6; slice 6).
     *
     * <p>{@code approvedColumns} is an argument because neither {@code table} nor {@code obligations} implies
     * it: ADR-0003 ¶2 measures every approved extraction expression, and a numeric column carries no
     * {@code LARGE_RECORD_ENVELOPE} obligation yet still counts toward the row payload (spec #134 correction 1).
     *
     * <p>Returns facts, never findings: every threshold is compared in {@code preflight}. An obligation naming
     * a column that is not in {@code approvedColumns}, or not in {@code table}, throws
     * {@link IllegalArgumentException} naming the coordinate.
     */
    public abstract SqlPlan preflightScanPlan(SourceTableMetadata table, List<ApprovedColumn> approvedColumns,
            List<PreflightObligation> obligations);

    /** Exact {@code COUNT(*)} and the keyset column's min and max (ADR-0037; slice 6). */
    public abstract SqlPlan baselinePlan(SourceTableMetadata table, Optional<KeysetColumn> keysetColumn);

    /** Source-side validation facts (TP §9.2; slice 6). */
    public abstract List<SqlPlan> validationFactPlans(List<ValidationItem> items);

    /**
     * Deterministic sampling of {@code n} keys, one plan per statement so every plan keeps one honest
     * cardinality (TP §9.3; slice 6). Seek thresholds present: one seek plan per threshold, in threshold
     * order. Absent: the first ⌈n/2⌉ rows ascending and the last ⌊n/2⌋ rows descending. A non-positive
     * {@code n} throws {@link IllegalArgumentException}.
     */
    public abstract List<SqlPlan> samplingPlan(SamplingKey key, int n);

    /** Single integer, {@code NOT NULL}, unique columns in ADR-0037 §Choice order (slice 5). */
    public abstract List<KeysetCandidate> keysetCandidates(SourceTableMetadata table);

    /** The only renderer of the prune/rename projection (#92; slice 5). */
    public abstract ProjectionSql queryProjection(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules);

    /** The fingerprinted Connector/J settings of TP §6.5 (slice 5). */
    public abstract ConnectionSemantics connectionSemantics(MappingOptions options);
}
