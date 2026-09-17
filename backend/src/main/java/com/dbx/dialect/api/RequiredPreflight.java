package com.dbx.dialect.api;

/**
 * An exact column-level preflight a mapping requires (TP §6.6 checks 1–6). {@code preflight} derives
 * its obligations from these instead of keeping a second copy of TP §6. Grouped by the TP §6 family
 * that introduced each constant; add yours to your group.
 */
public enum RequiredPreflight {
    // numeric (TP §6.2)
    /** TP §6.6 check 2: under the Boolean switch every value is {@code 0}, {@code 1} or {@code NULL}. */
    BOOLEAN_VALUES_ZERO_OR_ONE,
    /** TP §6.6 check 3: {@code MAX(column) <= 2^63-1}, because the Source cannot read larger values. */
    UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE,
    /**
     * TP §6.6 check 7, TP §7.3: an {@code AUTO_INCREMENT BIGINT UNSIGNED}'s next value is at most
     * {@code 2^63-1}, or the owned {@code bigint} sequence could not continue it.
     */
    AUTO_INCREMENT_NEXT_VALUE_WITHIN_SIGNED_RANGE,

    // character, binary and special (TP §6.3)
    /**
     * TP §6.6 check 1: every selected value and the selected row payload are at most 20 MiB
     * (20,971,520 source bytes), the large-record envelope of ADR-0003.
     */
    LARGE_RECORD_ENVELOPE,
    /**
     * TP §6.6 check 5: every {@code ENUM} value is one of the declared members and is not the empty
     * sentinel MySQL stores for an illegal value, so the target {@code CHECK} cannot reject a row mid-run.
     */
    ENUM_VALUE_DECLARED,

    // temporal (TP §6.4)
    /** TP §6.6 check 4: every {@code TIME} value lies in {@code [00:00:00, 24:00:00)}. */
    TIME_WITHIN_DAY,
    /** TP §6.6 check 6: no zero date, because the operator has not approved converting it to {@code NULL}. */
    NO_ZERO_DATE,
    /**
     * TP §6.6 check 6 under the approved zero-date switch: count the zero dates exactly. An observation, never a
     * blocker; it decides {@link ContractEffect#NOT_NULL_RELAXED_IF_ZERO_DATES_OBSERVED} (TP §7.3).
     */
    ZERO_DATE_ROWS_COUNTED
}
