package com.dbx.dialect.api;

import static com.dbx.dialect.api.MappingCase.PAIR;
import static com.dbx.dialect.api.MappingCase.supported;
import static com.dbx.dialect.api.SourceColumns.column;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbx.dialect.api.ConnectRepresentation.LogicalType;
import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * TP §6.2 through {@code pair.map}: the numeric rows, and the three rulings that get "simplified"
 * most often. The full matrix is pinned by golden set 1; these tests name the rulings.
 */
class NumericMappingContractTest {

    private static final MappingOptions OFF = MappingOptions.DEFAULTS;
    private static final MappingOptions BOOLEAN_ON = new MappingOptions(true, false);

    @Test
    void bigintUnsignedIsNumeric20WithARequiredExactMaxPreflight() {
        Supported decision = supported(column("bigint", "bigint unsigned").precision(20, 0), OFF);

        assertEquals(new TargetType(TargetTypeName.NUMERIC, List.of(20, 0)), decision.targetType(),
                "TP §6.2: BIGINT UNSIGNED → numeric(20,0)");
        assertEquals(SchemaType.INT64, decision.connectRepresentation().schemaType(),
                "TP §6.2: the Source reads BIGINT UNSIGNED as INT64");
        assertTrue(decision.requiredPreflights().contains(RequiredPreflight.UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE),
                "TP §6.2/§6.6 check 3: BIGINT UNSIGNED requires the exact MAX <= 2^63-1 preflight; the Source cannot "
                        + "read larger values even though numeric(20,0) could store them, so without it the operator "
                        + "is told an unsafe table is safe");
    }

    @Test
    void tinyintOneStaysSmallintWithTheBooleanSwitchOff() {
        for (SourceColumns tinyintOne : List.of(
                column("tinyint", "tinyint(1)").precision(3, 0),
                column("tinyint", "tinyint(1)").precision(3, 0).notNull().columnDefault("0"))) {
            Supported decision = supported(tinyintOne, OFF);
            assertEquals(new TargetType(TargetTypeName.SMALLINT, List.of()), decision.targetType(),
                    "TP §6.2: TINYINT(1)/BOOL/BOOLEAN → smallint while the task switch is off");
            assertEquals(SchemaType.INT8, decision.connectRepresentation().schemaType(), "TP §6.2: INT8");
            assertEquals(List.of(), decision.requiredPreflights(), "TP §6.2: smallint holds every TINYINT value");
        }
    }

    @Test
    void tinyintOneBecomesBooleanOnlyUnderTheSwitchAndThenRequiresTheZeroOnePreflight() {
        Supported decision = supported(column("tinyint", "tinyint(1)").precision(3, 0), BOOLEAN_ON);

        assertEquals(new TargetType(TargetTypeName.BOOLEAN, List.of()), decision.targetType(),
                "TP §6.2: with the task switch on, TINYINT(1) → boolean");
        assertEquals(new ConnectRepresentation(SchemaType.BOOLEAN, Optional.empty()), decision.connectRepresentation(),
                "TP §6.2: BOOLEAN on the wire");
        assertEquals(ExtractionIntent.TINYINT_ONE_AS_BOOLEAN, decision.extractionIntent(),
                "TP §6.5: tinyInt1isBit follows the task Boolean switch");
        assertEquals(List.of(RequiredPreflight.BOOLEAN_VALUES_ZERO_OR_ONE), decision.requiredPreflights(),
                "TP §6.2/§6.6 check 2: a column holding 2 must never be silently reinterpreted, so every value "
                        + "must preflight into {0,1}");
    }

    @Test
    void theBooleanSwitchTouchesNoOtherTinyint() {
        for (SourceColumns notBoolean : List.of(
                column("tinyint", "tinyint").precision(3, 0),
                column("tinyint", "tinyint(4)").precision(3, 0),
                column("tinyint", "tinyint unsigned").precision(3, 0),
                column("tinyint", "tinyint(1) unsigned").precision(3, 0))) {
            assertEquals(supported(notBoolean, OFF), supported(notBoolean, BOOLEAN_ON),
                    "TP §6.2: only TINYINT(1) is a Boolean candidate, so the switch must not change "
                            + notBoolean.build().columnType());
        }
    }

