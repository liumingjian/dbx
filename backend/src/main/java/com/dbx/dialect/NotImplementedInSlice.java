package com.dbx.dialect;

/**
 * What every entry point of {@code dialect.api} throws until the slice that owns it lands.
 *
 * <p>ADR-0008 §Ownership bans optional default-success implementations. A stub returning an empty
 * plan, an empty list or {@code null} breaks that ban where the compiler cannot see it, and the
 * next consumer builds on the lie. So an unimplemented capability fails, naming itself and the
 * slice of {@code docs/spec/dialect.md} §Slices that implements it.
 *
 * <p>Public only so the capability packages of this module can throw it; ADR-0018 rule 1 keeps every
 * other module out of this package. Nobody catches it: it marks unfinished code, not a result.
 */
public final class NotImplementedInSlice extends UnsupportedOperationException {

    private final String capability;
    private final int slice;

    public NotImplementedInSlice(String capability, int slice) {
        super("dialect capability " + capability + " is not implemented: docs/spec/dialect.md §Slices assigns it "
                + "to slice " + slice + ". It fails instead of returning an empty value because ADR-0008 §Ownership "
                + "bans default-success implementations.");
        this.capability = capability;
        this.slice = slice;
    }

    public String capability() {
        return capability;
    }

    public int slice() {
        return slice;
    }
}
