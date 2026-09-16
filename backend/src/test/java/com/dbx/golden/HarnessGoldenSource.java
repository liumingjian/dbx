package com.dbx.golden;

import java.util.Map;

/**
 * The self-test set. It pins nothing about the product — it exists so the harness has something to
 * read, update and diff before any of ADR-0022's four real sets exist.
 *
 * <p>The rendering is trivial on purpose: when this set breaks, the harness is broken, not a
 * mapping table.
 */
final class HarnessGoldenSource implements GoldenSource {

    static final String SET = "harness";
    static final String FILE = "echo.txt";

    /** The fixed inputs. A real set would list matrix rows or table sizes here. */
    private static final String[] INPUTS = {"alpha", "beta", "gamma"};

    @Override
    public String name() {
        return SET;
    }

    @Override
    public Map<String, String> render() {
        StringBuilder rendered = new StringBuilder();
        for (String input : INPUTS) {
            rendered.append(input).append(" -> ").append(input.length()).append('\n');
        }
        return Map.of(FILE, rendered.toString());
    }
}