    @Test
    void theSignedAndUnsignedWideningLadderIsRowForRow() {
        record Row(String dataType, String columnType, SchemaType schema, TargetTypeName target) {
        }
        List<Row> ladder = List.of(
                new Row("tinyint", "tinyint", SchemaType.INT8, TargetTypeName.SMALLINT),
                new Row("tinyint", "tinyint unsigned", SchemaType.INT16, TargetTypeName.SMALLINT),
                new Row("smallint", "smallint", SchemaType.INT16, TargetTypeName.SMALLINT),
                new Row("smallint", "smallint unsigned", SchemaType.INT32, TargetTypeName.INTEGER),
                new Row("mediumint", "mediumint", SchemaType.INT32, TargetTypeName.INTEGER),
                new Row("mediumint", "mediumint unsigned", SchemaType.INT64, TargetTypeName.BIGINT),
                new Row("int", "int", SchemaType.INT32, TargetTypeName.INTEGER),
                new Row("int", "int unsigned", SchemaType.INT64, TargetTypeName.BIGINT),
                new Row("bigint", "bigint", SchemaType.INT64, TargetTypeName.BIGINT),
                new Row("float", "float", SchemaType.FLOAT32, TargetTypeName.REAL),
                new Row("float", "float unsigned", SchemaType.FLOAT32, TargetTypeName.REAL),
                new Row("double", "double", SchemaType.FLOAT64, TargetTypeName.DOUBLE_PRECISION),
                new Row("double", "double unsigned", SchemaType.FLOAT64, TargetTypeName.DOUBLE_PRECISION));
        for (Row row : ladder) {
            Supported decision = supported(column(row.dataType(), row.columnType()), OFF);
            String label = "TP §6.2: " + row.columnType() + " → " + row.schema() + " / " + row.target();
            assertEquals(new ConnectRepresentation(row.schema(), Optional.empty()), decision.connectRepresentation(), label);
            assertEquals(new TargetType(row.target(), List.of()), decision.targetType(), label);
            assertEquals(List.of(), decision.requiredPreflights(), label + " needs no preflight");
        }
    }

    @Test
    void decimalPreservesPrecisionAndScaleAndDefaultsTo10And0() {
        assertEquals(new TargetType(TargetTypeName.NUMERIC, List.of(65, 30)),
                supported(column("decimal", "decimal(65,30)").precision(65, 30), OFF).targetType(),
                "TP §6.2: DECIMAL(p,s) preserves p and s exactly");
        assertEquals(new TargetType(TargetTypeName.NUMERIC, List.of(10, 0)),
                supported(column("decimal", "decimal"), OFF).targetType(),
                "TP §6.2: DECIMAL without explicit parameters → numeric(10,0), MySQL's default");
        assertEquals(new ConnectRepresentation(SchemaType.BYTES, Optional.of(LogicalType.DECIMAL)),
                supported(column("decimal", "decimal(5,2)").precision(5, 2), OFF).connectRepresentation(),
                "TP §6.2: Decimal logical type");
    }

    @Test
    void bitBelow8IsSmallintAndBit8OrWiderIsUnsupported() {
        for (int n = 1; n <= 7; n++) {
            assertEquals(new TargetType(TargetTypeName.SMALLINT, List.of()),
                    supported(column("bit", "bit(" + n + ")").precision(n), OFF).targetType(),
                    "TP §6.2: BIT(" + n + ") → smallint");
        }
        for (int n : new int[] {8, 9, 63, 64}) {
            SourceColumn wide = column("bit", "bit(" + n + ")").precision(n).build();
            Unsupported refused = assertInstanceOf(Unsupported.class, PAIR.map(wide, OFF),
                    "TP §6.2: BIT(" + n + ") can truncate or overflow before the Sink, so it is Unsupported");
            assertSame(MappingUnsupportedReason.BIT_WIDTH_AT_LEAST_8, refused.reason(),
                    "the error-translation layer keys on the stable code, not on prose");
            assertEquals(wide, refused.evidence(), "the refusal carries the facts it was decided from");
        }
    }

    @Test
    void contradictoryFactsAreRefusedNotGuessed() {
        for (SourceColumns contradictory : List.of(
                column("int", "int").unsignedFlag(true),
                column("bigint", "bigint unsigned").unsignedFlag(false),
                column("decimal", "decimal(5,2)").precision(5),
                column("decimal", "decimal(5,6)").precision(5, 6),
                column("bit", "bit"))) {
            Unsupported refused = assertInstanceOf(Unsupported.class, PAIR.map(contradictory.build(), OFF),
                    "TP §6.1: the decision is a function of the facts; contradictory facts are refused");
            assertSame(MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT, refused.reason());
        }
    }
}
