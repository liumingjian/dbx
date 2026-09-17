package com.dbx.dialect.api;

/** The version of TP §7.1's {@code <utf8-prefix>_<hash12>} rename that produced a name. */
public record RenameAlgorithmVersion(int value) {

    public RenameAlgorithmVersion {
        Checks.positive(value, "rename algorithm version");
    }
}
