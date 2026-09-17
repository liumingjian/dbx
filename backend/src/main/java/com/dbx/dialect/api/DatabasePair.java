package com.dbx.dialect.api;

import java.util.List;
import java.util.Optional;

/**
 * A directed, versioned, explicitly registered database pair (ADR-0008). It owns the
 * {@code TypeMapper}, cross-endpoint identifier mapping and compatibility decisions, and never
 * owns lifecycle, scheduling or gates. The slice implementing each entry point is named on it.
 */
public non-sealed interface DatabasePair extends PairSelection {

    /** Dialect ids, mapping version and certification version (slice 2). */
    PairDescriptor descriptor();

    SourceDialect source();

    TargetDialect target();

    /** {@code TypeMapper}: a closed decision for every column, never null (TP §6.1; slice 3). */
    MappingDecision map(SourceColumn column, MappingOptions options);

    /** Byte-counted identifier mapping under TP §7.1 (slice 4). */
    IdentifierMapping mapIdentifier(SourceCoordinate sourceCoordinate, Optional<MappingRule> mappingRule);

    /** Typed execution requirements, bounded read included (ADR-0033; slice 9). */
    ExecutionRequirements executionRequirements(List<Supported> mappingDecisions);

    /** Which comparisons validation may make (ADR-0040; slice 9). */
    ValidationCapabilities validationCapabilities();

    /** A codec that reads its own version and never upgrades it silently (slice 2). */
    CodecSelection descriptorCodec(DescriptorVersion version);
}
