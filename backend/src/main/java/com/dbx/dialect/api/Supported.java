package com.dbx.dialect.api;

import java.util.List;

/**
 * A complete mapping decision (ADR-0008 §Contract; TP §6.1). Every field is present; an empty list
 * means "none required", decided, not omitted. The contract freezes this whole value, so the runtime
 * representations are never recomputed from defaults.
 */
public record Supported(
        TargetType targetType,
        ExtractionIntent extractionIntent,
        ConnectRepresentation connectRepresentation,
        JdbcBinder jdbcBinder,
        ValueSemantics valueSemantics,
        List<RequiredPreflight> requiredPreflights,
        List<ContractEffect> contractEffects,
        List<MappingAlternative> alternatives,
        List<MappingNotice> notices)
        implements MappingDecision {

    public Supported {
        Checks.present(targetType, "targetType");
        Checks.present(extractionIntent, "extractionIntent");
        Checks.present(connectRepresentation, "connectRepresentation");
        Checks.present(jdbcBinder, "jdbcBinder");
        Checks.present(valueSemantics, "valueSemantics");
        requiredPreflights = Checks.list(requiredPreflights, "requiredPreflights");
        contractEffects = Checks.list(contractEffects, "contractEffects");
        alternatives = Checks.list(alternatives, "alternatives");
        notices = Checks.list(notices, "notices");
    }
}
