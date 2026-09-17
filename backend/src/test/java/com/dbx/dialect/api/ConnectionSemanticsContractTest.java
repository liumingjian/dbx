package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.ALL_OPTIONS;
import static com.dbx.dialect.api.MappingCase.BOOLEAN_ON;
import static com.dbx.dialect.api.MappingCase.PAIR;
import static com.dbx.dialect.api.MappingCase.SWITCHES_OFF;
import static com.dbx.dialect.api.MappingCase.ZERO_DATE_ON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** {@code source.connectionSemantics}: obligation 21, TP §6.5, ADR-0033. */
class ConnectionSemanticsContractTest {

    private static List<String> rendered(MappingOptions options) {
        return PAIR.source().connectionSemantics(options).properties().stream()
                .map(ConnectorProperty::rendered).toList();
    }

    private static List<String> expected(String bit, String zeroDate) {
        return List.of(
                "tinyInt1isBit=" + bit,
                "transformedBitIsBoolean=" + bit,
                "jdbcCompliantTruncation=true",
                "zeroDateTimeBehavior=" + zeroDate,
                "connectionTimeZone=UTC",
                "forceConnectionTimeZoneToSession=true",
                "preserveInstants=true",
                "useUnicode=true",
                "characterEncoding=UTF-8",
                "blobsAreStrings=false",
                "functionsNeverReturnBlobs=false",
                "yearIsDateType=true",
                "useCursorFetch=true");
    }

    @Test
    void bothSwitchesOff() {
        assertEquals(expected("false", "EXCEPTION"), rendered(SWITCHES_OFF),
                "TP §6.5: the Connector/J property list with both task switches off, in order");
    }

    @Test
    void booleanSwitchOn() {
        assertEquals(expected("true", "EXCEPTION"), rendered(BOOLEAN_ON),
                "TP §6.5: tinyInt1isBit and transformedBitIsBoolean follow the Boolean switch");
    }

    @Test
    void zeroDateSwitchOn() {
        assertEquals(expected("false", "CONVERT_TO_NULL"), rendered(ZERO_DATE_ON),
                "TP §6.5: zeroDateTimeBehavior=CONVERT_TO_NULL only under the approved zero-date switch");
    }

    @Test
    void bothSwitchesOn() {
        assertEquals(expected("true", "CONVERT_TO_NULL"), rendered(new MappingOptions(true, true)),
                "TP §6.5: the Connector/J property list with both task switches on, in order");
    }

    @TestFactory
    Stream<DynamicTest> everyCombinationUsesCursorFetchAndNoDefaultFetchSize() {
        return ALL_OPTIONS.stream().map(options -> dynamicTest(options.toString(), () -> {
            List<ConnectorProperty> properties = PAIR.source().connectionSemantics(options).properties();
            assertEquals(List.of(new ConnectorProperty.Flag("useCursorFetch", true)),
                    properties.stream().filter(p -> p.name().equals("useCursorFetch")).toList(),
                    "ADR-0033: every Source connection sets useCursorFetch=true exactly once, whatever the switches");
            assertTrue(properties.stream().noneMatch(p -> p.name().equalsIgnoreCase("defaultFetchSize")),
                    "ADR-0033: DBX never sets defaultFetchSize; the connector's setFetchSize(batch.max.rows) "
                            + "wins: " + properties);
        }));
    }

    @Test
    void defaultFetchSizeHasNoRepresentation() {
        Stream<String> names = Stream.of(
                Arrays.stream(ConnectionSemantics.class.getRecordComponents()).map(RecordComponent::getName),
                Arrays.stream(ConnectionSemantics.class.getDeclaredFields()).map(Field::getName),
                Arrays.stream(ConnectionSemantics.class.getDeclaredMethods()).map(Method::getName))
                .flatMap(s -> s);
        assertTrue(names.noneMatch(name -> name.toLowerCase(Locale.ROOT).replace("_", "").contains("fetchsize")),
                "ADR-0033: ConnectionSemantics has no defaultFetchSize to set");
    }

    @Test
    void onlyTheTwoSwitchesAreConstructorParameters() {
        assertEquals(List.of("tinyintOneAsBoolean", "zeroDateTimeBehavior"),
                Arrays.stream(ConnectionSemantics.class.getRecordComponents()).map(RecordComponent::getName).toList(),
                "TP §6.5: only the two MappingOptions switches vary; fixed values are constants of the type");
    }

    @Test
    void theRenderingIsAnOrderedListOfTypedRecords() throws NoSuchMethodException {
        Method properties = ConnectionSemantics.class.getMethod("properties");
        assertEquals(List.class, properties.getReturnType(),
                "obligation 2: the property rendering is an ordered list, not a Map<String, Object>");
        assertEquals(ConnectorProperty.class,
                ((ParameterizedType) properties.getGenericReturnType()).getActualTypeArguments()[0],
                "obligation 2: each property is a typed record");
        assertTrue(ConnectorProperty.class.isSealed()
                        && Arrays.stream(ConnectorProperty.class.getPermittedSubclasses()).allMatch(Class::isRecord),
                "obligation 2: a property is one of a closed set of records");
        List<ConnectorProperty> rendered = PAIR.source().connectionSemantics(BOOLEAN_ON).properties();
        assertEquals(new ConnectorProperty.Flag("tinyInt1isBit", true), rendered.get(0),
                "TP §6.5: a boolean setting is a Flag, not text");
        assertEquals(new ConnectorProperty.Text("connectionTimeZone", "UTC"), rendered.get(4),
                "TP §6.5: a token setting is Text");
    }

    @Test
    void theSemanticsAreAValueOfTheSwitches() {
        assertEquals(PAIR.source().connectionSemantics(new MappingOptions(true, false)),
                PAIR.source().connectionSemantics(BOOLEAN_ON),
                "TP §6.5: equal switches give equal fingerprinted semantics");
        assertTrue(ALL_OPTIONS.stream().map(o -> PAIR.source().connectionSemantics(o)).distinct().count() == 4,
                "TP §6.5: each switch combination gives distinct semantics");
    }
}
