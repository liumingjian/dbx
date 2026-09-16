package com.dbx.golden;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The registry of golden sets, addressed by name.
 *
 * <p>ADR-0022 rejects "a general {@code -Pgolden.update} with no name" because one flag would
 * silently rewrite every set. Names are therefore a closed list: an unknown name fails instead of
 * creating a set, so a typo can never quietly fork a second copy of a regression net.
 *
 * <p><b>Adding a set:</b> a later ticket adds exactly one line to {@link #register} and puts its
 * files under {@code src/test/resources/golden/<name>/}. The four sets ADR-0022 lists — the
 * type-mapping matrix, contract to DDL rendering, the error-translation rules, and box plans — are
 * registered by the tickets that create them, not here.
 */
public final class GoldenSets {

    /** Where golden files live, relative to the repository root. The trailer gate watches this prefix. */
    public static final String GOLDEN_ROOT = "backend/src/test/resources/golden";

    private static final Map<String, GoldenSource> REGISTRY = register(
            new HarnessGoldenSource());

    private GoldenSets() {
    }

    private static Map<String, GoldenSource> register(GoldenSource... sources) {
        Map<String, GoldenSource> byName = new LinkedHashMap<>();
        for (GoldenSource source : sources) {
            GoldenSource clash = byName.put(source.name(), source);
            if (clash != null) {
                throw new IllegalStateException("Two golden sources claim the name " + source.name());
            }
        }
        return Map.copyOf(byName);
    }

    /** Registered names, sorted so a rejection message reads the same way twice. */
    public static Set<String> names() {
        return new TreeSet<>(REGISTRY.keySet());
    }

    /** The source for {@code name}. Rejects anything {@link GoldenUpdateRequest} rejects. */
    public static GoldenSource require(String name) {
        return REGISTRY.get(GoldenUpdateRequest.validate(name, names()));
    }
}
