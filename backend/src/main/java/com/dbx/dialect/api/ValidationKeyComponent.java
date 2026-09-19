package com.dbx.dialect.api;

/**
 * One component of the key TP §9.2's key facts are gathered for, with the ordering the pair can prove
 * across the two engines.
 *
 * <p>The ordering is what decides whether extrema are planned at all. TP §9.2 compares {@code MIN}/{@code MAX}
 * "only for single keys with provably equivalent ordering: integers, exact decimals, dates, and byte-ordered
 * binary keys", and marks string, collated and composite extrema not applicable rather than manufacture a
 * cross-database order. So a string or collated key plans <strong>no</strong> extrema here — it is not an
 * unplanned column {@code validation} later ignores, it is a query DBX never sends.
 *
 * <p>The ordering names a value domain, never a type name: each endpoint dialect declares the extremum in
 * <em>its own</em> type text (MySQL {@code decimal}, PostgreSQL {@code numeric}), and a type name in this
 * package would belong to one of them.
 *
 * @param column the key component's source coordinate
 * @param ordering the ordering the pair can prove for it
 */
public record ValidationKeyComponent(ColumnCoordinate column, Ordering ordering) {

    public ValidationKeyComponent {
        Checks.present(column, "column");
        Checks.present(ordering, "ordering");
    }

    /** The four orderings TP §9.2 accepts, plus the one refusal. */
    public enum Ordering {

        /** An integer key. Its extrema are read as an arbitrary-precision decimal, never as a Java {@code long}. */
        INTEGER(true),

        /** An exact decimal key. Its extrema keep their scale, so they are read as an arbitrary-precision decimal. */
        EXACT_DECIMAL(true),

        /** A date key: the two engines order dates identically. */
        DATE(true),

        /** A binary key ordered byte by byte, which both engines do. */
        BYTE_ORDERED_BINARY(true),

        /**
         * A string, collated or otherwise engine-dependent order: TP §9.2 marks its extrema not applicable.
         * A composite key is refused by arity, not by this constant — every component of a composite key may
         * well be an integer, and it is the composite-ness that denies a single order.
         */
        NOT_ORDER_COMPARABLE(false);

        private final boolean extremaComparable;

        Ordering(boolean extremaComparable) {
            this.extremaComparable = extremaComparable;
        }

        /** Whether TP §9.2 lets a single key of this ordering be compared by {@code MIN}/{@code MAX}. */
        public boolean extremaComparable() {
            return extremaComparable;
        }
    }
}
