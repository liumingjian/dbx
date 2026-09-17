package com.dbx.dialect.api;

/**
 * Exactly the two v1 task switches (TP §6.1). A third is a decision ticket, not a field.
 *
 * @param tinyintOneAsBoolean map {@code TINYINT(1)}/{@code BOOL}/{@code BOOLEAN} to {@code boolean},
 *     under a required {@code {0,1}} preflight
 * @param zeroDateAsNull convert zero dates to {@code NULL}
 */
public record MappingOptions(boolean tinyintOneAsBoolean, boolean zeroDateAsNull) {

    /** Both switches off, which is what a task starts with. */
    public static final MappingOptions DEFAULTS = new MappingOptions(false, false);
}
