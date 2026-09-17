package com.dbx.dialect.api;

import com.dbx.dialect.pair.MySql80ToPostgres15;
import com.dbx.golden.GoldenSource;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Golden set 1 of ADR-0022: the type-mapping matrix (#11). One file per TP §6 family, so the family
 * tickets regenerate different files; a family with no rows yet renders no file.
 */
public final class TypeMappingMatrixGoldenSource implements GoldenSource {

    /** The one name of this matrix. A second name for the same matrix is the fork the registry prevents. */
    public static final String SET = "type-mapping-matrix";

    /** File name to that family's rows. Add a family by adding its line here. */
    static final Map<String, List<MappingCase>> FAMILIES = Map.of(
            "numeric.txt", NumericMappingCases.CASES,
            "character-binary-special.txt", CharacterBinarySpecialMappingCases.CASES,
            "temporal.txt", TemporalMappingCases.CASES);

    @Override
    public String name() {
        return SET;
    }

    @Override
    public Map<String, String> render() {
        Map<String, String> files = new TreeMap<>();
        FAMILIES.forEach((file, cases) -> {
            if (!cases.isEmpty()) {
                files.put(file, render(cases));
            }
        });
        return files;
    }

    static String render(List<MappingCase> cases) {
        StringBuilder out = new StringBuilder();
        for (MappingCase mappingCase : cases) {
            out.append("## ").append(mappingCase.name()).append('\n');
            out.append(input(mappingCase.column(), mappingCase.options()));
            out.append(decision(MySql80ToPostgres15.INSTANCE.map(mappingCase.column(), mappingCase.options())));
            out.append('\n');
        }
        return out.toString();
    }

    private static String input(SourceColumn c, MappingOptions options) {
        return "input: data_type=" + c.dataType()
                + " column_type=" + c.columnType()
                + " unsigned=" + c.unsigned()
                + " char_max_length=" + text(c.characterMaximumLength())
                + " char_octet_length=" + text(c.characterOctetLength())
                + " numeric_precision=" + text(c.numericPrecision())
                + " numeric_scale=" + text(c.numericScale())
                + " datetime_precision=" + text(c.datetimePrecision())
                + " charset=" + c.characterSetName().orElse("-")
                + " collation=" + c.collationName().orElse("-")
                + " nullability=" + c.nullability()
                + " default=" + c.columnDefault().orElse("-")
                + " extra=" + (c.extra().isEmpty() ? "-" : c.extra())
                + " ordinal=" + c.ordinalPosition() + '\n'
                + "options: tinyintOneAsBoolean=" + options.tinyintOneAsBoolean()
                + " zeroDateAsNull=" + options.zeroDateAsNull() + '\n';
    }

    private static String decision(MappingDecision decision) {
        String body = switch (decision) {
            case Supported s -> "result: SUPPORTED\n"
                    + "target_type: " + type(s.targetType()) + '\n'
                    + "extraction_intent: " + s.extractionIntent() + '\n'
                    + "connect: " + connect(s.connectRepresentation()) + '\n'
                    + "jdbc_binder: " + s.jdbcBinder() + '\n'
                    + "value_semantics: " + s.valueSemantics() + '\n'
                    + "required_preflights: " + s.requiredPreflights() + '\n'
                    + "contract_effects: " + s.contractEffects() + '\n'
                    + "alternatives: " + s.alternatives().stream()
                            .map(a -> type(a.targetType()) + " via " + connect(a.connectRepresentation()) + " "
                                    + a.jdbcBinder() + " " + a.valueSemantics())
                            .collect(Collectors.joining(", ", "[", "]")) + '\n'
                    + "notices: " + s.notices() + '\n';
            case Unsupported u -> "result: UNSUPPORTED\n"
                    + "reason: " + u.reason().name() + '\n';
        };
        return body + "fingerprint: " + decision.mappingFingerprint().sha256Hex() + '\n';
    }

    private static String type(TargetType type) {
        return type.modifiers().isEmpty() ? type.name().name()
                : type.name().name() + type.modifiers().stream().map(String::valueOf)
                        .collect(Collectors.joining(",", "(", ")"));
    }

    private static String connect(ConnectRepresentation connect) {
        return connect.schemaType() + connect.logicalType().map(logical -> "/" + logical).orElse("");
    }

    private static String text(OptionalInt value) {
        return value.isPresent() ? String.valueOf(value.getAsInt()) : "-";
    }

    private static String text(OptionalLong value) {
        return value.isPresent() ? String.valueOf(value.getAsLong()) : "-";
    }
}
