package com.dbx.dialect.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Per column, whether source and target source-byte lengths are provably comparable (ADR-0040).
 * Validation marks what is not provable {@code NOT_APPLICABLE / BYTE_LENGTH_NOT_COMPARABLE} rather than
 * comparing anyway. Owned by slice 9.
 */
public record ValidationCapabilities(List<ByteLengthComparability> byteLengthComparability) {

    public ValidationCapabilities {
        byteLengthComparability = Checks.list(byteLengthComparability, "byteLengthComparability");
        refuseDuplicates(byteLengthComparability);
    }

    /**
     * One coordinate, one answer. Two answers for the same column would let a caller pick the one it
     * liked, which is exactly the "compare anyway" ADR-0040 forbids.
     */
    private static void refuseDuplicates(List<ByteLengthComparability> answers) {
        List<ColumnCoordinate> seen = new ArrayList<>(answers.size());
        for (ByteLengthComparability answer : answers) {
            if (seen.contains(answer.column())) {
                throw new IllegalArgumentException("ADR-0040: byte-length comparability is decided once per column, "
                        + "but " + answer.column() + " appears twice");
            }
            seen.add(answer.column());
        }
    }

    public record ByteLengthComparability(ColumnCoordinate column, boolean provablyComparable) {

        public ByteLengthComparability {
            Checks.present(column, "column");
        }
    }
}
