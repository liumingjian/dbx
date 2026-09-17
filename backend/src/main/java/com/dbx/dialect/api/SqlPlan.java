package com.dbx.dialect.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * An immutable, typed, fingerprintable SQL plan (ADR-0008 §Plans). A dialect produces it; only the
 * gateway runs it. Every component contributes to the {@link #fingerprint()}, and the encoding
 * length-prefixes every string, so no two different plans can be spelled into the same bytes.
 */
public record SqlPlan(
        OperationKind operationKind,
        List<ParameterizedStatement> statements,
        ResultSchema resultSchema,
        TimeoutClass timeoutClass,
        Set<RequiredPrivilege> requiredPrivileges,
        EvidencePolicy evidencePolicy) {

    /** Bumped when the encoding below changes, so an old fingerprint is never compared to a new one. */
    private static final String ENCODING = "dbx.dialect.SqlPlan/1";

    public SqlPlan {
        Checks.present(operationKind, "operationKind");
        statements = Checks.nonEmptyList(statements, "statements");
        Checks.present(resultSchema, "resultSchema");
        Checks.present(timeoutClass, "timeoutClass");
        requiredPrivileges = Checks.set(requiredPrivileges, "requiredPrivileges");
        Checks.present(evidencePolicy, "evidencePolicy");
    }

    /** Deterministic over content: equal plans fingerprint equal, on any machine, in any run. */
    public PlanFingerprint fingerprint() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            string(out, ENCODING);
            string(out, operationKind.name());
            out.writeInt(statements.size());
            for (ParameterizedStatement statement : statements) {
                string(out, statement.sql());
                out.writeInt(statement.parameters().size());
                for (SqlValue parameter : statement.parameters()) {
                    value(out, parameter);
                }
            }
            string(out, resultSchema.cardinality().name());
            out.writeInt(resultSchema.columns().size());
            for (ResultSchema.Column column : resultSchema.columns()) {
                string(out, column.label());
                string(out, column.databaseType());
                string(out, column.nullability().name());
            }
            string(out, timeoutClass.name());
            // A set has no order of its own; sorting by name keeps the bytes independent of it.
            List<RequiredPrivilege> privileges = requiredPrivileges.stream()
                    .sorted(Comparator.comparing(RequiredPrivilege::name))
                    .toList();
            out.writeInt(privileges.size());
            for (RequiredPrivilege privilege : privileges) {
                string(out, privilege.name());
            }
            string(out, evidencePolicy.name());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return new PlanFingerprint(HexFormat.of().formatHex(sha256().digest(bytes.toByteArray())));
    }

    private static void value(DataOutputStream out, SqlValue value) throws IOException {
        switch (value) {
            case SqlValue.Null n -> {
                string(out, "NULL");
                string(out, n.type().name());
            }
            case SqlValue.Int64 i -> {
                string(out, SqlValue.Type.INT64.name());
                out.writeLong(i.value());
            }
            case SqlValue.Decimal d -> {
                string(out, SqlValue.Type.DECIMAL.name());
                string(out, d.value().toString());
            }
            case SqlValue.Text t -> {
                string(out, SqlValue.Type.TEXT.name());
                string(out, t.value());
            }
            case SqlValue.Bool b -> {
                string(out, SqlValue.Type.BOOLEAN.name());
                out.writeBoolean(b.value());
            }
            case SqlValue.Bytes b -> {
                string(out, SqlValue.Type.BYTES.name());
                byte[] content = b.value();
                out.writeInt(content.length);
                out.write(content);
            }
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absentFromTheJdk) {
            throw new IllegalStateException("every Java platform must provide SHA-256", absentFromTheJdk);
        }
    }
}
