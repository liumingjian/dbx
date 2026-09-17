package com.dbx.dialect.pair;

import static com.dbx.dialect.pair.SourceFacts.inconsistent;

import com.dbx.dialect.api.ConnectRepresentation;
import com.dbx.dialect.api.ConnectRepresentation.LogicalType;
import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import com.dbx.dialect.api.ContractEffect;
import com.dbx.dialect.api.ExtractionIntent;
import com.dbx.dialect.api.JdbcBinder;
import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingNotice;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.Nullability;
import com.dbx.dialect.api.RequiredPreflight;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.Supported;
import com.dbx.dialect.api.TargetType;
import com.dbx.dialect.api.TargetTypeName;
import com.dbx.dialect.api.ValueSemantics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TP §6.4: the temporal rows, where v1's one deliberate precision loss lives. Connect logical time is
 * milliseconds, so the target records {@code min(n,3)}, never a misleading {@code 6}, and the lost
 * fraction is a stated notice. Zero dates become {@code NULL} only under the operator's switch; with
 * it off they are an exact preflight obligation (TP §6.6 check 6), never a silent conversion; with it on
 * they are counted exactly and decide a {@code NOT NULL} column's relaxation (TP §7.3).
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

    static MappingDecision map(TemporalType type, SourceColumn column, MappingOptions options) {
        if (!onlyTemporalFacts(column)) {
            return inconsistent(column);
        }
        String columnType = column.columnType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case DATE -> columnType.equals("date") && noFraction(column) ? date(column, options) : inconsistent(column);
            case YEAR -> YEAR_TYPE.matcher(columnType).matches() && noFraction(column) ? year() : inconsistent(column);
            case DATETIME -> fractional(type, column, columnType, options, sourceFraction -> {
                Decision datetime = new Decision(new TargetType(TargetTypeName.TIMESTAMP, kept(sourceFraction)),
                        LogicalType.TIMESTAMP, JdbcBinder.TIMESTAMP, ValueSemantics.WALL_CLOCK_MILLISECONDS);
                datetime.effects.add(ContractEffect.ORIGINAL_SOURCE_TYPE_DATETIME);
                return datetime;
            });
            case TIMESTAMP -> fractional(type, column, columnType, options, sourceFraction -> {
                Decision timestamp = new Decision(new TargetType(TargetTypeName.TIMESTAMPTZ, kept(sourceFraction)),
                        LogicalType.TIMESTAMP, JdbcBinder.TIMESTAMP, ValueSemantics.UTC_INSTANT_MILLISECONDS);
                timestamp.effects.add(ContractEffect.ORIGINAL_SOURCE_TYPE_TIMESTAMP);
                return timestamp;
            });
            case TIME -> fractional(type, column, columnType, options, sourceFraction -> {
                Decision time = new Decision(new TargetType(TargetTypeName.TIME, kept(sourceFraction)),
                        LogicalType.TIME, JdbcBinder.TIME, ValueSemantics.TIME_OF_DAY_MILLISECONDS);
                time.preflights.add(RequiredPreflight.TIME_WITHIN_DAY);
                return time;
            });
        };
    }

    /** {@code DATE} → {@code date}; the zero-date policy applies. */
    private static Supported date(SourceColumn column, MappingOptions options) {
        Decision date = new Decision(new TargetType(TargetTypeName.DATE, List.of()), LogicalType.DATE,
                JdbcBinder.DATE, ValueSemantics.CALENDAR_DATE);
        date.zeroDatePolicy(column, options);
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
    private static MappingDecision fractional(TemporalType type, SourceColumn column, String columnType,
            MappingOptions options, IntFunction<Decision> row) {
        OptionalInt fraction = fraction(type, column, columnType);
        if (fraction.isEmpty()) {
            return inconsistent(column);
        }
        Decision decision = row.apply(fraction.getAsInt());
        if (fraction.getAsInt() > CONNECT_FRACTION) {
            decision.notices.add(MappingNotice.MICROSECONDS_TRUNCATED_TO_MILLISECONDS);
        }
        if (type != TemporalType.TIME) {
            decision.zeroDatePolicy(column, options);
        }
        return decision.build();
    }

    private static List<Integer> kept(int sourceFraction) {
        return List.of(Math.min(sourceFraction, CONNECT_FRACTION));
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
         * The switch is the operator's decision to lose a value, so the decision records it. On, the zero
         * dates are still counted exactly, and a {@code NOT NULL} column is relaxed only if that count finds
         * one (TP §7.3). Off, a zero date must be proven absent and the Source rejects one
         * ({@code zeroDateTimeBehavior=EXCEPTION}).
         */
        void zeroDatePolicy(SourceColumn column, MappingOptions options) {
            if (options.zeroDateAsNull()) {
                intent = ExtractionIntent.ZERO_DATE_AS_NULL;
                preflights.add(RequiredPreflight.ZERO_DATE_ROWS_COUNTED);
                if (column.nullability() == Nullability.NOT_NULL) {
                    effects.add(ContractEffect.NOT_NULL_RELAXED_IF_ZERO_DATES_OBSERVED);
                }
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
    private static OptionalInt fraction(TemporalType type, SourceColumn column, String columnType) {
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
}
