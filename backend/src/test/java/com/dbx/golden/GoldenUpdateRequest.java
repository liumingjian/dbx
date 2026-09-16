package com.dbx.golden;

import java.util.Set;

/**
 * Validates the {@code -Pgolden.update=<name>} value.
 *
 * <p>ADR-0022 §Explicit updates: the task "rewrites exactly one named golden set" and "rejects
 * wildcards and 'all'". The rejections live here, in a pure function, so L1 can watch each of them
 * fire without launching a build; the Gradle task and the updater both route through it.
 */
public final class GoldenUpdateRequest {

    /** Any of these in a name means the agent was aiming at more than one set. */
    private static final String WILDCARD_CHARACTERS = "*?%[]{},";

    private GoldenUpdateRequest() {
    }

    /**
     * @return the single set name to rewrite
     * @throws GoldenUpdateRejected when the value is absent, plural, or not registered
     */
    public static String validate(String rawName, Set<String> registered) {
        if (rawName == null || rawName.isBlank()) {
            throw new GoldenUpdateRejected(
                    "-Pgolden.update needs exactly one set name (ADR-0022 §Explicit updates). "
                            + "Registered sets: " + registered + ".");
        }
        String name = rawName.strip();
        if (name.equalsIgnoreCase("all")) {
            throw new GoldenUpdateRejected(
                    "-Pgolden.update=all is rejected: ADR-0022 exists to stop one flag from "
                            + "flattening every regression net. Name one set. "
                            + "Registered sets: " + registered + ".");
        }
        for (int i = 0; i < name.length(); i++) {
            if (WILDCARD_CHARACTERS.indexOf(name.charAt(i)) >= 0) {
                throw new GoldenUpdateRejected(
                        "-Pgolden.update=" + name + " looks like a pattern or a list. The task "
                                + "rewrites exactly one named set (ADR-0022 §Explicit updates). "
                                + "Registered sets: " + registered + ".");
            }
        }
        if (!registered.contains(name)) {
            throw new GoldenUpdateRejected(
                    "Unknown golden set '" + name + "'. The task fails rather than creating a set, "
                            + "because a typo must not fork a second copy of a regression net. "
                            + "Register it in com.dbx.golden.GoldenSets first. "
                            + "Registered sets: " + registered + ".");
        }
        return name;
    }
}
