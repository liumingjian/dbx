package com.dbx.dialect.api;

/** {@code pair.mapIdentifier}'s closed result: {@link Exact}, {@link Renamed} or {@link Unsupported} (TP §7.1). */
public sealed interface IdentifierMapping permits IdentifierMapping.Exact, IdentifierMapping.Renamed, Unsupported {

    /** The source name is representable unchanged. */
    record Exact(SourceCoordinate source, TargetIdentifier target) implements IdentifierMapping {

        public Exact {
            Checks.present(source, "source");
            Checks.present(target, "target");
        }
    }

    /**
     * An overlong schema or table name, renamed deterministically. Both coordinates and the algorithm
     * version travel together so review and the report can show a source-to-target row.
     */
    record Renamed(SourceCoordinate source, TargetIdentifier target, RenameAlgorithmVersion algorithmVersion)
            implements IdentifierMapping {

        public Renamed {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.present(algorithmVersion, "algorithmVersion");
        }
    }
}
