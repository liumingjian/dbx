package com.dbx.dialect.pair;

import static com.dbx.dialect.pair.SourceFacts.inconsistent;

import com.dbx.dialect.api.ConnectRepresentation;
import com.dbx.dialect.api.ConnectRepresentation.SchemaType;
import com.dbx.dialect.api.ContractEffect;
import com.dbx.dialect.api.ExtractionIntent;
import com.dbx.dialect.api.JdbcBinder;
import com.dbx.dialect.api.MappingAlternative;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TP §6.3: character, binary and special types, row for row. The two rows that are not unremarkable:
 * a character column of the {@code binary} character set is {@code bytea}, decided from the source
 * facts alone, and {@code ENUM} is {@code text} + {@code CHECK} under an exact membership preflight
 * while {@code SET} gets no combinatorial {@code CHECK}. Neither task switch affects this family.
 */
final class CharacterBinarySpecialMapping {

    /** The character set (and its only collation) MySQL uses for byte strings. */
    private static final String BINARY_CHARSET = "binary";

    /** MySQL's bounds on declared lengths: {@code CHAR}/{@code BINARY} ≤ 255, {@code VARCHAR}/{@code VARBINARY} ≤ 65,535. */
    private static final long CHAR_MAX_LENGTH = 255;
    private static final long VARCHAR_MAX_LENGTH = 65_535;

    private static final Pattern LENGTH = Pattern.compile("\\((\\d{1,9})\\)");
    /** One member of {@code enum(...)}/{@code set(...)} as {@code column_type} quotes it: {@code ''} escapes {@code '}. */
    private static final Pattern MEMBER = Pattern.compile("'(?:[^']++|'')*+'");

    private CharacterBinarySpecialMapping() {
    }

    static MappingDecision map(CharacterBinarySpecialType type, SourceColumn column, MappingOptions options) {
        String dataType = type.name().toLowerCase(Locale.ROOT);
        String columnType = column.columnType().toLowerCase(Locale.ROOT);
        boolean plain = !column.unsigned() && column.numericPrecision().isEmpty() && column.numericScale().isEmpty()
                && column.datetimePrecision().isEmpty();
        return switch (type) {
            case GEOMETRY, POINT, LINESTRING, POLYGON, MULTIPOINT, MULTILINESTRING, MULTIPOLYGON, GEOMCOLLECTION ->
                    new Unsupported(MappingUnsupportedReason.GEOMETRY, column);
            case VECTOR -> new Unsupported(MappingUnsupportedReason.VECTOR, column);
            case CHAR -> plain && declaredLength(column, dataType, columnType, CHAR_MAX_LENGTH)
                    ? character(column, TargetTypeName.CHAR, ValueSemantics.TRAILING_SPACE_PADDED)
                    : inconsistent(column);
            case VARCHAR -> plain && declaredLength(column, dataType, columnType, VARCHAR_MAX_LENGTH)
                    ? character(column, TargetTypeName.VARCHAR, ValueSemantics.EXACT_TEXT)
                    : inconsistent(column);
            case TINYTEXT, TEXT, MEDIUMTEXT, LONGTEXT -> plain && columnType.equals(dataType) ? largeText(column)
                    : inconsistent(column);
            case BINARY -> plain && declaredLength(column, dataType, columnType, CHAR_MAX_LENGTH) && byteString(column)
                    ? bytes(List.of()) : inconsistent(column);
            case VARBINARY -> plain && declaredLength(column, dataType, columnType, VARCHAR_MAX_LENGTH)
                    && byteString(column) ? bytes(List.of()) : inconsistent(column);
            case TINYBLOB, BLOB, MEDIUMBLOB, LONGBLOB -> plain && columnType.equals(dataType) && byteString(column)
                    ? bytes(List.of(RequiredPreflight.LARGE_RECORD_ENVELOPE)) : inconsistent(column);
            case ENUM -> plain && members(dataType, columnType) ? enumeration(column) : inconsistent(column);
            case SET -> plain && members(dataType, columnType) ? set(column) : inconsistent(column);
            case JSON -> plain && columnType.equals(dataType) ? json() : inconsistent(column);
        };
    }

