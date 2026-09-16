package com.dbx.golden;

import java.util.Map;

/**
 * Renders one golden set from fixed inputs.
 *
 * <p>A source is the only thing that may produce golden content, and it is deliberately not the
 * reader tests use: ADR-0022 requires that a failing assertion can never repair itself. Sources are
 * driven exclusively by the {@code goldenUpdate} task.
 *
 * <p>Implementations must be deterministic — ADR-0022 §Golden files: inputs are fixed examples,
 * never random, so a change in behaviour shows up as a real diff rather than noise.
 */
public interface GoldenSource {

    /** The registry name the set is addressed by, i.e. the {@code <name>} in {@code -Pgolden.update=<name>}. */
    String name();

    /** File name (relative to the set directory) to file content. */
    Map<String, String> render();
}
