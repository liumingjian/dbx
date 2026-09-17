package com.dbx.dialect.pair;

import com.dbx.dialect.api.ConnectRepresentation;
import com.dbx.dialect.api.ConnectRepresentation.LogicalType;
import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import com.dbx.dialect.api.ContractEffect;
import com.dbx.dialect.api.ExtractionIntent;
import com.dbx.dialect.api.JdbcBinder;
import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingNotice;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingUnsupportedReason;
import com.dbx.dialect.api.RequiredPreflight;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Supported;
import com.dbx.dialect.api.TargetType;
import com.dbx.dialect.api.TargetTypeName;
import com.dbx.dialect.api.Unsupported;
import com.dbx.dialect.api.ValueSemantics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TP §6.4: the temporal rows, where v1's one deliberate precision loss lives. Connect logical time is
 * milliseconds, so the target records {@code min(n,3)}, never a misleading {@code 6}, and the lost
 * fraction is a stated notice. Zero dates become {@code NULL} only under the operator's switch; with
 * it off they are an exact preflight obligation (TP §6.6 check 6), never a silent conversion.
 */
final class TemporalMapping {

    /** MySQL's fractional-seconds precision is 0..6. */
    private static final int MAX_SOURCE_FRACTION = 6;

    /** Connect logical Time and Timestamp carry milliseconds (TP §6.4). */
    private static final int CONNECT_FRACTION = 3;

    private static final Pattern FRACTIONAL_TYPE = Pattern.compile("^(datetime|timestamp|time)(?:\\((\\d)\\))?$");
    private static final Pattern YEAR_TYPE = Pattern.compile("^year(?:\\(4\\))?$");

    private TemporalMapping() {
    }

    static MappingDecision map(MySqlDataType type, SourceColumn column, MappingOptions options) {
        if (!onlyTemporalFacts(column)) {
            return inconsistent(column);
        }
        String columnType = column.columnType().toLowerCase(Locale.ROOT);
        if (type == MySqlDataType.DATE || type == MySqlDataType.YEAR) {
            boolean declared = type == MySqlDataType.DATE ? columnType.equals("date")
                    : YEAR_TYPE.matcher(columnType).matches();
            if (!declared || !noFraction(column)) {
                return inconsistent(column);
            }
            return type == MySqlDataType.DATE ? date(options) : year();
        }
        OptionalInt fraction = fraction(type, column, columnType);
        if (fraction.isEmpty()) {
            return inconsistent(column);
        }
        return fractional(type, fraction.getAsInt(), options);
    }

    /** {@code DATE} → {@code date}; the zero-date policy applies. */
    private static Supported date(MappingOptions options) {
        Decision date = new Decision(new TargetType(TargetTypeName.DATE, List.of()), LogicalType.DATE,
                JdbcBinder.DATE, ValueSemantics.CALENDAR_DATE);
        date.zeroDatePolicy(options);
        return date.build();
    }

    /** {@code YEAR} → {@code date} with the value {@code YYYY-01-01}, the proven Connect representation. */
    private static Supported year() {
        Decision year = new Decision(new TargetType(TargetTypeName.DATE, List.of()), LogicalType.DATE,
                JdbcBinder.DATE, ValueSemantics.YEAR_AS_FIRST_OF_JANUARY);
        year.intent = ExtractionIntent.YEAR_AS_DATE;
        return year.build();
    }

