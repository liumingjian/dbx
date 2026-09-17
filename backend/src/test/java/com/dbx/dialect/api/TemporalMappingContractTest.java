package com.dbx.dialect.api;

import static com.dbx.dialect.api.SourceColumns.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.dialect.api.ConnectRepresentation.LogicalType;
import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * TP §6.4 through {@code pair.map}: the temporal rows, v1's one deliberate precision loss, and the
 * zero-date switch. The full matrix is pinned by golden set 1; these tests name the rulings.
 */
class TemporalMappingContractTest {

    private static final DatabasePair PAIR = (DatabasePair) DialectCatalog.compileTime()
            .select(new ProductVersion("MySQL", "8.0.36"), new ProductVersion("PostgreSQL", "15.4"));
    private static final MappingOptions OFF = MappingOptions.DEFAULTS;
    private static final MappingOptions ZERO_DATE_ON = new MappingOptions(false, true);

    @Test
    void everyTemporalRowMapsAsTp64States() {
        record Row(SourceColumns column, TargetType target, ConnectRepresentation connect, JdbcBinder binder,
                ValueSemantics semantics) {
        }
        ConnectRepresentation date = new ConnectRepresentation(SchemaType.INT32, Optional.of(LogicalType.DATE));
        ConnectRepresentation timestamp = new ConnectRepresentation(SchemaType.INT64, Optional.of(LogicalType.TIMESTAMP));
        ConnectRepresentation time = new ConnectRepresentation(SchemaType.INT32, Optional.of(LogicalType.TIME));
        List<Row> rows = new ArrayList<>(List.of(
                new Row(column("date", "date"), new TargetType(TargetTypeName.DATE, List.of()), date,
                        JdbcBinder.DATE, ValueSemantics.CALENDAR_DATE),
                new Row(column("year", "year"), new TargetType(TargetTypeName.DATE, List.of()), date,
                        JdbcBinder.DATE, ValueSemantics.YEAR_AS_FIRST_OF_JANUARY)));
        for (int n = 0; n <= 6; n++) {
            int kept = Math.min(n, 3);
            rows.add(new Row(fractional("datetime", n), new TargetType(TargetTypeName.TIMESTAMP, List.of(kept)),
                    timestamp, JdbcBinder.TIMESTAMP, ValueSemantics.WALL_CLOCK_MILLISECONDS));
            rows.add(new Row(fractional("timestamp", n), new TargetType(TargetTypeName.TIMESTAMPTZ, List.of(kept)),
                    timestamp, JdbcBinder.TIMESTAMP, ValueSemantics.UTC_INSTANT_MILLISECONDS));
            rows.add(new Row(fractional("time", n), new TargetType(TargetTypeName.TIME, List.of(kept)),
                    time, JdbcBinder.TIME, ValueSemantics.TIME_OF_DAY_MILLISECONDS));
        }
        for (Row row : rows) {
            Supported decision = supported(row.column(), OFF);
            String label = "TP §6.4: " + row.column().build().columnType();
            assertEquals(row.target(), decision.targetType(), label + " target type is min(n,3)");
            assertEquals(row.connect(), decision.connectRepresentation(), label + " Connect representation");
            assertEquals(row.binder(), decision.jdbcBinder(), label + " JDBC binder");
            assertEquals(row.semantics(), decision.valueSemantics(), label + " value semantics");
            assertEquals(List.of(), decision.alternatives(), label + " offers no alternative");
        }
        assertEquals(ExtractionIntent.YEAR_AS_DATE, supported(column("year", "year"), OFF).extractionIntent(),
                "TP §6.4/§6.5: YEAR is read as the date YYYY-01-01 (yearIsDateType=true)");
    }

    @Test
    void datetime6RecordsPrecision3AndNoSixAnywhereInTheDecision() {
        Pattern six = Pattern.compile("\\b6\\b");
        for (String type : List.of("datetime", "timestamp", "time")) {
            for (MappingOptions options : List.of(OFF, ZERO_DATE_ON)) {
                Supported decision = supported(fractional(type, 6), options);
                assertEquals(List.of(3), decision.targetType().modifiers(),
                        "TP §6.4: DDL records precision 3, not a misleading 6, for " + type + "(6)");
                assertFalse(six.matcher(decision.toString()).find(),
                        "TP §6.4: the operator approves the precision they will actually get, so a " + type
                                + "(6) decision must not carry a 6 anywhere, got " + decision);
            }
        }
    }

    @Test
    void microsecondLossIsAStatedNoticeAndMillisecondsAreNot() {
        for (String type : List.of("datetime", "timestamp", "time")) {
            for (int n = 0; n <= 6; n++) {
                boolean lost = n > 3;
                assertEquals(lost, supported(fractional(type, n), OFF).notices()
                                .contains(MappingNotice.MICROSECONDS_TRUNCATED_TO_MILLISECONDS),
                        "TP §6.4: microseconds are an explicit v1 loss boundary, stated on the decision exactly "
                                + "when " + type + "(" + n + ") has digits beyond milliseconds");
            }
        }
    }

