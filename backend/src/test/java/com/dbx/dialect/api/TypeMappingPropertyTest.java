package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.dbx.dialect.pair.MySql80ToPostgres15;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * TP §15.1 properties of {@code pair.map}, over generated source facts rather than chosen examples:
 * every input gets a closed result, and the same input yields the same decision and fingerprint. The
 * seed is fixed so a failure reproduces; the generator deliberately produces contradictory and
 * out-of-range facts as well as realistic ones.
 */
class TypeMappingPropertyTest {

    private static final DatabasePair PAIR = MySql80ToPostgres15.INSTANCE;
    private static final int SAMPLES = 20_000;
    private static final long SEED = 0x5EED_0115L;

    private static final List<String> DATA_TYPES = List.of(
            "tinyint", "smallint", "mediumint", "int", "bigint", "decimal", "float", "double", "bit",
            "char", "varchar", "binary", "varbinary", "tinytext", "text", "mediumtext", "longtext", "tinyblob", "blob",
            "mediumblob", "longblob", "enum", "set", "json", "geometry", "point", "linestring", "polygon",
            "multipoint", "multilinestring", "multipolygon", "geomcollection", "vector",
            "date", "datetime", "timestamp", "time", "year",
            "BIGINT", "TinyInt", "integer", "bool", "serial", "uuid", "tinyint(1)", "x");
    private static final List<String> SUFFIXES = List.of(
            "", " unsigned", " unsigned zerofill", "(1)", "(1) unsigned", "(4)", "(7)", "(8)", "(64)", "(0)",
            "(10,0)", "(65,30)", "(66,0)", "(5,6)", "(99999999999)", "('a','b')", "(", " UNSIGNED", "(1)unsigned");
    private static final List<Optional<String>> CHARSETS = List.of(
            Optional.empty(), Optional.of("utf8mb4"), Optional.of("binary"), Optional.of("latin1"));
    private static final List<Optional<String>> COLLATIONS = List.of(
            Optional.empty(), Optional.of("utf8mb4_0900_ai_ci"), Optional.of("binary"), Optional.of("utf8mb4_bin"));

    @Test
    void everyGeneratedInputGetsAClosedResult() {
        Random random = new Random(SEED);
        for (int i = 0; i < SAMPLES; i++) {
            SourceColumn column = generate(random);
            MappingOptions options = new MappingOptions(random.nextBoolean(), random.nextBoolean());
            MappingDecision decision;
            try {
                decision = PAIR.map(column, options);
            } catch (RuntimeException thrown) {
                throw new AssertionError("TP §6.1: pair.map returns a closed result and never an exception; "
                        + "it threw for " + column + " under " + options, thrown);
            }
            assertNotNull(decision, "TP §6.1: pair.map never returns null, got null for " + column);
            switch (decision) {
                case Supported supported -> assertNotNull(supported.targetType());
                case Unsupported unsupported -> {
                    assertInstanceOf(MappingUnsupportedReason.class, unsupported.reason(),
                            "a mapping refusal carries a mapping reason code");
                    assertEquals(column, unsupported.evidence(),
                            "ADR-0008 §Contract: a mapping refusal carries the facts it was decided from");
                }
            }
        }
    }

    @Test
    void theSameInputYieldsTheSameDecisionAndTheSameFingerprint() {
        Random random = new Random(SEED);
        Map<MappingFingerprint, MappingDecision> byFingerprint = new HashMap<>();
        for (int i = 0; i < SAMPLES; i++) {
            SourceColumn column = generate(random);
            MappingOptions options = new MappingOptions(random.nextBoolean(), random.nextBoolean());
            SourceColumn equalCopy = copy(column);
            MappingOptions equalOptions = new MappingOptions(options.tinyintOneAsBoolean(), options.zeroDateAsNull());

            MappingDecision first = PAIR.map(column, options);
            MappingDecision second = PAIR.map(equalCopy, equalOptions);

            assertEquals(first, second,
                    "TP §15.1: the decision is a function of the facts and switches only: " + column + " " + options);
            assertEquals(first.mappingFingerprint(), second.mappingFingerprint(),
                    "TP §15.1: the same input yields the same fingerprint: " + column + " " + options);

            MappingDecision seen = byFingerprint.putIfAbsent(first.mappingFingerprint(), first);
            if (seen != null && !seen.equals(first)) {
                fail("two different decisions share a fingerprint:\n" + seen + "\n" + first);
            }
        }
    }

    private static SourceColumn generate(Random random) {
        String dataType = pick(random, DATA_TYPES);
        String columnType = (random.nextInt(4) == 0 ? pick(random, DATA_TYPES) : dataType) + pick(random, SUFFIXES);
        return new SourceColumn(
                new ColumnCoordinate("db" + random.nextInt(3), "t" + random.nextInt(3), "c" + random.nextInt(3)),
                dataType,
                columnType,
                random.nextInt(5) == 0 ? !columnType.contains("unsigned") : columnType.contains("unsigned"),
                random.nextBoolean() ? OptionalLong.empty() : OptionalLong.of(random.nextLong(-1, 5_000_000_000L)),
                random.nextBoolean() ? OptionalLong.empty() : OptionalLong.of(random.nextLong(-1, 5_000_000_000L)),
                random.nextBoolean() ? OptionalInt.empty() : OptionalInt.of(random.nextInt(-2, 80)),
                random.nextBoolean() ? OptionalInt.empty() : OptionalInt.of(random.nextInt(-2, 40)),
                random.nextBoolean() ? OptionalInt.empty() : OptionalInt.of(random.nextInt(-1, 8)),
                pick(random, CHARSETS),
                pick(random, COLLATIONS),
                random.nextBoolean() ? Nullability.NULLABLE : Nullability.NOT_NULL,
                random.nextBoolean() ? Optional.empty() : Optional.of(String.valueOf(random.nextInt(-5, 5))),
                random.nextBoolean() ? "" : "auto_increment",
                1 + random.nextInt(200));
    }

    /** A field-by-field copy, so equality is proven over content rather than identity. */
    private static SourceColumn copy(SourceColumn c) {
        return new SourceColumn(
                new ColumnCoordinate(new String(c.coordinate().database()), new String(c.coordinate().table()),
                        new String(c.coordinate().column())),
                new String(c.dataType()), new String(c.columnType()), c.unsigned(), c.characterMaximumLength(),
                c.characterOctetLength(), c.numericPrecision(), c.numericScale(), c.datetimePrecision(),
                c.characterSetName().map(String::new), c.collationName().map(String::new), c.nullability(),
                c.columnDefault().map(String::new), new String(c.extra()), c.ordinalPosition());
    }

    private static <T> T pick(Random random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
