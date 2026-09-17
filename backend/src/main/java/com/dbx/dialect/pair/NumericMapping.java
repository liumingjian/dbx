package com.dbx.dialect.pair;

import com.dbx.dialect.api.ConnectRepresentation;
import com.dbx.dialect.api.ConnectRepresentation.LogicalType;
import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import com.dbx.dialect.api.ExtractionIntent;
import com.dbx.dialect.api.JdbcBinder;
import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingUnsupportedReason;
import com.dbx.dialect.api.RequiredPreflight;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Supported;
import com.dbx.dialect.api.TargetType;
import com.dbx.dialect.api.TargetTypeName;
import com.dbx.dialect.api.Unsupported;
import com.dbx.dialect.api.ValueSemantics;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TP §6.2: the numeric rows, row for row, including the three rulings most often "simplified". */
final class NumericMapping {

    /** MySQL's own bound on {@code DECIMAL}: precision ≤ 65, scale ≤ 30 and ≤ precision. */
    private static final int DECIMAL_MAX_PRECISION = 65;
    private static final int DECIMAL_MAX_SCALE = 30;
    private static final int BIT_MAX_WIDTH = 64;

    /** {@code DECIMAL} without explicit parameters is {@code DECIMAL(10,0)} in MySQL (TP §6.2). */
    private static final int DECIMAL_DEFAULT_PRECISION = 10;

    private static final Pattern DISPLAY_WIDTH = Pattern.compile("^[a-z]+\\((\\d+)\\)");
    private static final Pattern UNSIGNED_WORD = Pattern.compile("\\bunsigned\\b");

    private NumericMapping() {
    }

    static MappingDecision map(MySqlDataType type, SourceColumn column, MappingOptions options) {
        String columnType = column.columnType().toLowerCase(Locale.ROOT);
        if (UNSIGNED_WORD.matcher(columnType).find() != column.unsigned()) {
            return inconsistent(column);
        }
        boolean unsigned = column.unsigned();
        return switch (type) {
            case TINYINT -> tinyint(column, columnType, options);
            case SMALLINT -> unsigned ? integral(SchemaType.INT32, TargetTypeName.INTEGER, JdbcBinder.INT)
                    : integral(SchemaType.INT16, TargetTypeName.SMALLINT, JdbcBinder.SHORT);
            case MEDIUMINT -> unsigned ? integral(SchemaType.INT64, TargetTypeName.BIGINT, JdbcBinder.LONG)
                    : integral(SchemaType.INT32, TargetTypeName.INTEGER, JdbcBinder.INT);
            case INT -> unsigned ? integral(SchemaType.INT64, TargetTypeName.BIGINT, JdbcBinder.LONG)
                    : integral(SchemaType.INT32, TargetTypeName.INTEGER, JdbcBinder.INT);
            case BIGINT -> unsigned ? bigintUnsigned()
                    : integral(SchemaType.INT64, TargetTypeName.BIGINT, JdbcBinder.LONG);
            case FLOAT -> floating(SchemaType.FLOAT32, TargetTypeName.REAL, JdbcBinder.FLOAT);
            case DOUBLE -> floating(SchemaType.FLOAT64, TargetTypeName.DOUBLE_PRECISION, JdbcBinder.DOUBLE);
            case DECIMAL -> decimal(column);
            case BIT -> bit(column, columnType);
            default -> throw new IllegalArgumentException("TypeMapper routed a non-numeric type here: " + type);
        };
    }

    /**
     * {@code TINYINT(1)}/{@code BOOL}/{@code BOOLEAN} (all stored as {@code tinyint(1)}) stay
     * {@code smallint} unless the task switch is on; then {@code boolean} under a required exact
     * {@code {0,1}} preflight, so a column holding {@code 2} is never silently reinterpreted.
     * {@code TINYINT(1) UNSIGNED} is not a Boolean candidate: it is the {@code TINYINT UNSIGNED} row.
     */
    private static MappingDecision tinyint(SourceColumn column, String columnType, MappingOptions options) {
        if (column.unsigned()) {
            return integral(SchemaType.INT16, TargetTypeName.SMALLINT, JdbcBinder.SHORT);
        }
        boolean widthOne = displayWidth(columnType).equals(OptionalInt.of(1));
        if (widthOne && options.tinyintOneAsBoolean()) {
            return new Supported(
                    new TargetType(TargetTypeName.BOOLEAN, List.of()),
                    ExtractionIntent.TINYINT_ONE_AS_BOOLEAN,
                    new ConnectRepresentation(SchemaType.BOOLEAN, Optional.empty()),
                    JdbcBinder.BOOLEAN,
                    ValueSemantics.ZERO_ONE_AS_BOOLEAN,
                    List.of(RequiredPreflight.BOOLEAN_VALUES_ZERO_OR_ONE),
                    List.of(),
                    List.of(),
                    List.of());
        }
        return integral(SchemaType.INT8, TargetTypeName.SMALLINT, JdbcBinder.BYTE);
    }

