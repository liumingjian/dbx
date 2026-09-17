package com.dbx.dialect.api;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.dbx.dialect.NotImplementedInSlice;
import com.dbx.dialect.pair.MySql80ToPostgres15;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The primary documentation of {@code dialect.api} (ADR-0018 §Module context). Each test defends one
 * ruling and cites it in its failure message; everything is driven through the api with values.
 */
class DialectContractTest {

    private static final DialectCatalog CATALOG = DialectCatalog.compileTime();

    /** Reached directly until slice 2's {@code catalog.select} hands it out. */
    private static final DatabasePair PAIR = MySql80ToPostgres15.INSTANCE;

    // --- Stubs fail, they do not return empty (ADR-0008 §Ownership) --------------------------------

    /**
     * Every entry point of {@code docs/spec/dialect.md} §Interface with the slice that implements it.
     * A slice that implements one deletes its row and adds the name to {@link #IMPLEMENTED}. Arguments
     * are null on purpose: a stub has to fail before it looks at them.
     */
    private static final List<Stub> STUBS = List.of(
            new Stub("catalog.select", 2, () -> CATALOG.select(null, null)),
            new Stub("catalog.list", 2, CATALOG::list),
            new Stub("pair.descriptor", 2, PAIR::descriptor),
            new Stub("pair.descriptorCodec", 2, () -> PAIR.descriptorCodec(null)),
            new Stub("pair.map", 3, () -> PAIR.map(null, null)),
            new Stub("pair.mapIdentifier", 4, () -> PAIR.mapIdentifier(null, null)),
            new Stub("source.metadataPlan", 5, () -> PAIR.source().metadataPlan(null)),
            new Stub("source.normalizeMetadata", 5, () -> PAIR.source().normalizeMetadata(null)),
            new Stub("source.capabilityPlans", 5, () -> PAIR.source().capabilityPlans(null)),
            new Stub("source.keysetCandidates", 5, () -> PAIR.source().keysetCandidates(null)),
            new Stub("source.queryProjection", 5, () -> PAIR.source().queryProjection(null, null)),
            new Stub("source.connectionSemantics", 5, () -> PAIR.source().connectionSemantics(null)),
            new Stub("source.preflightScanPlan", 6, () -> PAIR.source().preflightScanPlan(null, null)),
            new Stub("source.baselinePlan", 6, () -> PAIR.source().baselinePlan(null, null)),
            new Stub("source.validationFactPlans", 6, () -> PAIR.source().validationFactPlans(null)),
            new Stub("source.samplingPlan", 6, () -> PAIR.source().samplingPlan(null, 1)),
            new Stub("target.ddlPlan", 7, () -> PAIR.target().ddlPlan(null)),
            new Stub("target.supplementalStatements", 7, () -> PAIR.target().supplementalStatements(null)),
            new Stub("target.sinkSettings", 7, () -> PAIR.target().sinkSettings()),
            new Stub("target.catalogReadPlan", 8, () -> PAIR.target().catalogReadPlan(null)),
            new Stub("target.capabilityProbePlans", 8, () -> PAIR.target().capabilityProbePlans(null, null)),
            new Stub("target.maintenancePlans", 8, () -> PAIR.target().maintenancePlans(null)),
            new Stub("target.validationFactPlans", 8, () -> PAIR.target().validationFactPlans(null)),
            new Stub("target.samplingLookupPlan", 8, () -> PAIR.target().samplingLookupPlan(null)),
            new Stub("target.normalizeCatalog", 8, () -> PAIR.target().normalizeCatalog(null)),
            new Stub("target.leastPrivilegeSql", 8, () -> PAIR.target().leastPrivilegeSql(null)),
            new Stub("pair.executionRequirements", 9, () -> PAIR.executionRequirements(null)),
            new Stub("pair.validationCapabilities", 9, PAIR::validationCapabilities));

    /** Entry points a landed slice implements, one per line. Empty in slice 1. */
    private static final Set<String> IMPLEMENTED = Set.of();

    /** Composition, not capability: they hand out the dialects whose entry points are listed above. */
    private static final Set<String> COMPOSITION = Set.of("pair.source", "pair.target");