    @Test
    void theOriginalMySqlTypeKeepsDatetimeAndTimestampDistinguishable() {
        for (int n = 0; n <= 6; n++) {
            Supported datetime = supported(fractional("datetime", n), OFF);
            Supported timestamp = supported(fractional("timestamp", n), OFF);
            assertEquals(datetime.connectRepresentation(), timestamp.connectRepresentation(),
                    "TP §6.4: both travel as the same Connect Timestamp, which is why the type must be retained");
            assertEquals(List.of(ContractEffect.ORIGINAL_SOURCE_TYPE_DATETIME), datetime.contractEffects(),
                    "TP §6.4: a DATETIME decision retains its original MySQL type");
            assertEquals(List.of(ContractEffect.ORIGINAL_SOURCE_TYPE_TIMESTAMP), timestamp.contractEffects(),
                    "TP §6.4: a TIMESTAMP decision retains its original MySQL type");
            assertNotEquals(datetime.mappingFingerprint(), timestamp.mappingFingerprint(),
                    "a frozen decision still tells DATETIME from TIMESTAMP");
        }
    }

    @Test
    void timeRequiresTheWithinDayPreflight() {
        for (int n = 0; n <= 6; n++) {
            for (MappingOptions options : List.of(OFF, ZERO_DATE_ON)) {
                assertEquals(List.of(RequiredPreflight.TIME_WITHIN_DAY),
                        supported(fractional("time", n), options).requiredPreflights(),
                        "TP §6.4/§6.6 check 4: exact TIME values must lie in [00:00:00, 24:00:00); MySQL TIME "
                                + "reaches 838:59:59 and is negative-capable");
            }
        }
    }

    @Test
    void theZeroDateSwitchIsOffByDefault() {
        assertFalse(MappingOptions.DEFAULTS.zeroDateAsNull(),
                "TP §6.1: converting a zero date to NULL loses a value, so it is the operator's decision, never "
                        + "a default");
        for (SourceColumns zeroDateCapable : zeroDateCapable()) {
            Supported decision = supported(zeroDateCapable, MappingOptions.DEFAULTS);
            assertTrue(decision.requiredPreflights().contains(RequiredPreflight.NO_ZERO_DATE),
                    "TP §6.6 check 6: by default " + zeroDateCapable.build().columnType()
                            + " must prove it holds no zero date");
            assertEquals(ExtractionIntent.AS_DECLARED, decision.extractionIntent(),
                    "TP §6.5: by default the Source rejects a zero date instead of converting it");
            assertFalse(decision.notices().contains(MappingNotice.ZERO_DATE_CONVERTED_TO_NULL),
                    "nothing is lost while the switch is off");
        }
    }

    @Test
    void withTheSwitchOnZeroDatesBecomeNullAndTheDecisionRecordsTheLoss() {
        for (SourceColumns zeroDateCapable : zeroDateCapable()) {
            Supported decision = supported(zeroDateCapable, ZERO_DATE_ON);
            String label = zeroDateCapable.build().columnType();
            assertEquals(ExtractionIntent.ZERO_DATE_AS_NULL, decision.extractionIntent(),
                    "TP §6.5: under the approved switch " + label + " reads a zero date as NULL");
            assertTrue(decision.notices().contains(MappingNotice.ZERO_DATE_CONVERTED_TO_NULL),
                    "TP §6.1: the switch is a decision to lose a value, so " + label + " records it");
            assertFalse(decision.requiredPreflights().contains(RequiredPreflight.NO_ZERO_DATE),
                    "TP §6.6 check 6: an approved conversion no longer demands the absence of zero dates");
        }
    }

    @Test
    void theZeroDateSwitchTouchesNeitherTimeNorYear() {
        for (SourceColumns untouched : List.of(fractional("time", 0), fractional("time", 6), column("year", "year"))) {
            assertEquals(supported(untouched, OFF), supported(untouched, ZERO_DATE_ON),
                    "TP §6.4: the zero-date policy applies to dates, not to " + untouched.build().columnType());
        }
    }

    @Test
    void contradictoryFactsAreRefusedNotGuessed() {
        for (SourceColumns contradictory : List.of(
                column("datetime", "datetime(6)").datetimePrecision(3),
                column("datetime", "datetime"),
                column("timestamp", "timestamp(7)").datetimePrecision(7),
                column("time", "datetime(3)").datetimePrecision(3),
                column("time", "time unsigned").datetimePrecision(0),
                column("date", "date").datetimePrecision(6),
                column("year", "year(2)"),
                column("date", "date").charset("utf8mb4", "utf8mb4_0900_ai_ci"))) {
            Unsupported refused = assertInstanceOf(Unsupported.class, PAIR.map(contradictory.build(), OFF),
                    "TP §6.1: the decision is a function of the facts; contradictory facts are refused");
            assertSame(MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT, refused.reason());
        }
    }

    private static List<SourceColumns> zeroDateCapable() {
        return List.of(column("date", "date"), fractional("datetime", 0), fractional("datetime", 6),
                fractional("timestamp", 0), fractional("timestamp", 6));
    }

    private static SourceColumns fractional(String type, int precision) {
        return column(type, precision == 0 ? type : type + "(" + precision + ")").datetimePrecision(precision);
    }

    private static Supported supported(SourceColumns column, MappingOptions options) {
        return assertInstanceOf(Supported.class, PAIR.map(column.build(), options),
                "expected a supported decision for " + column.build().columnType());
    }
}
