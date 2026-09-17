package com.dbx.dialect.api;

import java.util.List;

/**
 * Per column, whether source and target source-byte lengths are provably comparable (ADR-0040).
 * Validation marks what is not provable {@code NOT_APPLICABLE} rather than comparing anyway. Owned by
 * slice 9.
 */
public record ValidationCapabilities(List<ByteLengthComparability> byteLengthComparability) {

    public ValidationCapabilities {
        byteLengthComparability = Checks.list(byteLengthComparability, "byteLengthComparability");
    }

    public record ByteLengthComparability(ColumnCoordinate column, boolean provablyComparable) {

        public ByteLengthComparability {
            Checks.present(column, "column");
        }
    }
}
