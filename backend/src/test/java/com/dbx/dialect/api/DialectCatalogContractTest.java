package com.dbx.dialect.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Which database pairs DBX will migrate between, and how a frozen pair descriptor is read back
 * (obligations 6–9 of {@code docs/spec/dialect.md}; ADR-0008 §Registration; ADR-0033). Everything is
 * driven through {@code dialect.api}: probed facts in, a pair or a stated refusal out.
 */
class DialectCatalogContractTest {

    private static final DialectCatalog CATALOG = DialectCatalog.compileTime();

    private static final ProductVersion MYSQL_80 = new ProductVersion("MySQL", "8.0.36");
    private static final ProductVersion POSTGRES_15 = new ProductVersion("PostgreSQL", "15.4");

    // --- The one pair (obligation 6) ----------------------------------------------------------------

    @Test
    void mySql80ToPostgres15SelectsTheOnePair() {
        DatabasePair pair = assertInstanceOf(DatabasePair.class, CATALOG.select(MYSQL_80, POSTGRES_15),
                "ADR-0008 §Registration: MySQL 8.0 → PostgreSQL 15 is the pair v1 certifies");

        assertEquals(new PairDescriptor(new DialectId("mysql-8.0"), new DialectId("postgresql-15"),
                        new MappingVersion(1), new CertificationVersion(1)),
                pair.descriptor(),
                "ADR-0008 §Registration: a pair records its dialect ids, mapping version and certification version");
    }

