package com.dbx.dialect.api;

/**
 * The typed execution requirements a pair declares (ADR-0008 §Plans). Every platform policy ADR-0009
 * fixes is a constant of this type rather than a component, exactly as {@link ConnectionSemantics} and
 * {@link SinkSettings} do: a policy that is not state cannot be omitted, overridden or weakened by any
 * caller, by any constructor argument or by any {@link MappingOptions} switch. That inexpressibility is
 * obligation 30 of {@code docs/spec/dialect.md}; {@code PairContractTest} defends it.
 *
 * <p>It declares and derives no configuration. {@code batch.max.rows}, the keyset chunk size N, the
 * routing snapshot and the execution signature are {@code connector.deriveBox}'s, computed from M, the
 * table's largest row byte length ({@code conflicts.md} §dialect). Nothing here is graded either: the
 * 20 MiB boundary below is the number {@code preflight} grades against, not a verdict.
 *
 * @param boundedRead the source dialect's own {@link BoundedReadRequirement}, carried through unchanged
 * @param largeRecordEnvelopePendingPreflight whether some mapping decision requires
 *     {@link RequiredPreflight#LARGE_RECORD_ENVELOPE}, so the table may turn out to be a large record
 *     table (大记录表) and {@link #LARGE_RECORD_ISOLATION} is in force pending preflight's exact scan
 */
public record ExecutionRequirements(BoundedReadRequirement boundedRead, boolean largeRecordEnvelopePendingPreflight) {

    /** ADR-0003: the source support boundary, per value and per row, in source-representation bytes. */
    public static final long SOURCE_SUPPORT_BOUNDARY_BYTES = 20_971_520L;

    /** ADR-0003: Kafka's separate transport envelope, in bytes. Never the same number as the boundary above. */
    public static final long TRANSPORT_ENVELOPE_BYTES = 26_214_400L;

    /** ADR-0009 §Ownership: Connect never creates the target table; {@code contract} and {@code target} do. */
    public static final boolean AUTO_CREATE = false;

    /** ADR-0009 §Ownership: Connect never evolves the target table. */
    public static final boolean AUTO_EVOLVE = false;

    /** ADR-0003: a large record table gets its own box; it never shares one with an ordinary table. */
    public static final boolean LARGE_RECORD_ISOLATION = true;

    /** ADR-0009 §Ownership: connector and topic names carry run and box identity, so no run can read another's. */
    public static final boolean RUN_ISOLATED_NAMING = true;

    /** ADR-0009 §Failure and evidence: a record is never silently skipped, truncated or redirected. */
    public static final boolean SKIP_RECORD_ALLOWED = false;

    /** ADR-0009 §Failure and evidence: v1 has no dead-letter queue, and never one as a success path. */
    public static final boolean DEAD_LETTER_QUEUE_ALLOWED = false;

    /** ADR-0009: Kafka Connect is the sole data plane; DBX never becomes a second record-copy engine. */
    public static final boolean SECOND_DATA_PATH_ALLOWED = false;

    public ExecutionRequirements {
        Checks.present(boundedRead, "boundedRead");
    }
}