    @TestFactory
    Stream<DynamicTest> anUnimplementedEntryPointFailsNamingItsSlice() {
        return STUBS.stream().map(stub -> dynamicTest(stub.capability(), () -> {
            NotImplementedInSlice failure = assertThrows(
                    NotImplementedInSlice.class,
                    stub.call()::get,
                    "ADR-0008 §Ownership bans default-success stubs: " + stub.capability() + " must fail, "
                            + "not return a value");
            assertEquals(stub.capability(), failure.capability(), "the failure names the capability");
            assertEquals(stub.slice(), failure.slice(), "the failure names the slice of docs/spec/dialect.md §Slices");
            assertTrue(failure.getMessage().contains(stub.capability())
                            && failure.getMessage().contains("slice " + stub.slice()),
                    "the message a caller sees names both: " + failure.getMessage());
        }));
    }

    @Test
    void everyInterfaceEntryPointIsEitherStubbedOrImplemented() {
        Set<String> declared = new TreeSet<>();
        declared.addAll(entryPoints("catalog", DialectCatalog.class));
        declared.addAll(entryPoints("pair", DatabasePair.class));
        declared.addAll(entryPoints("source", SourceDialect.class));
        declared.addAll(entryPoints("target", TargetDialect.class));
        declared.removeAll(COMPOSITION);

        Set<String> accounted = new TreeSet<>(IMPLEMENTED);
        STUBS.forEach(stub -> accounted.add(stub.capability()));

        assertEquals(declared, accounted, "docs/spec/dialect.md §Interface: every entry point is declared in "
                + "dialect.api and is either a failing stub or implemented, never silently absent");
    }

    @Test
    void theCompositionOfThePairIsPresent() {
        assertTrue(PAIR.source() != null && PAIR.target() != null,
                "ADR-0008: a pair composes a source dialect and a target dialect");
    }

    // --- Closed outcomes (ADR-0008 §Ownership) ------------------------------------------------------

    @Test
    void proofOutcomesAreExactlyProvenInconclusiveRejected() {
        assertEquals(List.of("PROVEN", "INCONCLUSIVE", "REJECTED"),
                Arrays.stream(ProofOutcome.values()).map(Enum::name).toList(),
                "ADR-0008 §Ownership: proof outcomes are closed; \"probably fine\" is not expressible");
    }

    @Test
    void everyResultIsAClosedChoice() {
        assertEquals(Set.of(DatabasePair.class, Unsupported.class), permitted(PairSelection.class),
                "ADR-0008 §Registration: catalog.select → DatabasePair | Unsupported");
        assertEquals(Set.of(Supported.class, Unsupported.class), permitted(MappingDecision.class),
                "TP §6.1: pair.map → Supported | Unsupported");
        assertEquals(Set.of(IdentifierMapping.Exact.class, IdentifierMapping.Renamed.class, Unsupported.class),
                permitted(IdentifierMapping.class), "TP §7.1: pair.mapIdentifier → Exact | Renamed | Unsupported");
        assertEquals(Set.of(DescriptorCodec.class, Unsupported.class), permitted(CodecSelection.class),
                "ADR-0008 §Registration: pair.descriptorCodec → DescriptorCodec | Unsupported");
    }

    @Test
    void mappingOptionsHoldExactlyTheTwoTaskSwitches() {
        assertEquals(List.of("tinyintOneAsBoolean", "zeroDateAsNull"),
                Arrays.stream(MappingOptions.class.getRecordComponents()).map(c -> c.getName()).toList(),
                "TP §6.1: MappingOptions holds exactly two switches; a third is a decision ticket");
    }

    // --- SqlPlan shape (ADR-0008 §Plans) ------------------------------------------------------------