    /**
     * {@code CHAR(M)} → {@code char(M)}, {@code VARCHAR(M)} → {@code varchar(M)}, where {@code M} is the
     * character length, so no width preflight is needed. The binary character set is decided first.
     */
    private static MappingDecision character(SourceColumn column, TargetTypeName target, ValueSemantics semantics) {
        Optional<Boolean> binary = binaryCharacterSet(column);
        if (binary.isEmpty()) {
            return inconsistent(column);
        }
        if (binary.get()) {
            return binaryCharacter(List.of(), List.of(), List.of());
        }
        long length = column.characterMaximumLength().getAsLong();
        if (length == 0) {
            return new Unsupported(MappingUnsupportedReason.CHARACTER_LENGTH_ZERO, column);
        }
        return new Supported(
                new TargetType(target, List.of((int) length)),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(SchemaType.STRING, Optional.empty()),
                JdbcBinder.STRING,
                semantics,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /** {@code TINYTEXT}…{@code LONGTEXT} → {@code text} under the 20 MiB value/row preflight. */
    private static MappingDecision largeText(SourceColumn column) {
        List<RequiredPreflight> envelope = List.of(RequiredPreflight.LARGE_RECORD_ENVELOPE);
        return binaryCharacterSet(column)
                .<MappingDecision>map(binary -> binary
                        ? binaryCharacter(envelope, List.of(), List.of())
                        : text(envelope, List.of(), List.of()))
                .orElseGet(() -> inconsistent(column));
    }

    /**
     * {@code ENUM} → {@code text} + {@code CHECK}. MySQL can hold a value outside the declared set (the
     * empty sentinel of an illegal insert), so the membership preflight is required, not advisory. An
     * {@code ENUM} of the binary character set is {@code bytea}, where a text {@code CHECK} cannot apply:
     * like {@code SET}, it states the missing {@code CHECK} and keeps the exact membership preflight.
     */
    private static MappingDecision enumeration(SourceColumn column) {
        List<RequiredPreflight> membership = List.of(RequiredPreflight.ENUM_VALUE_DECLARED);
        return binaryCharacterSet(column)
                .<MappingDecision>map(binary -> binary
                        ? binaryCharacter(membership, List.of(), List.of(MappingNotice.ENUM_WITHOUT_CHECK_CONSTRAINT))
                        : text(membership, List.of(ContractEffect.ENUM_CHECK_CONSTRAINT), List.of()))
                .orElseGet(() -> inconsistent(column));
    }

    /** {@code SET} → {@code text}: a {@code CHECK} enumerating the power set is a denial of service, not a constraint. */
    private static MappingDecision set(SourceColumn column) {
        List<MappingNotice> notice = List.of(MappingNotice.SET_WITHOUT_CHECK_CONSTRAINT);
        return binaryCharacterSet(column)
                .<MappingDecision>map(binary -> binary
                        ? binaryCharacter(List.of(), List.of(), notice)
                        : text(List.of(), List.of(), notice))
                .orElseGet(() -> inconsistent(column));
    }

    /**
     * {@code JSON} → {@code json}, compared as JSON values, with {@code text} as the one alternative:
     * every document {@code json} accepts, {@code text} accepts. {@code jsonb} is deliberately not offered
     * although TP §6.3 allows it: it rejects a string holding U+0000 (JSON escape backslash-u0000), which
     * MySQL JSON and PostgreSQL {@code json} both hold, so it would narrow the default's domain. No byte
     * fidelity is claimed. A JSON value is not bounded by its type the way {@code TINYTEXT} is, so the
     * ADR-0003 envelope applies to it as well.
     */
    private static MappingDecision json() {
        return new Supported(
                new TargetType(TargetTypeName.JSON, List.of()),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(SchemaType.STRING, Optional.empty()),
                JdbcBinder.STRING,
                ValueSemantics.JSON_VALUE,
                List.of(RequiredPreflight.LARGE_RECORD_ENVELOPE),
                List.of(),
                List.of(new MappingAlternative(
                        new TargetType(TargetTypeName.TEXT, List.of()),
                        new ConnectRepresentation(SchemaType.STRING, Optional.empty()),
                        JdbcBinder.STRING,
                        ValueSemantics.JSON_VALUE)),
                List.of(MappingNotice.JSON_BYTE_FIDELITY_NOT_CLAIMED));
    }

    private static Supported text(List<RequiredPreflight> preflights, List<ContractEffect> effects,
            List<MappingNotice> notices) {
        return new Supported(
                new TargetType(TargetTypeName.TEXT, List.of()),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(SchemaType.STRING, Optional.empty()),
                JdbcBinder.STRING,
                ValueSemantics.EXACT_TEXT,
                preflights,
                effects,
                List.of(),
                notices);
    }

    /** A character column of the {@code binary} character set holds bytes, so it travels and lands as bytes. */
    private static Supported binaryCharacter(List<RequiredPreflight> preflights, List<ContractEffect> effects,
            List<MappingNotice> notices) {
        return new Supported(
                new TargetType(TargetTypeName.BYTEA, List.of()),
                ExtractionIntent.BINARY_CHARACTER_AS_BYTES,
                new ConnectRepresentation(SchemaType.BYTES, Optional.empty()),
                JdbcBinder.BYTES,
                ValueSemantics.EXACT_BYTES,
                preflights,
                effects,
                List.of(),
                notices);
    }

    /** {@code BINARY}/{@code VARBINARY} and the {@code BLOB} family → {@code bytea}. */
    private static Supported bytes(List<RequiredPreflight> preflights) {
        return new Supported(
                new TargetType(TargetTypeName.BYTEA, List.of()),
                ExtractionIntent.AS_DECLARED,
                new ConnectRepresentation(SchemaType.BYTES, Optional.empty()),
                JdbcBinder.BYTES,
                ValueSemantics.EXACT_BYTES,
                preflights,
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * Whether this character column holds bytes, read from its own character set and collation only:
     * both {@code binary} is bytes; both something else is text (a {@code *_bin} collation compares by
     * code point but still holds text). A missing fact, or one {@code binary} and the other not, is empty.
     */
    private static Optional<Boolean> binaryCharacterSet(SourceColumn column) {
        if (column.characterSetName().isEmpty() || column.collationName().isEmpty()) {
            return Optional.empty();
        }
        boolean charset = column.characterSetName().get().equalsIgnoreCase(BINARY_CHARSET);
        boolean collation = column.collationName().get().equalsIgnoreCase(BINARY_CHARSET);
        return charset == collation ? Optional.of(charset) : Optional.empty();
    }

    /** A byte-string type reports no character set, or the {@code binary} one on both facts. */
    private static boolean byteString(SourceColumn column) {
        if (column.characterSetName().isEmpty() && column.collationName().isEmpty()) {
            return true;
        }
        return binaryCharacterSet(column).orElse(false);
    }

    /** {@code column_type} is {@code data_type(M)} and {@code M} equals the reported character length. */
    private static boolean declaredLength(SourceColumn column, String dataType, String columnType, long max) {
        OptionalLong reported = column.characterMaximumLength();
        if (!columnType.startsWith(dataType) || reported.isEmpty()) {
            return false;
        }
        Matcher matcher = LENGTH.matcher(columnType.substring(dataType.length()));
        return matcher.matches() && Long.parseLong(matcher.group(1)) == reported.getAsLong()
                && reported.getAsLong() <= max;
    }

    /** {@code column_type} is {@code data_type('a','b',…)} with at least one quoted member. */
    private static boolean members(String dataType, String columnType) {
        if (!columnType.startsWith(dataType + "(") || !columnType.endsWith(")")) {
            return false;
        }
        String list = columnType.substring(dataType.length() + 1, columnType.length() - 1);
        Matcher matcher = MEMBER.matcher(list);
        int at = 0;
        while (at < list.length() && matcher.find(at) && matcher.start() == at) {
            at = matcher.end();
            if (at == list.length()) {
                return true;
            }
            if (list.charAt(at) != ',') {
                return false;
            }
            at++;
        }
        return false;
    }
}
