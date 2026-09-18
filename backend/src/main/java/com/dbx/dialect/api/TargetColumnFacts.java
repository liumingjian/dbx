package com.dbx.dialect.api;

import java.util.List;
import java.util.Optional;

/**
 * One target column as the PostgreSQL catalogs read it back (TP §7.4; ADR-0011 §DDL and structural proof).
 *
 * <p>It is permissive on purpose: a catalog can hold what an approved contract cannot express. A type DBX
 * cannot name survives as {@code catalogType} with an empty {@link #type()}, and an identity DBX cannot name
 * survives as {@code identityText} with an empty {@link #identity()}. Either one is a difference for
 * {@code contract.prove} to report, never an exception. {@link TargetColumn} keeps the contract-side
 * invariants instead. Owned by slice 8.
 */
public record TargetColumnFacts(
        TargetIdentifier name,
        String catalogType,
        Optional<TargetType> type,
        Nullability nullability,
        Optional<String> defaultExpression,
        String identityText,
        Optional<IdentityIntent> identity,
        List<String> checkDefinitions,
        List<String> enumMembers) {

    public TargetColumnFacts {
        Checks.present(name, "name");
        Checks.nonEmpty(catalogType, "catalogType");
        Checks.present(type, "type");
        Checks.present(nullability, "nullability");
        Checks.present(defaultExpression, "defaultExpression");
        // The catalog spells "no identity" as the empty string, so this one is present but may be empty.
        Checks.present(identityText, "identityText");
        Checks.present(identity, "identity");
        checkDefinitions = Checks.list(checkDefinitions, "checkDefinitions");
        enumMembers = Checks.list(enumMembers, "enumMembers");
    }
}
