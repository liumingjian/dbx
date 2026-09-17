package com.dbx.dialect.api;

import java.util.HexFormat;
import java.util.Locale;

/** A SHA-256 over a mapping decision's canonical encoding, as 64 lowercase hex characters. */
public record MappingFingerprint(String sha256Hex) {

    public MappingFingerprint {
        Checks.present(sha256Hex, "sha256Hex");
        if (sha256Hex.length() != 64 || !sha256Hex.chars().allMatch(HexFormat::isHexDigit)
                || !sha256Hex.equals(sha256Hex.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("a mapping fingerprint is 64 lowercase hex characters: " + sha256Hex);
        }
    }
}
