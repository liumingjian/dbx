package com.dbx.dialect.api;

/**
 * TP §7.1's single structured mapping-rule model: table rename, column prune, column rename and
 * target-type override, each with an {@code AUTO} or {@code USER} origin. No regular expressions.
 */
public sealed interface MappingRule {

    RuleOrigin origin();

    enum RuleOrigin {
        AUTO,
        USER
    }

    record TableRename(TableCoordinate source, TargetIdentifier target, RuleOrigin origin) implements MappingRule {

        public TableRename {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.present(origin, "origin");
        }
    }

    record ColumnPrune(ColumnCoordinate source, RuleOrigin origin) implements MappingRule {

        public ColumnPrune {
            Checks.present(source, "source");
            Checks.present(origin, "origin");
        }
    }

    record ColumnRename(ColumnCoordinate source, TargetIdentifier target, RuleOrigin origin) implements MappingRule {

        public ColumnRename {
            Checks.present(source, "source");
            Checks.present(target, "target");
            Checks.present(origin, "origin");
        }
    }

    record TargetTypeOverride(ColumnCoordinate source, TargetType targetType, RuleOrigin origin)
            implements MappingRule {

        public TargetTypeOverride {
            Checks.present(source, "source");
            Checks.present(targetType, "targetType");
            Checks.present(origin, "origin");
        }
    }
}
