package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code target.sinkSettings}: obligation 29, ADR-0011 §Sink contract, TP §6.5. */
class SinkSettingsContractTest {

    @Test
    void theSinkPropertyListIsFixed() {
        assertEquals(List.of(
                        "auto.create=false",
                        "auto.evolve=false",
                        "insert.mode=insert",
                        "pk.mode=none",
                        "delete.enabled=false",
                        "quote.sql.identifiers=always",
                        "db.timezone=UTC"),
                PAIR.target().sinkSettings().properties().stream().map(ConnectorProperty::rendered).toList(),
                "ADR-0011 §Sink contract and TP §6.5 UTC session semantics: the Sink property list, in order");
    }

    @Test
    void booleanSettingsAreTypedFlags() {
        List<ConnectorProperty> properties = PAIR.target().sinkSettings().properties();
        assertEquals(new ConnectorProperty.Flag("auto.create", false), properties.get(0),
                "obligation 2: a boolean Sink setting is a typed Flag, rendered like ConnectionSemantics");
    }

    @Test
    void thereIsOneSinkSettingsValue() {
        assertSame(PAIR.target().sinkSettings(), PAIR.target().sinkSettings(),
                "obligation 29: target.sinkSettings returns the single instance");
    }

    @Test
    void noOtherSinkSettingsCanBeConstructedOutsideTheModule() {
        Constructor<?>[] constructors = SinkSettings.class.getDeclaredConstructors();
        assertTrue(Arrays.stream(constructors).allMatch(c -> Modifier.isPrivate(c.getModifiers())),
                "obligation 29: SinkSettings has no constructor callable from outside: " + Arrays.toString(constructors));
        assertTrue(Modifier.isFinal(SinkSettings.class.getModifiers()) && !SinkSettings.class.isRecord(),
                "obligation 29: SinkSettings cannot be subclassed and has no public canonical record constructor");
        assertFalse(Arrays.stream(SinkSettings.class.getMethods())
                        .anyMatch(m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == SinkSettings.class),
                "obligation 29: no static factory hands out another SinkSettings value");
    }
}