    /**
     * {@code DATETIME(n)}, {@code TIMESTAMP(n)} and {@code TIME(n)} keep {@code min(n,3)}. The first two
     * also retain their original type: Connect cannot tell them apart once converted.
     */
    private static Supported fractional(MySqlDataType type, int sourceFraction, MappingOptions options) {
        List<Integer> kept = List.of(Math.min(sourceFraction, CONNECT_FRACTION));
        Decision decision = switch (type) {
            case DATETIME -> new Decision(new TargetType(TargetTypeName.TIMESTAMP, kept), LogicalType.TIMESTAMP,
                    JdbcBinder.TIMESTAMP, ValueSemantics.WALL_CLOCK_MILLISECONDS);
            case TIMESTAMP -> new Decision(new TargetType(TargetTypeName.TIMESTAMPTZ, kept), LogicalType.TIMESTAMP,
                    JdbcBinder.TIMESTAMP, ValueSemantics.UTC_INSTANT_MILLISECONDS);
            case TIME -> new Decision(new TargetType(TargetTypeName.TIME, kept), LogicalType.TIME,
                    JdbcBinder.TIME, ValueSemantics.TIME_OF_DAY_MILLISECONDS);
            default -> throw new IllegalArgumentException("TypeMapper routed a non-temporal type here: " + type);
        };
        if (sourceFraction > CONNECT_FRACTION) {
            decision.notices.add(MappingNotice.MICROSECONDS_TRUNCATED_TO_MILLISECONDS);
        }
        switch (type) {
            case DATETIME -> decision.effects.add(ContractEffect.ORIGINAL_SOURCE_TYPE_DATETIME);
            case TIMESTAMP -> decision.effects.add(ContractEffect.ORIGINAL_SOURCE_TYPE_TIMESTAMP);
            default -> decision.preflights.add(RequiredPreflight.TIME_WITHIN_DAY);
        }
        if (type != MySqlDataType.TIME) {
            decision.zeroDatePolicy(options);
        }
        return decision.build();
    }

    /** The fields every temporal row sets, plus the lists a row appends to before building. */
    private static final class Decision {
        private final TargetType target;
        private final LogicalType logicalType;
        private final JdbcBinder binder;
        private final ValueSemantics semantics;
        private ExtractionIntent intent = ExtractionIntent.AS_DECLARED;
        private final List<RequiredPreflight> preflights = new ArrayList<>();
        private final List<ContractEffect> effects = new ArrayList<>();
        private final List<MappingNotice> notices = new ArrayList<>();

        Decision(TargetType target, LogicalType logicalType, JdbcBinder binder, ValueSemantics semantics) {
            this.target = target;
            this.logicalType = logicalType;
            this.binder = binder;
            this.semantics = semantics;
        }

        /**
         * The switch is the operator's decision to lose a value, so the decision records it. Off, a zero
         * date must be proven absent and the Source rejects one ({@code zeroDateTimeBehavior=EXCEPTION}).
         */
        void zeroDatePolicy(MappingOptions options) {
            if (options.zeroDateAsNull()) {
                intent = ExtractionIntent.ZERO_DATE_AS_NULL;
                notices.add(MappingNotice.ZERO_DATE_CONVERTED_TO_NULL);
            } else {
                preflights.add(RequiredPreflight.NO_ZERO_DATE);
            }
        }

        Supported build() {
            SchemaType schema = logicalType == LogicalType.TIMESTAMP ? SchemaType.INT64 : SchemaType.INT32;
            return new Supported(target, intent, new ConnectRepresentation(schema, Optional.of(logicalType)), binder,
                    semantics, preflights, effects, List.of(), notices);
        }
    }

    /**
     * The fractional-seconds precision when {@code datetime_precision} and {@code column_type} agree: a
     * bare {@code datetime} is precision 0 and {@code datetime(n)} is {@code n}.
     */
    private static OptionalInt fraction(MySqlDataType type, SourceColumn column, String columnType) {
        Matcher matcher = FRACTIONAL_TYPE.matcher(columnType);
        OptionalInt reported = column.datetimePrecision();
        if (!matcher.matches() || !matcher.group(1).equals(type.name().toLowerCase(Locale.ROOT))
                || reported.isEmpty()) {
            return OptionalInt.empty();
        }
        int declared = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        int precision = reported.getAsInt();
        if (precision != declared || precision > MAX_SOURCE_FRACTION) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(precision);
    }

    /** {@code DATE} and {@code YEAR} have no fraction; a reported {@code 0} says the same thing. */
    private static boolean noFraction(SourceColumn column) {
        return column.datetimePrecision().isEmpty() || column.datetimePrecision().getAsInt() == 0;
    }

    /** A temporal column carries no sign, numeric precision or character facts. */
    private static boolean onlyTemporalFacts(SourceColumn column) {
        return !column.unsigned()
                && column.numericPrecision().isEmpty()
                && column.numericScale().isEmpty()
                && column.characterMaximumLength().isEmpty()
                && column.characterOctetLength().isEmpty()
                && column.characterSetName().isEmpty()
                && column.collationName().isEmpty();
    }

    private static Unsupported inconsistent(SourceColumn column) {
        return new Unsupported(MappingUnsupportedReason.SOURCE_FACTS_INCONSISTENT, column);
    }
}