    @TestFactory
    Stream<DynamicTest> everyProbedReleaseOfTheCertifiedSeriesSelectsTheSamePair() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
        List<ProductVersion[]> probes = List.of(
                pairOf("MySQL", "8.0.0", "PostgreSQL", "15.0"),
                pairOf("MySQL", "8.0.36-log", "PostgreSQL", "15.4 (Debian 15.4-1.pgdg120+1)"),
                pairOf("MySQL", "8.0.40-0ubuntu0.22.04.1", "PostgreSQL", "15.10"),
                pairOf("MySQL", "8.0.36+commercial", "PostgreSQL", "15.4-rds"));
        return probes.stream().map(probe -> dynamicTest(probe[0].version() + " → " + probe[1].version(),
                () -> assertSame(pair, CATALOG.select(probe[0], probe[1]),
                        "ADR-0008 §Registration: a release of the certified series is the certified pair")));
    }

    // --- Refusals (obligation 7) --------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> aProductTheCatalogDoesNotKnowIsMissing() {
        return Stream.of(
                        pairOf("Oracle", "19.0.0", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.36", "Microsoft SQL Server", "15.0.2000"),
                        pairOf("MariaDB", "8.0.36", "PostgreSQL", "15.4"),
                        pairOf("mysql", "8.0.36", "PostgreSQL", "15.4"),
                        pairOf("MySQL ", "8.0.36", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.36", "Postgres", "15.4"))
                .map(probe -> refused(probe, CatalogUnsupportedReason.MISSING,
                        "ADR-0008 §Registration: a product is matched exactly, never by resemblance"));
    }

    /**
     * No nearest-version fallback: each of these is one step from a certified release, and each is
     * refused rather than rounded to it.
     */
    @TestFactory
    Stream<DynamicTest> aNearMissVersionIsIncompatibleNeverTheClosestPair() {
        return Stream.of(
                        pairOf("MySQL", "8.1.0", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.4.3", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "5.7.44", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "9.0.1", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "80.0.36", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "08.0.36", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.00.36", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.36.1", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.36x", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "v8.0.36", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "latest", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "14.9"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "16.0"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "150.4"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "1.5"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "15.4.1"))
                .map(probe -> refused(probe, CatalogUnsupportedReason.VERSION_INCOMPATIBLE,
                        "ADR-0008 §Registration: there is no nearest-version or default fallback"));
    }

    /** A version that agrees with the certified series but does not name one release is not guessed at. */
    @TestFactory
    Stream<DynamicTest> aVersionThatDoesNotNameOneReleaseIsAmbiguous() {
        return Stream.of(
                        pairOf("MySQL", "8", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.x", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0-log", "PostgreSQL", "15.4"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "15"),
                        pairOf("MySQL", "8.0.36", "PostgreSQL", "15beta1"))
                .map(probe -> refused(probe, CatalogUnsupportedReason.AMBIGUOUS,
                        "ADR-0008 §Registration: an ambiguous version is refused, not resolved to a release"));
    }

    /**
     * No automatic composition: the catalog recognises both MySQL 8.0 and PostgreSQL 15, and still no
     * pair exists in any direction or combination except the one registered and certified.
     */
    @TestFactory
    Stream<DynamicTest> recognisedEndpointsDoNotComposeAPair() {
        return Stream.of(
                        pairOf("PostgreSQL", "15.4", "MySQL", "8.0.36"),
                        pairOf("MySQL", "8.0.36", "MySQL", "8.0.36"),
                        pairOf("PostgreSQL", "15.4", "PostgreSQL", "15.4"))
                .map(probe -> refused(probe, CatalogUnsupportedReason.UNCERTIFIED,
                        "ADR-0008 §Registration: registering both endpoint dialects does not create a pair"));
    }

    @Test
    void aRefusalCarriesExactlyWhatWasAsked() {
        ProductVersion nearMiss = new ProductVersion("MySQL", "8.4.3");

        assertEquals(new Unsupported(CatalogUnsupportedReason.VERSION_INCOMPATIBLE, new CatalogRequest(nearMiss,
                        POSTGRES_15)),
                CATALOG.select(nearMiss, POSTGRES_15),
                "ADR-0008 §Contract: a refusal carries a stable reason and the evidence it needs");
    }

    @Test
    void theCatalogRefusesInAFixedVocabulary() {
        assertEquals(List.of("MISSING", "AMBIGUOUS", "UNCERTIFIED", "VERSION_INCOMPATIBLE"),
                Arrays.stream(CatalogUnsupportedReason.values()).map(Enum::name).toList(),
                "ADR-0005: diagnosis keys on these codes; renaming or adding one is a breaking change");
    }

    // --- catalog.list (obligation 6; #46 Q1) --------------------------------------------------------

    @Test
    void theConnectionFormIsOfferedExactlyTheCertifiedPair() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);

        assertEquals(List.of(pair.descriptor()), CATALOG.list(),
                "#46 Q1: catalog.list offers exactly the certified pairs, so v1 lists one and nothing greyed out");
        assertThrows(UnsupportedOperationException.class, () -> CATALOG.list().add(pair.descriptor()),
                "ADR-0008 §Registration: the catalog is compile-time; a caller cannot add to it");
    }

    // --- Descriptor codecs (obligation 8) -----------------------------------------------------------

    @Test
    void aCodecRoundTripsTheDescriptorUnderItsOwnVersion() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
        DescriptorCodec codec = assertInstanceOf(DescriptorCodec.class, pair.descriptorCodec(new DescriptorVersion(1)),
                "ADR-0008 §Registration: this build reads descriptor version 1");

        EncodedDescriptor encoded = codec.encode(pair.descriptor());

        assertEquals(new DescriptorVersion(1), codec.version(), "a codec is the codec of the version asked for");
        assertEquals(new DescriptorVersion(1), encoded.version(),
                "ADR-0008 §Registration: a codec writes its own version, never a newer one");
        assertEquals(pair.descriptor(), codec.decode(encoded), "a descriptor survives its own codec unchanged");
    }

    /** Pins version 1's text: a snapshot written by this release must read identically in every later one. */
    @Test
    void versionOneTextIsFrozen() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
        DescriptorCodec codec = (DescriptorCodec) pair.descriptorCodec(new DescriptorVersion(1));

        assertEquals("dbx.dialect.PairDescriptor/1|9:mysql-8.0|13:postgresql-15|1|1",
                codec.encode(pair.descriptor()).text(),
                "ADR-0008 §Registration: version 1's encoding is frozen; a new shape is a new version");
    }

    /**
     * No silent upgrade: a descriptor recorded under other mapping and certification versions, with
     * hostile ids, reads back as recorded rather than as the installed pair's current descriptor.
     */
    @Test
    void decodingPreservesTheRecordedSemanticsNotTheInstalledOnes() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
        DescriptorCodec codec = (DescriptorCodec) pair.descriptorCodec(new DescriptorVersion(1));
        PairDescriptor recorded = new PairDescriptor(new DialectId("a|9:b\n客户"), new DialectId("|"),
                new MappingVersion(7), new CertificationVersion(3));

        PairDescriptor decoded = codec.decode(codec.encode(recorded));

        assertEquals(recorded, decoded,
                "ADR-0008 §Registration: a compatible reader preserves the original version identity and semantics");
        assertNotEquals(pair.descriptor(), decoded, "the installed pair's descriptor is not substituted");
    }

    @TestFactory
    Stream<DynamicTest> aVersionTheBuildCannotInterpretIsRefused() {
        return Stream.of(2, 3, 999, Integer.MAX_VALUE).map(value -> dynamicTest("version " + value, () -> {
            DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
            DescriptorVersion version = new DescriptorVersion(value);

            assertEquals(new Unsupported(CodecUnsupportedReason.UNKNOWN_DESCRIPTOR_VERSION, version),
                    pair.descriptorCodec(version),
                    "ADR-0008 §Registration: an uninterpretable version is refused, never read by another codec");
        }));
    }

    @Test
    void aCodecDoesNotReadAnotherVersionsText() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
        DescriptorCodec codec = (DescriptorCodec) pair.descriptorCodec(new DescriptorVersion(1));
        String text = codec.encode(pair.descriptor()).text();

        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new EncodedDescriptor(new DescriptorVersion(2), text)),
                "ADR-0008 §Registration: a version 1 codec never reads a descriptor stamped version 2");
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new EncodedDescriptor(new DescriptorVersion(1), text.replace("/1|", "/2|"))),
                "ADR-0008 §Registration: text of another version is not read as version 1");
    }

    @TestFactory
    Stream<DynamicTest> aMalformedSnapshotIsRefusedNotRepaired() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);
        DescriptorCodec codec = (DescriptorCodec) pair.descriptorCodec(new DescriptorVersion(1));
        return Stream.of(
                        "dbx.dialect.PairDescriptor/1|9:mysql-8.0|13:postgresql-15|1",
                        "dbx.dialect.PairDescriptor/1|9:mysql-8.0|13:postgresql-15|1|1|",
                        "dbx.dialect.PairDescriptor/1|9:mysql-8.0|13:postgresql-15|01|1",
                        "dbx.dialect.PairDescriptor/1|9:mysql-8.0|13:postgresql-15|0|1",
                        "dbx.dialect.PairDescriptor/1|10:mysql-8.0|13:postgresql-15|1|1",
                        "dbx.dialect.PairDescriptor/1|99:mysql-8.0",
                        " dbx.dialect.PairDescriptor/1|9:mysql-8.0|13:postgresql-15|1|1")
                .map(text -> dynamicTest(text, () -> assertThrows(IllegalArgumentException.class,
                        () -> codec.decode(new EncodedDescriptor(new DescriptorVersion(1), text)),
                        "ADR-0008 §Registration: a snapshot the codec cannot interpret exactly is refused")));
    }

    // --- Bounded read is a construction precondition (obligation 9; ADR-0033) -----------------------

    @Test
    void everySourceDialectConstructorDemandsABoundedReadDeclaration() {
        Constructor<?>[] constructors = SourceDialect.class.getDeclaredConstructors();

        assertTrue(constructors.length > 0 && Arrays.stream(constructors)
                        .allMatch(constructor -> List.of(constructor.getParameterTypes())
                                .contains(BoundedReadRequirement.class)),
                "ADR-0033: a source dialect cannot be constructed without a bounded-read declaration, so no "
                        + "SourceDialect constructor may omit it: " + Arrays.toString(constructors));
    }

    @Test
    void aSourceDialectWithoutABoundedReadDeclarationCannotExist() {
        NullPointerException failure = assertThrows(NullPointerException.class, UndeclaredSourceDialect::new,
                "ADR-0033: passing no bounded-read declaration must fail construction, not register a dialect");
        assertTrue(failure.getMessage().contains("ADR-0033"), "the failure names its ruling: " + failure.getMessage());
    }

    @Test
    void theCertifiedPairsSourceDialectDeclaresItsBoundedRead() {
        DatabasePair pair = (DatabasePair) CATALOG.select(MYSQL_80, POSTGRES_15);

        assertTrue(pair.source().boundedRead() != null,
                "ADR-0033: the registered source dialect carries its bounded-read declaration");
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private static ProductVersion[] pairOf(String sourceProduct, String sourceVersion, String targetProduct,
            String targetVersion) {
        return new ProductVersion[] {
            new ProductVersion(sourceProduct, sourceVersion), new ProductVersion(targetProduct, targetVersion)};
    }

    private static DynamicTest refused(ProductVersion[] probe, CatalogUnsupportedReason reason, String ruling) {
        String name = "'" + probe[0].product() + "' " + probe[0].version() + " → '" + probe[1].product() + "' "
                + probe[1].version();
        return dynamicTest(name, () -> assertEquals(
                new Unsupported(reason, new CatalogRequest(probe[0], probe[1])),
                CATALOG.select(probe[0], probe[1]),
                ruling + ": " + name + " must be " + reason));
    }

    /** A source dialect that tries to exist without declaring how its reads stay bounded. */
    private static final class UndeclaredSourceDialect extends SourceDialect {

        UndeclaredSourceDialect() {
            super(null);
        }

        @Override
        public SqlPlan metadataPlan(MetadataScope scope) {
            throw new AssertionError("never constructed");
        }

        @Override
        public List<SourceTableMetadata> normalizeMetadata(ResultRows rows) {
            throw new AssertionError("never constructed");
        }

        @Override
        public List<SqlPlan> capabilityPlans(MetadataScope scope) {
            throw new AssertionError("never constructed");
        }

        @Override
        public SqlPlan preflightScanPlan(SourceTableMetadata table, List<ApprovedColumn> approvedColumns,
                List<PreflightObligation> obligations) {
            throw new AssertionError("never constructed");
        }

        @Override
        public SqlPlan baselinePlan(SourceTableMetadata table, Optional<KeysetColumn> keysetColumn) {
            throw new AssertionError("never constructed");
        }

        @Override
        public List<SqlPlan> validationFactPlans(List<ValidationItem> items) {
            throw new AssertionError("never constructed");
        }

        @Override
        public List<SqlPlan> samplingPlan(SamplingKey key, int n) {
            throw new AssertionError("never constructed");
        }

        @Override
        public List<KeysetCandidate> keysetCandidates(SourceTableMetadata table) {
            throw new AssertionError("never constructed");
        }

        @Override
        public ProjectionSql queryProjection(List<ApprovedColumn> approvedColumns, List<MappingRule> mappingRules) {
            throw new AssertionError("never constructed");
        }

        @Override
        public ConnectionSemantics connectionSemantics(MappingOptions options) {
            throw new AssertionError("never constructed");
        }
    }
}
