package com.dbx.dialect.api;

import java.util.HexFormat;

/** A SHA-256 over a plan's canonical encoding, as 64 lowercase hex characters. */
public record PlanFingerprint(String sha256Hex) {

    public PlanFingerprint {
        Checks.present(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != 64 || !sha256Hex.chars().allMatch(HexFormat::isHexDigit)
                || !sha256Hex.equals(sha256Hex.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("a plan fingerprint is 64 lowercase hex characters: " + sha256Hex);
        }
    }
}
