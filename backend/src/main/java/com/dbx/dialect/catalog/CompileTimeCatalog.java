package com.dbx.dialect.catalog;

import com.dbx.dialect.api.CatalogRequest;
import com.dbx.dialect.api.CatalogUnsupportedReason;
import com.dbx.dialect.api.DatabasePair;
import com.dbx.dialect.api.DialectCatalog;
import com.dbx.dialect.api.DialectId;
import com.dbx.dialect.api.PairDescriptor;
import com.dbx.dialect.api.PairSelection;
import com.dbx.dialect.api.ProductVersion;
import com.dbx.dialect.api.Unsupported;
import com.dbx.dialect.mysql.MySql80SourceDialect;
import com.dbx.dialect.pair.MySql80ToPostgres15;
import com.dbx.dialect.postgres.Postgres15TargetDialect;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * v1's compile-time catalog (ADR-0008 §Registration). Endpoint dialects and database pairs are
 * registered separately, and selection only ever returns a registered pair: recognising both
 * endpoints of a request never composes one. Every step is an exact match, so there is no nearest
 * version, no default and nothing to fall back to.
 */
public final class CompileTimeCatalog implements DialectCatalog {

    /** Endpoint dialects the catalog recognises, in any role. Recognition alone certifies nothing. */
    private static final List<ReleaseSeries> ENDPOINTS = List.of(
            new ReleaseSeries(MySql80SourceDialect.ID, "MySQL", List.of("8", "0"), 3),
            new ReleaseSeries(Postgres15TargetDialect.ID, "PostgreSQL", List.of("15"), 2));

    /** Independently certified directed pairs; the only things {@link #select} can return. */
    private static final List<DatabasePair> PAIRS = List.of(MySql80ToPostgres15.INSTANCE);

    public static final CompileTimeCatalog INSTANCE = new CompileTimeCatalog();

    private CompileTimeCatalog() {
        Set<DialectId> endpointIds = new HashSet<>();
        ENDPOINTS.forEach(endpoint -> require(endpointIds.add(endpoint.id()), "duplicate endpoint " + endpoint.id()));
        Set<List<DialectId>> directions = new HashSet<>();
        for (DatabasePair pair : PAIRS) {
            PairDescriptor descriptor = pair.descriptor();
            require(endpointIds.contains(descriptor.sourceDialect()) && endpointIds.contains(descriptor.targetDialect()),
                    "pair " + descriptor + " names an unregistered endpoint dialect");
            require(directions.add(List.of(descriptor.sourceDialect(), descriptor.targetDialect())),
                    "pair " + descriptor + " is registered twice");
        }
    }

    @Override
    public PairSelection select(ProductVersion sourceProductVersion, ProductVersion targetProductVersion) {
        CatalogRequest request = new CatalogRequest(sourceProductVersion, targetProductVersion);
        Resolution source = resolve(sourceProductVersion);
        if (source.refusal() != null) {
            return new Unsupported(source.refusal(), request);
        }
        Resolution target = resolve(targetProductVersion);
        if (target.refusal() != null) {
            return new Unsupported(target.refusal(), request);
        }
        List<DatabasePair> matches = PAIRS.stream()
                .filter(pair -> pair.descriptor().sourceDialect().equals(source.id())
                        && pair.descriptor().targetDialect().equals(target.id()))
                .toList();
        return switch (matches.size()) {
            case 0 -> new Unsupported(CatalogUnsupportedReason.UNCERTIFIED, request);
            case 1 -> matches.get(0);
            default -> new Unsupported(CatalogUnsupportedReason.AMBIGUOUS, request);
        };
    }

    @Override
    public List<PairDescriptor> list() {
        return PAIRS.stream().map(DatabasePair::descriptor).toList();
    }

    private static Resolution resolve(ProductVersion probed) {
        List<ReleaseSeries> sameProduct = ENDPOINTS.stream()
                .filter(endpoint -> endpoint.product().equals(probed.product()))
                .toList();
        if (sameProduct.isEmpty()) {
            return Resolution.refused(CatalogUnsupportedReason.MISSING);
        }
        List<ReleaseSeries> releases = sameProduct.stream()
                .filter(endpoint -> endpoint.match(probed.version()) == ReleaseSeries.Match.MATCHES)
                .toList();
        if (releases.size() == 1) {
            return new Resolution(releases.get(0).id(), null);
        }
        boolean ambiguous = releases.size() > 1 || sameProduct.stream()
                .anyMatch(endpoint -> endpoint.match(probed.version()) == ReleaseSeries.Match.AMBIGUOUS);
        return Resolution.refused(ambiguous
                ? CatalogUnsupportedReason.AMBIGUOUS
                : CatalogUnsupportedReason.VERSION_INCOMPATIBLE);
    }

    private static void require(boolean condition, String violation) {
        if (!condition) {
            throw new IllegalStateException("ADR-0008 §Registration: invalid compile-time catalog: " + violation);
        }
    }

    /** Exactly one of an endpoint id or the reason it could not be identified. */
    private record Resolution(DialectId id, CatalogUnsupportedReason refusal) {

        Resolution {
            if ((id == null) == (refusal == null)) {
                throw new IllegalArgumentException("a resolution is an id or a refusal");
            }
        }

        static Resolution refused(CatalogUnsupportedReason reason) {
            return new Resolution(null, Objects.requireNonNull(reason));
        }
    }
}
