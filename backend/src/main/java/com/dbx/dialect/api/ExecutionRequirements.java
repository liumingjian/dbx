package com.dbx.dialect.api;

/**
 * The typed execution requirements a pair declares (ADR-0008 §Plans). They cannot omit or override
 * the platform policies ADR-0009 fixes; the migration core injects those. Owned by slice 9.
 */
public record ExecutionRequirements(BoundedReadRequirement boundedRead) {

    public ExecutionRequirements {
        Checks.present(boundedRead, "boundedRead");
    }
}