    @Test
    void aPlanCannotBeChangedThroughTheCollectionsItWasBuiltFrom() {
        List<SqlValue> parameters = new ArrayList<>(List.of(new SqlValue.Int64(7)));
        List<ParameterizedStatement> statements =
                new ArrayList<>(List.of(new ParameterizedStatement("SELECT 1 WHERE ? > 0", parameters)));
        Set<RequiredPrivilege> privileges = new HashSet<>(Set.of(RequiredPrivilege.SOURCE_SELECT));
        SqlPlan plan = new SqlPlan(OperationKind.SOURCE_BASELINE_READ, statements, oneCountRow(),
                TimeoutClass.EXACT_SCAN, privileges, EvidencePolicy.STATEMENT_AND_AGGREGATES);
        PlanFingerprint before = plan.fingerprint();

        parameters.add(new SqlValue.Int64(8));
        statements.add(new ParameterizedStatement("SELECT 2", List.of()));
        privileges.add(RequiredPrivilege.TARGET_OWN_OBJECT);

        assertEquals(before, plan.fingerprint(), "ADR-0008 §Plans: a plan is immutable once built");
        assertThrows(UnsupportedOperationException.class, () -> plan.statements().add(null),
                "ADR-0008 §Plans: a plan's statements are read-only");
        assertThrows(UnsupportedOperationException.class,
                () -> plan.requiredPrivileges().add(RequiredPrivilege.TARGET_CREATE_SCHEMA),
                "ADR-0008 §Plans: a plan's privileges are read-only");
    }

    @Test
    void boundBytesCannotBeChangedFromOutsideThePlan() {
        byte[] raw = {1, 2, 3};
        SqlValue.Bytes value = new SqlValue.Bytes(raw);
        SqlPlan plan = plan(OperationKind.TARGET_SAMPLING_LOOKUP, "SELECT 1 WHERE \"k\" = ?", value);
        PlanFingerprint before = plan.fingerprint();

        raw[0] = 9;
        value.value()[1] = 9;

        assertEquals(before, plan.fingerprint(), "ADR-0008 §Plans: typed parameter values are immutable");
    }