    /**
     * {@code numeric(20,0)} can store every {@code BIGINT UNSIGNED}, but the Source reads it as INT64
     * and cannot read a value above {@code 2^63-1}. That makes this a restriction, not a widening,
     * and the exact {@code MAX} preflight is what keeps the operator from being told it is safe.
     */
    private static MappingDecision bigintUnsigned() {
        return new Supported(
                new TargetType(TargetTypeName.NUMERIC, List.of(20, 0)),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(SchemaType.INT64, Optional.empty()),
                JdbcBinder.LONG,
                ValueSemantics.EXACT,
                List.of(RequiredPreflight.UNSIGNED_BIGINT_MAX_WITHIN_SIGNED_RANGE),
                List.of(),
                List.of(),
                List.of());
    }

    /** Parameterised {@code DECIMAL(p,s)} keeps {@code p} and {@code s} exactly; none means {@code (10,0)}. */
    private static MappingDecision decimal(SourceColumn column) {
        OptionalInt precision = column.numericPrecision();
        OptionalInt scale = column.numericScale();
        int p;
        int s;
        if (precision.isEmpty() && scale.isEmpty()) {
            p = DECIMAL_DEFAULT_PRECISION;
            s = 0;
        } else if (precision.isPresent() && scale.isPresent()) {
            p = precision.getAsInt();
            s = scale.getAsInt();
        } else {
            return inconsistent(column);
        }
        if (p < 1 || p > DECIMAL_MAX_PRECISION || s < 0 || s > DECIMAL_MAX_SCALE || s > p) {
            return inconsistent(column);
        }
        return new Supported(
                new TargetType(TargetTypeName.NUMERIC, List.of(p, s)),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(SchemaType.BYTES, Optional.of(LogicalType.DECIMAL)),
                JdbcBinder.BIG_DECIMAL,
                ValueSemantics.EXACT,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * {@code BIT(1..7)} → {@code smallint} over INT8, the proven connector representation;
     * {@code BIT(n >= 8)} can truncate or overflow before the Sink ever sees it.
     */
    private static MappingDecision bit(SourceColumn column, String columnType) {
        OptionalInt width = column.numericPrecision().isPresent() ? column.numericPrecision() : displayWidth(columnType);
        if (width.isEmpty() || width.getAsInt() < 1 || width.getAsInt() > BIT_MAX_WIDTH || column.unsigned()) {
            return inconsistent(column);
        }
        if (width.getAsInt() >= 8) {
            return new Unsupported(MappingUnsupportedReason.BIT_WIDTH_AT_LEAST_8, column);
        }
        return integral(SchemaType.INT8, TargetTypeName.SMALLINT, JdbcBinder.BYTE);
    }

    private static Supported integral(SchemaType schema, TargetTypeName target, JdbcBinder binder) {
        return plain(schema, target, binder, ValueSemantics.EXACT);
    }

    private static Supported floating(SchemaType schema, TargetTypeName target, JdbcBinder binder) {
        return plain(schema, target, binder, ValueSemantics.IEEE_FLOATING_POINT);
    }

    private static Supported plain(SchemaType schema, TargetTypeName target, JdbcBinder binder, ValueSemantics semantics) {
        return new Supported(
                new TargetType(target, List.of()),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(schema, Optional.empty()),
                binder,
                semantics,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static OptionalInt displayWidth(String columnType) {
        Matcher matcher = DISPLAY_WIDTH.matcher(columnType);
        if (!matcher.find() || matcher.group(1).length() > 9) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    }

    private static Unsupported inconsistent(SourceColumn column) {
        return new Unsupported(MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT, column);
    }
}
