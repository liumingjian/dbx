package com.dbx.golden;

/** A {@code -Pgolden.update=<name>} value the harness refuses to act on. */
public class GoldenUpdateRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GoldenUpdateRejected(String message) {
        super(message);
    }
}
