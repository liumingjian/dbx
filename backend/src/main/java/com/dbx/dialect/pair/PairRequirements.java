package com.dbx.dialect.pair;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.api.ExecutionRequirements;
import com.dbx.dialect.api.Supported;
import com.dbx.dialect.api.ValidationCapabilities;
import java.util.List;

/** Execution requirements and validation capabilities (ADR-0008 §Plans; ADR-0040). Slice 9. */
final class PairRequirements {

    private PairRequirements() {
    }

    static ExecutionRequirements executionRequirements(List<Supported> mappingDecisions) {
        throw new NotImplementedInSlice("pair.executionRequirements", 9);
    }

    static ValidationCapabilities validationCapabilities() {
        throw new NotImplementedInSlice("pair.validationCapabilities", 9);
    }
}
