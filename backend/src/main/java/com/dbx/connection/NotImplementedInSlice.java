package com.dbx.connection;

/**
 * What every entry point of {@code connection.api} throws until the slice that owns it lands.
 *
 * <p>ADR-0008 §Ownership bans optional default-success implementations. In a crypto module the ban
 * matters more than usual: an {@code encrypt} that returns an empty ciphertext is indistinguishable,
 * at the call site, from one that worked, and the next agent stores that empty value as a credential
 * version (凭据版本). So an unimplemented capability fails, naming itself and the slice of
 * {@code docs/spec/connection.md} §Slices that implements it.
 *
 * <p>This is {@code connection}'s own type, modelled on {@code dialect}'s and deliberately not
 * imported from it: {@code com.dbx.dialect.NotImplementedInSlice} lives outside {@code dialect.api},
 * so naming it would break obligation 1 and ADR-0018 rule 1 — the leaf rule slice 1 exists to make
 * bite.
 *
 * <p>Public only so the capability packages of this module can throw it; ADR-0018 rule 1 keeps every
 * other module out of this package. Nobody catches it: it marks unfinished code, not a result.
 */
public final class NotImplementedInSlice extends UnsupportedOperationException {

    private final String capability;
    private final int slice;

    public NotImplementedInSlice(String capability, int slice) {
        super("connection capability " + capability + " is not implemented: docs/spec/connection.md §Slices "
                + "assigns it to slice " + slice + ". It fails instead of returning an empty value because "
                + "ADR-0008 §Ownership bans default-success implementations.");
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
