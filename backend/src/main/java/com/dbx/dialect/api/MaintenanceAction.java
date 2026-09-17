package com.dbx.dialect.api;

/** One target maintenance action, addressed by OID where it drops (ADR-0023; obligation 28). Owned by slice 8. */
public sealed interface MaintenanceAction {

    record DropOwnedTable(TargetTableCoordinate table, long pgClassOid) implements MaintenanceAction {

        public DropOwnedTable {
            Checks.present(table, "table");
            Checks.positive(pgClassOid, "pgClassOid");
        }
    }

    record DropEmptySchema(TargetIdentifier schema, long namespaceOid) implements MaintenanceAction {

        public DropEmptySchema {
            Checks.present(schema, "schema");
            Checks.positive(namespaceOid, "namespaceOid");
        }
    }

    record SetOwnedSequence(TargetTableCoordinate sequence, long value, boolean isCalled) implements MaintenanceAction {

        public SetOwnedSequence {
            Checks.present(sequence, "sequence");
        }
    }

    record GrantOnOwnedObject(TargetTableCoordinate object, TargetIdentifier grantee, RequiredPrivilege privilege)
            implements MaintenanceAction {

        public GrantOnOwnedObject {
            Checks.present(object, "object");
            Checks.present(grantee, "grantee");
            Checks.present(privilege, "privilege");
        }
    }
}
