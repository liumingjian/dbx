package com.dbx.dialect.pair;

import com.dbx.dialect.api.CodecSelection;
import com.dbx.dialect.api.DatabasePair;
import com.dbx.dialect.api.DescriptorVersion;
import com.dbx.dialect.api.ExecutionRequirements;
import com.dbx.dialect.api.IdentifierMapping;
import com.dbx.dialect.api.MappingDecision;
import com.dbx.dialect.api.MappingOptions;
import com.dbx.dialect.api.MappingRule;
import com.dbx.dialect.api.PairDescriptor;
import com.dbx.dialect.api.SourceColumn;
import com.dbx.dialect.api.SourceCoordinate;
import com.dbx.dialect.api.SourceDialect;
import com.dbx.dialect.api.Supported;
import com.dbx.dialect.api.TargetDialect;
import com.dbx.dialect.api.ValidationCapabilities;
import com.dbx.dialect.mysql.MySql80SourceDialect;
import com.dbx.dialect.postgres.Postgres15TargetDialect;
import java.util.List;
import java.util.Optional;

/**
 * The one directed pair v1 certifies. It only composes: each capability lives in its own class of
 * this package, so the slices filling them in edit different files.
 */
public final class MySql80ToPostgres15 implements DatabasePair {

    /** The catalog's to hand out (slice 2); until then {@code DialectContractTest} reaches it directly. */
    public static final MySql80ToPostgres15 INSTANCE = new MySql80ToPostgres15();

    private static final Postgres15TargetDialect TARGET = new Postgres15TargetDialect(MySqlSourceDefinitions.INSTANCE);

    private MySql80ToPostgres15() {
    }

    @Override
    public PairDescriptor descriptor() {
        return PairRegistration.descriptor();
    }

    @Override
    public SourceDialect source() {
        return MySql80SourceDialect.INSTANCE;
    }

    @Override
    public TargetDialect target() {
        return TARGET;
    }

    @Override
    public MappingDecision map(SourceColumn column, MappingOptions options) {
        return TypeMapper.map(column, options);
    }

    @Override
    public IdentifierMapping mapIdentifier(SourceCoordinate sourceCoordinate, Optional<MappingRule> mappingRule) {
        return IdentifierMapper.mapIdentifier(sourceCoordinate, mappingRule);
    }

    @Override
    public ExecutionRequirements executionRequirements(List<Supported> mappingDecisions) {
        return PairRequirements.executionRequirements(source().boundedRead(), mappingDecisions);
    }

    @Override
    public ValidationCapabilities validationCapabilities(List<SourceColumn> columns) {
        return PairRequirements.validationCapabilities(columns);
    }

    @Override
    public CodecSelection descriptorCodec(DescriptorVersion version) {
        return PairRegistration.descriptorCodec(version);
    }
}