    @Test
    void everyValueIsBoundOnePerPlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> new ParameterizedStatement("SELECT 1 WHERE a = ? AND b = ?", List.of(new SqlValue.Int64(1))),
                "ADR-0008 §Plans: values are always bound, so a placeholder without a value is refused");
        assertThrows(IllegalArgumentException.class,
                () -> new ParameterizedStatement("SELECT 1", List.of(new SqlValue.Int64(1))),
                "ADR-0008 §Plans: a value without a placeholder was meant to be spliced somewhere; refused");
    }

    @Test
    void aPlanHasAtLeastOneStatementAndEveryComponent() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlPlan(OperationKind.TARGET_DDL, List.of(), ResultSchema.NONE, TimeoutClass.DDL,
                        Set.of(), EvidencePolicy.STATEMENT_ONLY),
                "ADR-0008 §Plans: an empty plan is the plausible nothing ADR-0008 §Ownership bans");
        assertThrows(NullPointerException.class,
                () -> new SqlPlan(OperationKind.TARGET_DDL, List.of(new ParameterizedStatement("SELECT 1", List.of())),
                        ResultSchema.NONE, null, Set.of(), EvidencePolicy.STATEMENT_ONLY),
                "ADR-0008 §Plans: a plan without a timeout class is not a plan");
    }

    // --- Fingerprint determinism (ADR-0008 §Plans) --------------------------------------------------

    @Test
    void equalContentFingerprintsEqual() {
        Set<RequiredPrivilege> oneOrder = new LinkedHashSet<>(
                List.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT));
        Set<RequiredPrivilege> otherOrder = new LinkedHashSet<>(
                List.of(RequiredPrivilege.TARGET_OWN_OBJECT, RequiredPrivilege.TARGET_READ_CATALOG));

        SqlPlan first = syntheticPlan(oneOrder);
        SqlPlan second = syntheticPlan(otherOrder);

        assertEquals(first, second, "equal content is an equal plan");
        assertEquals(first.fingerprint(), second.fingerprint(),
                "ADR-0008 §Plans: the fingerprint is a function of content, not of construction order");
    }

    /**
     * Pins the encoding across runs and machines. The value was derived independently of Java, from
     * the documented encoding (length-prefixed UTF-8, big-endian ints) in a separate script. A slice
     * that changes the encoding on purpose bumps {@code SqlPlan.ENCODING} and this value together.
     */
    @Test
    void theFingerprintIsStableAcrossRunsAndMachines() {
        assertEquals("ead554390c34d2b798f0394b676b91dc293f9815ad26a31a6f602771605a32aa",
                syntheticPlan(Set.of(RequiredPrivilege.TARGET_READ_CATALOG, RequiredPrivilege.TARGET_OWN_OBJECT))
                        .fingerprint().sha256Hex(),
                "ADR-0008 §Plans: a fingerprint must not depend on the JVM, the machine or the run");
    }

    @Test
    void changingAnyContributingFieldChangesTheFingerprint() {
        SqlPlan base = syntheticPlan(Set.of(RequiredPrivilege.TARGET_READ_CATALOG));
        ParameterizedStatement statement = base.statements().get(0);
        Map<String, SqlPlan> variants = Map.ofEntries(
                Map.entry("operation kind", with(base, OperationKind.TARGET_VALIDATION_FACTS)),
                Map.entry("statement text", with(base, List.of(new ParameterizedStatement(
                        statement.sql().replace("\"n\"", "\"m\""), statement.parameters())))),
                Map.entry("statement count", with(base, List.of(statement, statement))),
                Map.entry("parameter value", with(base, List.of(new ParameterizedStatement(statement.sql(),
                        List.of(new SqlValue.Text("public"), new SqlValue.Int64(43)))))),
                Map.entry("parameter type", with(base, List.of(new ParameterizedStatement(statement.sql(),
                        List.of(new SqlValue.Text("public"), new SqlValue.Decimal(BigDecimal.valueOf(42))))))),
                Map.entry("decimal scale", with(base, List.of(new ParameterizedStatement(statement.sql(),
                        List.of(new SqlValue.Text("public"), new SqlValue.Decimal(new BigDecimal("42.0"))))))),
                Map.entry("typed null", with(base, List.of(new ParameterizedStatement(statement.sql(),
                        List.of(new SqlValue.Text("public"), new SqlValue.Null(SqlValue.Type.INT64)))))),
                Map.entry("result column label", with(base, new ResultSchema(List.of(
                        new ResultSchema.Column("oid2", "oid", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.AT_MOST_ONE_ROW))),
                Map.entry("result column type", with(base, new ResultSchema(List.of(
                        new ResultSchema.Column("oid", "bigint", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.AT_MOST_ONE_ROW))),
                Map.entry("result nullability", with(base, new ResultSchema(List.of(
                        new ResultSchema.Column("oid", "oid", Nullability.NULLABLE)),
                        ResultSchema.Cardinality.AT_MOST_ONE_ROW))),
                Map.entry("result cardinality", with(base, new ResultSchema(List.of(
                        new ResultSchema.Column("oid", "oid", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.EXACTLY_ONE_ROW))),
                Map.entry("timeout class", with(base, TimeoutClass.MAINTENANCE)),
                Map.entry("required privileges", with(base, Set.of(RequiredPrivilege.TARGET_OWN_OBJECT))),
                Map.entry("evidence policy", with(base, EvidencePolicy.STATEMENT_ONLY)));

        Set<PlanFingerprint> seen = new HashSet<>(Set.of(base.fingerprint()));
        for (Map.Entry<String, SqlPlan> variant : new TreeMap<>(variants).entrySet()) {
            assertTrue(seen.add(variant.getValue().fingerprint()),
                    "ADR-0008 §Plans: the fingerprint covers the " + variant.getKey()
                            + ", so changing only it must change the fingerprint");
        }
    }

    // --- Hostile identifiers and hostile values (ADR-0008 §Plans; TP §7.1) --------------------------

    @Test
    void hostileNamesAreKeptCharacterForCharacter() {
        List<String> hostile = List.of(
                "a\"b", "a\"\"b", "x\"; DROP TABLE t; --", "back\\slash", "new\nline", "?", "`tick`",
                "客户订单明细", "emoji😀", " padded ");
        for (String name : hostile) {
            assertEquals(name, new ColumnCoordinate(name, name, name).column(),
                    "TP §7.1: a source name is preserved character for character: " + name);
            assertEquals(name, new TargetIdentifier(name).name(),
                    "TP §7.1: an approved target name is kept unquoted and exact; quoting is central: " + name);
        }
        assertNotEquals(new TargetIdentifier("a\"b"), new TargetIdentifier("a\"\"b"),
                "TP §7.1: an embedded quote and an escaped quote are different names, never normalised together");
    }

    @Test
    void aPlaceholderInsideAQuotedIdentifierOrLiteralIsNotABinding() {
        String sql = "SELECT \"a?b\", `c?`, 'd?' FROM \"t\"\"?\" WHERE \"k\" = ?";

        ParameterizedStatement statement = new ParameterizedStatement(sql, List.of(new SqlValue.Text("v")));

        assertEquals(1, statement.parameters().size(),
                "ADR-0008 §Plans: a hostile identifier containing ? must not shift the bound values");
        assertThrows(IllegalArgumentException.class,
                () -> new ParameterizedStatement("SELECT \"a?b FROM t WHERE k = ?", List.of(new SqlValue.Text("v"))),
                "ADR-0008 §Plans: an unterminated quoted identifier is refused, not guessed at");
    }

    @Test
    void aHostileValueIsBoundAndNeverReachesTheStatementText() {
        String sql = "SELECT count(*) FROM \"s\".\"t\" WHERE \"name\" = ?";
        SqlValue hostile = new SqlValue.Text("'); DROP TABLE t; -- ?\"\\\n");

        SqlPlan plan = plan(OperationKind.SOURCE_VALIDATION_FACTS, sql, hostile);

        assertEquals(sql, plan.statements().get(0).sql(),
                "ADR-0008 §Plans: values are bound, so the statement text never contains them");
        assertEquals(List.of(hostile), plan.statements().get(0).parameters(),
                "ADR-0008 §Plans: the hostile value travels as its typed parameter, unchanged");
        assertNotEquals(plan.fingerprint(), plan(OperationKind.SOURCE_VALIDATION_FACTS, sql,
                        new SqlValue.Text("benign")).fingerprint(),
                "ADR-0008 §Plans: bound values contribute to the fingerprint");
    }

    @Test
    void adjacentStringsCannotBeRespelledIntoTheSameFingerprint() {
        SqlPlan ab = plan(OperationKind.SOURCE_SAMPLING, "SELECT ? , ?", new SqlValue.Text("ab"), new SqlValue.Text("c"));
        SqlPlan bc = plan(OperationKind.SOURCE_SAMPLING, "SELECT ? , ?", new SqlValue.Text("a"), new SqlValue.Text("bc"));
        SqlPlan textOne = plan(OperationKind.SOURCE_SAMPLING, "SELECT ?", new SqlValue.Text("1"));
        SqlPlan intOne = plan(OperationKind.SOURCE_SAMPLING, "SELECT ?", new SqlValue.Int64(1));

        assertNotEquals(ab.fingerprint(), bc.fingerprint(),
                "ADR-0008 §Plans: every string is length-prefixed, so shifting a boundary changes the fingerprint");
        assertNotEquals(textOne.fingerprint(), intOne.fingerprint(),
                "ADR-0008 §Plans: parameters are typed, so '1' and 1 are different plans");
    }

    // --- Purity and shape of the module (ADR-0018 §Enforcement; ADR-0008 §Ownership) ----------------

    private static final JavaClasses DIALECT = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.dbx.dialect");

    @Test
    void dialectDependsOnNoOtherModule() {
        noClasses().that().resideInAPackage("com.dbx.dialect..")
                .should().dependOnClassesThat(resideInAPackage("com.dbx..")
                        .and(resideOutsideOfPackage("com.dbx.dialect..")))
                .as("docs/spec/dialect.md §Consumes: dialect is the bottom of the graph and depends on no other "
                        + "module, so it can never advance workflow or create or approve a contract (ADR-0008 "
                        + "§Ownership)")
                .check(DIALECT);
    }

    @Test
    void dialectUsesNoReflectionServiceLoaderOrJsonBag() {
        noClasses().that().resideInAPackage("com.dbx.dialect..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.lang.reflect..", "com.fasterxml.jackson..", "com.google.gson..", "org.json..",
                        "jakarta.json..", "javax.json..")
                .orShould().dependOnClassesThat().belongToAnyOf(ServiceLoader.class)
                .orShould().callMethod(Class.class, "forName", String.class)
                .as("ADR-0008 §Ownership; TP §3.2: fixed, strongly typed capabilities only — no reflection, SPI, "
                        + "ServiceLoader, classpath scanning or JSON bag")
                .check(DIALECT);
    }

    @Test
    void theApiCarriesNoMapBag() {
        noClasses().that().resideInAPackage("com.dbx.dialect.api..")
                .should().dependOnClassesThat().belongToAnyOf(Map.class)
                .as("ADR-0008 §Contract: dialect.api models database semantics as types, never as a "
                        + "Map<String, Object> or other key-value bag")
                .check(DIALECT);
    }

    // --- Synthetic plans ----------------------------------------------------------------------------

    private record Stub(String capability, int slice, Supplier<?> call) {
    }

    private static Set<String> entryPoints(String prefix, Class<?> api) {
        return Arrays.stream(api.getDeclaredMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getName)
                .map(name -> prefix + "." + name)
                .collect(Collectors.toSet());
    }

    private static Set<Class<?>> permitted(Class<?> sealed) {
        return Set.of(sealed.getPermittedSubclasses());
    }

    private static ResultSchema oneCountRow() {
        return new ResultSchema(List.of(new ResultSchema.Column("count", "bigint", Nullability.NOT_NULL)),
                ResultSchema.Cardinality.EXACTLY_ONE_ROW);
    }

    private static SqlPlan plan(OperationKind kind, String sql, SqlValue... parameters) {
        return new SqlPlan(kind, List.of(new ParameterizedStatement(sql, List.of(parameters))), oneCountRow(),
                TimeoutClass.EXACT_SCAN, Set.of(RequiredPrivilege.SOURCE_SELECT), EvidencePolicy.STATEMENT_AND_RESULT);
    }

    private static SqlPlan syntheticPlan(Set<RequiredPrivilege> privileges) {
        return new SqlPlan(
                OperationKind.TARGET_CATALOG_READ,
                List.of(new ParameterizedStatement(
                        "SELECT c.oid FROM pg_class c JOIN pg_namespace \"n\" ON \"n\".oid = c.relnamespace "
                                + "WHERE \"n\".nspname = ? AND c.relpages = ?",
                        List.of(new SqlValue.Text("public"), new SqlValue.Int64(42)))),
                new ResultSchema(List.of(new ResultSchema.Column("oid", "oid", Nullability.NOT_NULL)),
                        ResultSchema.Cardinality.AT_MOST_ONE_ROW),
                TimeoutClass.CATALOG_READ,
                privileges,
                EvidencePolicy.STATEMENT_AND_RESULT);
    }

    private static SqlPlan with(SqlPlan p, OperationKind kind) {
        return new SqlPlan(kind, p.statements(), p.resultSchema(), p.timeoutClass(), p.requiredPrivileges(), p.evidencePolicy());
    }

    private static SqlPlan with(SqlPlan p, List<ParameterizedStatement> statements) {
        return new SqlPlan(p.operationKind(), statements, p.resultSchema(), p.timeoutClass(), p.requiredPrivileges(),
                p.evidencePolicy());
    }

    private static SqlPlan with(SqlPlan p, ResultSchema schema) {
        return new SqlPlan(p.operationKind(), p.statements(), schema, p.timeoutClass(), p.requiredPrivileges(),
                p.evidencePolicy());
    }

    private static SqlPlan with(SqlPlan p, TimeoutClass timeout) {
        return new SqlPlan(p.operationKind(), p.statements(), p.resultSchema(), timeout, p.requiredPrivileges(),
                p.evidencePolicy());
    }

    private static SqlPlan with(SqlPlan p, Set<RequiredPrivilege> privileges) {
        return new SqlPlan(p.operationKind(), p.statements(), p.resultSchema(), p.timeoutClass(), privileges,
                p.evidencePolicy());
    }

    private static SqlPlan with(SqlPlan p, EvidencePolicy policy) {
        return new SqlPlan(p.operationKind(), p.statements(), p.resultSchema(), p.timeoutClass(), p.requiredPrivileges(),
                policy);
    }
}
